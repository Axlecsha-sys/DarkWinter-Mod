// Serves authenticated control-plane requests without simulating Minecraft worlds.
package com.dark.winter.online.node;

import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.authority.AuthorityNodeService;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;

/** Independent control-plane node. No Minecraft simulation, world files, private peer keys or voice media. */
public final class DirectoryNode implements AutoCloseable {
    public enum RejectionStage { UNKNOWN, ADDRESS_CAPACITY, ADDRESS_RATE, BODY, REQUEST_JSON, REQUEST_SIGNATURE,
        REQUEST_FIELDS, REQUEST_CLOCK, USER, USER_RATE, REGISTER_ARGUMENTS, REGISTER_PROFILE, REGISTER_REVISION,
        REGISTER_ACCESS, REGISTER_STORE, OPERATION, RESPONSE_SIZE, RESPONSE_SIGN, RESPONSE_WRITE }
    public enum RejectionReason { CLOCK_STALE, CLOCK_FUTURE, SIGNATURE, PROFILE_PROOF, PROFILE_REVISION,
        RATE_LIMIT, CAPACITY, INVALID_JSON, INVALID_ARGUMENT, TIMEOUT, IO, OTHER }
    /** Fixed enums and one bounded signed difference only; no request, identity, key or exception is retained. */
    public record Rejection(RejectionStage stage,RejectionReason reason,long clockDeltaMs) {
        public Rejection {
            stage=stage==null?RejectionStage.UNKNOWN:stage;reason=reason==null?RejectionReason.OTHER:reason;
            clockDeltaMs=reason==RejectionReason.CLOCK_STALE||reason==RejectionReason.CLOCK_FUTURE
                    ?Math.max(-300_000,Math.min(300_000,clockDeltaMs)):0;
        }
        public String summary(){return "stage="+stage.name().toLowerCase(Locale.ROOT)
                +" reason="+reason.name().toLowerCase(Locale.ROOT)+" clock_delta_ms="+clockDeltaMs;}
        public JsonObject toJson(){JsonObject value=new JsonObject();value.addProperty("stage",stage.name().toLowerCase(Locale.ROOT));
            value.addProperty("reason",reason.name().toLowerCase(Locale.ROOT));value.addProperty("clock_delta_ms",clockDeltaMs);return value;}
    }
    /** Diagnostic-only state on one request worker; never affects validation or response contents. */
    static final class RejectionTrace {
        private RejectionStage stage=RejectionStage.UNKNOWN;
        private RejectionReason reason;
        private long clockDelta;
        void at(RejectionStage value){stage=Objects.requireNonNull(value);reason=null;clockDelta=0;}
        void clock(long issued,long now){
            stage=RejectionStage.REQUEST_CLOCK;reason=issued<now?RejectionReason.CLOCK_STALE:RejectionReason.CLOCK_FUTURE;
            try{clockDelta=Math.subtractExact(issued,now);}catch(ArithmeticException overflow){clockDelta=issued<now?Long.MIN_VALUE:Long.MAX_VALUE;}
        }
        Rejection snapshot(Throwable failure){
            RejectionReason selected=reason;
            if(selected==null)selected=switch(stage){
                case REQUEST_SIGNATURE->RejectionReason.SIGNATURE;
                case REGISTER_PROFILE->RejectionReason.PROFILE_PROOF;
                case REGISTER_REVISION->RejectionReason.PROFILE_REVISION;
                case ADDRESS_RATE,USER_RATE->RejectionReason.RATE_LIMIT;
                case ADDRESS_CAPACITY->RejectionReason.CAPACITY;
                default->failure instanceof JsonParseException||failure instanceof IllegalStateException?RejectionReason.INVALID_JSON:
                    failure instanceof IllegalArgumentException?RejectionReason.INVALID_ARGUMENT:
                    failure instanceof InterruptedIOException?RejectionReason.TIMEOUT:
                    failure instanceof IOException?RejectionReason.IO:RejectionReason.OTHER;
            };
            return new Rejection(stage,selected,clockDelta);
        }
    }
    /** At most one log per fixed stage/reason pair and 32 logs per node lifetime. */
    static final class RejectionLog {
        private final EnumMap<RejectionStage,EnumSet<RejectionReason>> seen=new EnumMap<>(RejectionStage.class);
        private int emitted;
        synchronized boolean permit(Rejection rejection){
            if(rejection==null||emitted>=32)return false;
            if(!seen.computeIfAbsent(rejection.stage(),ignored->EnumSet.noneOf(RejectionReason.class)).add(rejection.reason()))return false;
            emitted++;return true;
        }
    }
    private final RejectionLog rejectionLog=new RejectionLog();
    private volatile Rejection lastRejection;
    public Optional<Rejection> lastRejection(){return Optional.ofNullable(lastRejection);}
    private void rejected(Rejection value){
        lastRejection=value;
        // Minecraft captures stderr; its JUL configuration can suppress System.Logger warnings.
        if(rejectionLog.permit(value))System.err.println("Online Node rejected request "+value.summary());
    }
    private static final int WIRE_LIMIT=196608, MAX_USERS=256, MAX_QUEUE=128, MAX_TOTAL=16*1024*1024, MAX_DELIVERIES=512;
    private final HttpServer http;
    private final ExecutorService workers=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dw-directory-",0).factory());
    private final Semaphore incoming=new Semaphore(48);
    private final Map<String,User> users=new HashMap<>();
    private final Map<String,Seen> seen=new LinkedHashMap<>();
    private final Map<String,Delivery> deliveries=new LinkedHashMap<>();
    private final String mailboxEpoch=UUID.randomUUID().toString();
    private final Map<String,Rate> addresses=new HashMap<>();
    private final IdentityStore identity;
    private final Clock clock;
    private final Path profileFile;
    private final NodeIceConfiguration ice;
    private final AuthorityNodeService authority;
    private final boolean connectionDirectory;
    private int queuedBytes;
    private boolean closed;
    private record Mail(long sequence,long expires,String from,String kind,JsonObject message,int bytes){}
    private record Seen(String digest,long expires,JsonObject reply){}
    private record Delivery(String sender,String recipient,String messageId,String digest,long expires,long sequence,boolean acknowledged){}
    private static final class Rate {long start;int count;}
    private static final class User {
        IdentityProfile profile;SignedPayload profileProof;long lastSeen,sequence;String status="online";
        final ArrayDeque<Mail> mailbox=new ArrayDeque<>();
        final Set<String> audience=new HashSet<>(),blocked=new HashSet<>();
        final Rate rate=new Rate();
    }
    public DirectoryNode(Path directory,InetSocketAddress address,SSLContext tls,Clock clock)throws IOException {this(directory,address,tls,clock,NodeIceConfiguration.empty(),false);}
    public DirectoryNode(Path directory,InetSocketAddress address,SSLContext tls,Clock clock,NodeIceConfiguration ice)throws IOException {this(directory,address,tls,clock,ice,false);}
    /** An explicit connection has a shared member roster; ordinary directories keep their original privacy rules. */
    public DirectoryNode(Path directory,InetSocketAddress address,SSLContext tls,Clock clock,boolean connectionDirectory)throws IOException {this(directory,address,tls,clock,NodeIceConfiguration.empty(),connectionDirectory);}
    public DirectoryNode(Path directory,InetSocketAddress address,SSLContext tls,Clock clock,NodeIceConfiguration ice,boolean connectionDirectory)throws IOException {
        this.connectionDirectory=connectionDirectory;this.ice=Objects.requireNonNull(ice);ice.validateReady();
        if(address.getAddress()==null)throw new IOException("Адрес узла должен быть определён заранее");
        if(tls==null&&!address.getAddress().isLoopbackAddress())throw new IOException("Внешний узел требует HTTPS");
        this.clock=Objects.requireNonNull(clock);
        Path base=directory.toAbsolutePath().normalize();ProfileFiles.requireOutsideWorld(base);Files.createDirectories(base);
        if(Files.isSymbolicLink(base))throw new IOException("Каталог узла не должен быть ссылкой");
        identity=IdentityStore.open(base.resolve("node-identity"));profileFile=identity.base().resolve("directory-state.json");
        AuthorityNodeService openedAuthority=null;
        try{
            loadProfiles();authority=openedAuthority=AuthorityNodeService.open(identity.base(),identity,clock,System::nanoTime);
            if(tls==null)http=HttpServer.create(address,48);
            else{HttpsServer secure=HttpsServer.create(address,48);secure.setHttpsConfigurator(new HttpsConfigurator(tls));http=secure;}
            http.setExecutor(workers);http.createContext("/v1/request",this::handle);http.start();
        }catch(Exception failure){if(openedAuthority!=null)try{openedAuthority.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}identity.close();workers.shutdownNow();if(failure instanceof IOException io)throw io;throw new IOException("Не удалось открыть служебный узел",failure);}
    }
    public InetSocketAddress address(){return http.getAddress();}
    public IdentityProfile profile(){return identity.profile();}
    private void handle(HttpExchange exchange){
        boolean acquired=false,bodyConsumed=false;RejectionTrace trace=new RejectionTrace();
        try{
            if(!exchange.getRequestMethod().equals("POST")||!exchange.getRequestURI().getPath().equals("/v1/request")){respond(exchange,404,OnlineJson.error("Неизвестный запрос"));return;}
            if(!incoming.tryAcquire()){respond(exchange,429,OnlineJson.error("Узел занят, повторите позже"));return;}acquired=true;
            String address=exchange.getRemoteAddress().getAddress().getHostAddress();
            synchronized(this){
                long now=clock.millis();addresses.entrySet().removeIf(e->now-e.getValue().start>60_000);
                trace.at(RejectionStage.ADDRESS_CAPACITY);
                if(addresses.size()>=1024&&!addresses.containsKey(address))throw new IOException("Слишком много подключений");
                trace.at(RejectionStage.ADDRESS_RATE);rate(addresses.computeIfAbsent(address,k->new Rate()),128,10_000);
            }
            trace.at(RejectionStage.BODY);
            byte[] raw=OnlineHttpBodies.read(exchange,WIRE_LIMIT,Duration.ofSeconds(15));bodyConsumed=true;
            trace.at(RejectionStage.REQUEST_JSON);
            JsonObject object=OnlineJson.object(raw,WIRE_LIMIT);
            SignedPayload envelope=OnlineJson.GSON.fromJson(object,SignedPayload.class);
            trace.at(RejectionStage.REQUEST_SIGNATURE);
            if(!IdentityStore.verify(envelope,Purpose.SERVICE_REQUEST))throw new IOException("Подпись запроса не подтверждена");
            trace.at(RejectionStage.REQUEST_JSON);
            JsonObject request=OnlineJson.object(IdentityStore.decodeData(envelope),65536);
            JsonObject reply=process(envelope.signer(),request,trace);
            trace.at(RejectionStage.RESPONSE_SIGN);
            SignedPayload signed=identity.sign(Purpose.SERVICE_RESPONSE,OnlineJson.bytes(reply));
            trace.at(RejectionStage.RESPONSE_WRITE);respond(exchange,200,OnlineJson.GSON.toJsonTree(signed));
        }catch(Exception failure){
            try{rejected(trace.snapshot(failure));}catch(Throwable ignored){/* Diagnostics never change the original HTTP rejection. */}
            // No envelope, request arguments, invitation token, plaintext or stack trace is logged.
            try{respond(exchange,400,OnlineJson.error(failure instanceof IOException?failure.getMessage():"Некорректный запрос"));}catch(Exception ignored){}
        }finally{try{if(bodyConsumed)exchange.close();else OnlineHttpBodies.abort(exchange);}finally{if(acquired)incoming.release();}}
    }
    private synchronized JsonObject process(IdentityProfile sender,JsonObject request,RejectionTrace trace)throws IOException {
        trace.at(RejectionStage.REQUEST_FIELDS);
        if(closed)throw new IOException("Узел закрывается");
        OnlineJson.keys(request,"requestId","issuedAt","operation","arguments");
        String requestId=canonicalUuid(OnlineJson.string(request,"requestId",36));
        long now=clock.millis(),issued=OnlineJson.integer(request,"issuedAt",0,Long.MAX_VALUE);
        if(issued<now-60_000||issued>now+15_000){trace.clock(issued,now);throw new IOException("Запрос устарел: проверьте часы");}
        String operation=OnlineJson.string(request,"operation",32);
        JsonObject args=OnlineJson.child(request,"arguments");
        String key=sender.id()+":"+requestId,digest=sha(OnlineJson.bytes(request));
        seen.entrySet().removeIf(e->e.getValue().expires()<now);
        Seen previous=seen.get(key);
        if(previous!=null){if(!previous.digest().equals(digest))throw new IOException("Идентификатор запроса уже использован");return previous.reply().deepCopy();}
        if(seen.size()>=1024)throw new IOException("Очередь подтверждений заполнена");
        trace.at(RejectionStage.USER);User user=users.get(sender.id());
        if(user==null){
            if(!operation.equals("register")&&!(connectionDirectory&&operation.equals("connection_probe")))throw new IOException("Сначала зарегистрируйте профиль");
            if(users.size()>=MAX_USERS)throw new IOException("Узел достиг лимита профилей");
            user=new User();user.profile=sender;
        }else if(!user.profile.publicKey().equals(sender.publicKey()))throw new IOException("Ключ профиля изменился");
        trace.at(RejectionStage.USER_RATE);rate(user.rate,64,10_000);prune(now);
        trace.at(RejectionStage.OPERATION);
        JsonObject result=OnlineJson.ok();
        switch(operation){
            case "register" -> {
                trace.at(RejectionStage.REGISTER_ARGUMENTS);OnlineJson.keys(args,"status","audience","blocked","profileProof");
                trace.at(RejectionStage.REGISTER_PROFILE);
                SignedPayload proof=OnlineJson.GSON.fromJson(OnlineJson.child(args,"profileProof"),SignedPayload.class);
                if(!IdentityStore.validateProfileProof(proof).equals(sender))throw new IOException("Профиль не подтверждён владельцем ключа");
                trace.at(RejectionStage.REGISTER_REVISION);
                if(sender.revision()<user.profile.revision()||sender.revision()==user.profile.revision()&&!sender.equals(user.profile))
                    throw new IOException("Получена устаревшая или конфликтующая версия профиля");
                trace.at(RejectionStage.REGISTER_ACCESS);
                User replacement=new User();replacement.profile=sender;replacement.profileProof=proof;
                replacement.status=status(OnlineJson.optional(args,"status","online",24));replacement.lastSeen=now;
                replacement.sequence=user.sequence;replacement.mailbox.addAll(user.mailbox);
                replacement.rate.start=user.rate.start;replacement.rate.count=user.rate.count;
                replaceIds(replacement.audience,args.get("audience"));replaceIds(replacement.blocked,args.get("blocked"));
                User old=users.put(sender.id(),replacement);
                trace.at(RejectionStage.REGISTER_STORE);
                try{saveProfiles();}catch(IOException failure){if(old==null)users.remove(sender.id());else users.put(sender.id(),old);throw failure;}
                result.add("node",OnlineJson.GSON.toJsonTree(identity.profile()));
            }
            case "lookup" -> {
                OnlineJson.keys(args,"id");User target=users.get(peerId(OnlineJson.string(args,"id",64)));
                if(target==null||target.blocked.contains(sender.id()))throw new IOException("Профиль недоступен");
                result.add("profile",OnlineJson.GSON.toJsonTree(target.profile));result.add("profileProof",OnlineJson.GSON.toJsonTree(target.profileProof));
                result.addProperty("status",presence(target,sender.id(),now));
            }
            case "send" -> {
                OnlineJson.keys(args,"recipient","kind","message","messageId");
                String recipient=peerId(OnlineJson.string(args,"recipient",64)),kind=OnlineJson.string(args,"kind",16);
                String messageId=canonicalUuid(OnlineJson.string(args,"messageId",36));
                if(!Set.of("friend","invite","signal","chat","control").contains(kind))throw new IOException("Неизвестный канал");
                JsonObject message=OnlineJson.child(args,"message");int size=OnlineJson.bytes(message).length;
                if(size>49152)throw new IOException("Сообщение слишком большое");
                User target=users.get(recipient);if(target==null)throw new IOException("Адресат ещё не подключался к этому узлу");
                if(target.blocked.contains(sender.id())||user.blocked.contains(recipient))throw new IOException("Общение с участником заблокировано");
                var policy=switch(kind){case "friend"->target.profile.privacy().requests();case "chat"->target.profile.privacy().messages();case "invite"->target.profile.privacy().invitations();default->IdentityProfile.ContactPolicy.ANYONE;};
                if(!allows(policy,target,sender.id()))throw new IOException("Настройки адресата запрещают это сообщение");
                if(kind.equals("chat")||kind.equals("signal")||kind.equals("invite")){
                    SignedPayload sealed=OnlineJson.GSON.fromJson(message,SignedPayload.class);
                    Purpose purpose=switch(kind){case "chat"->Purpose.CHAT;case "invite"->Purpose.INVITATION;default->Purpose.SIGNAL;};
                    IdentityCipher.validateEnvelope(sealed,purpose,target.profile);
                    if(!sealed.signer().id().equals(sender.id()))throw new IOException("Подпись защищённого сообщения неверна");
                    // The node validates the opaque envelope, never decrypts it or holds recipient private keys.
                }
                String delivery=sender.id()+":"+messageId;
                JsonObject binding=new JsonObject();binding.addProperty("recipient",recipient);binding.addProperty("kind",kind);binding.add("message",message);
                String deliveryDigest=sha(ProfileFiles.bytes(binding));Delivery previousDelivery=deliveries.get(delivery);
                if(previousDelivery!=null&&!previousDelivery.digest().equals(deliveryDigest))throw new IOException("Идентификатор сообщения уже связан с другим содержимым");
                Mail existing=target.mailbox.stream().filter(mail->mail.message().get("_delivery").getAsString().equals(delivery)).findFirst().orElse(null);
                if(previousDelivery!=null&&previousDelivery.acknowledged()){
                    result.addProperty("queued",false);result.addProperty("delivered",false);result.addProperty("acknowledged",true);result.addProperty("sequence",previousDelivery.sequence());
                }else if(existing!=null){
                    result.addProperty("queued",true);result.addProperty("delivered",false);result.addProperty("acknowledged",false);result.addProperty("sequence",existing.sequence());
                }else{
                    if(target.mailbox.size()>=MAX_QUEUE||queuedBytes+size>MAX_TOTAL||previousDelivery==null&&deliveries.size()>=MAX_DELIVERIES)
                        throw new IOException("Очередь адресата или журнал подтверждений заполнены");
                    if(target.sequence==Long.MAX_VALUE)throw new IOException("Исчерпан диапазон сообщений");
                    long previousSequence=target.sequence;
                    JsonObject stored=new JsonObject();stored.addProperty("_delivery",delivery);stored.add("payload",message.deepCopy());
                    long expires=previousDelivery==null?now+(kind.equals("signal")?120_000:24*60*60*1000L):previousDelivery.expires();
                    Mail mail=new Mail(++target.sequence,expires,sender.id(),kind,stored,size);
                    target.mailbox.addLast(mail);queuedBytes+=size;
                    deliveries.put(delivery,new Delivery(sender.id(),recipient,messageId,deliveryDigest,expires,mail.sequence(),false));
                    try{saveProfiles();}catch(IOException failure){
                        target.mailbox.removeLastOccurrence(mail);queuedBytes-=size;target.sequence=previousSequence;
                        if(previousDelivery==null)deliveries.remove(delivery);else deliveries.put(delivery,previousDelivery);throw failure;
                    }
                    result.addProperty("queued",true);result.addProperty("delivered",false);result.addProperty("acknowledged",false);result.addProperty("sequence",mail.sequence());
                }
            }
            case "poll" -> {
                OnlineJson.keys(args,"ack","status");long ack=OnlineJson.integer(args,"ack",0,user.sequence);
                user.status=status(OnlineJson.optional(args,"status","online",24));user.lastSeen=now;
                List<Mail> removed=new ArrayList<>();Map<String,Delivery> oldReceipts=new HashMap<>();
                while(!user.mailbox.isEmpty()&&user.mailbox.getFirst().sequence()<=ack){
                    Mail mail=user.mailbox.removeFirst();removed.add(mail);queuedBytes-=mail.bytes();
                    String delivery=mail.message().get("_delivery").getAsString();Delivery prior=deliveries.get(delivery);
                    if(prior==null)throw new IOException("Повреждён журнал сообщений");oldReceipts.put(delivery,prior);
                    deliveries.put(delivery,new Delivery(prior.sender(),prior.recipient(),prior.messageId(),prior.digest(),prior.expires(),prior.sequence(),true));
                }
                if(!removed.isEmpty())try{saveProfiles();}catch(IOException failure){
                    for(int i=removed.size()-1;i>=0;i--){Mail mail=removed.get(i);user.mailbox.addFirst(mail);queuedBytes+=mail.bytes();}
                    deliveries.putAll(oldReceipts);throw failure;
                }
                JsonArray messages=new JsonArray();int total=0;
                for(Mail mail:user.mailbox){
                    if(messages.size()>=16||total+mail.bytes()+512>52_000)break; // Signed response payload remains within identity wire cap.
                    JsonObject row=new JsonObject();row.addProperty("sequence",mail.sequence());row.addProperty("from",mail.from());row.addProperty("kind",mail.kind());row.add("message",mail.message().get("payload").deepCopy());
                    messages.add(row);total+=OnlineJson.bytes(row).length;
                }
                result.add("messages",messages);
                JsonArray peers=new JsonArray();
                for(String id:user.audience){User peer=users.get(id);if(peer==null)continue;JsonObject row=new JsonObject();row.addProperty("id",id);row.addProperty("status",presence(peer,sender.id(),now));int size=OnlineJson.bytes(row).length;if(total+size>58_000)break;peers.add(row);total+=size;}
                result.add("presence",peers);
            }
            case "connection_probe","connection_members" -> {
                OnlineJson.keys(args);
                if(!connectionDirectory)throw new IOException("Список соединений недоступен на этом узле");
                // A signed availability check does not register a member or refresh presence.
                if(operation.equals("connection_probe"))result.addProperty("available",true);
                JsonArray members=new JsonArray();
                for(var entry:users.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()){
                    User peer=entry.getValue();
                    if(now<peer.lastSeen||now-peer.lastSeen>45_000||peer.profileProof==null
                            ||peer.blocked.contains(sender.id())||user.blocked.contains(entry.getKey()))continue;
                    JsonObject member=new JsonObject();member.addProperty("id",entry.getKey());
                    member.addProperty("name",peer.profile.nickname());member.addProperty("online",true);
                    members.add(member);if(members.size()>=128)break;
                }
                result.add("members",members);
            }
            case "authority" -> {JsonObject receipt=authority.execute(sender,args);for(var entry:receipt.entrySet())result.add(entry.getKey(),entry.getValue());}
            case "ice" -> {OnlineJson.keys(args);JsonObject offered=ice.issue(sender.id(),clock);for(var entry:offered.entrySet())result.add(entry.getKey(),entry.getValue());}
            default -> throw new IOException("Неизвестная операция узла");
        }
        result.addProperty("requestId",requestId);result.addProperty("issuedAt",now);
        result.addProperty("mailboxEpoch",mailboxEpoch);result.addProperty("lastSequence",users.containsKey(sender.id())?users.get(sender.id()).sequence:0);
        trace.at(RejectionStage.RESPONSE_SIZE);
        if(OnlineJson.bytes(result).length>60000)throw new IOException("Ответ превышает допустимый размер");
        seen.put(key,new Seen(digest,now+120_000,result.deepCopy()));return result;
    }
    private static boolean allows(IdentityProfile.ContactPolicy policy,User target,String sender){
        return policy==IdentityProfile.ContactPolicy.ANYONE||policy==IdentityProfile.ContactPolicy.FRIENDS&&target.audience.contains(sender);
    }
    private static String presence(User target,String requester,long now){
        if(target.blocked.contains(requester)||target.status.equals("invisible")||now-target.lastSeen>45_000)return "offline";
        var visibility=target.profile.privacy().presence();
        if(visibility==IdentityProfile.Visibility.NOBODY||visibility==IdentityProfile.Visibility.FRIENDS&&!target.audience.contains(requester))return "offline";
        return target.status;
    }
    private void prune(long now){
        for(User user:users.values())user.mailbox.removeIf(mail->{if(mail.expires()<=now){queuedBytes-=mail.bytes();return true;}return false;});
        deliveries.entrySet().removeIf(entry->entry.getValue().expires()<=now);
    }
    private void rate(Rate rate,int max,long duration)throws IOException {
        long now=clock.millis();if(now-rate.start>=duration){rate.start=now;rate.count=0;}
        if(++rate.count>max)throw new IOException("Слишком частые запросы, повторите позже");
    }
    private static String status(String value)throws IOException {
        if(!Set.of("online","on_island","busy","invisible","offline").contains(value))throw new IOException("Неизвестный статус");return value;
    }
    private static String peerId(String id)throws IOException {if(!id.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Некорректный DarkWinter ID");return id;}
    private static void replaceIds(Set<String> target,JsonElement value)throws IOException {
        if(value==null)return;if(!value.isJsonArray()||value.getAsJsonArray().size()>1024)throw new IOException("Некорректный список доступа");
        Set<String> replacement=new HashSet<>();for(JsonElement entry:value.getAsJsonArray()){if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isString())throw new IOException("Некорректный ID");replacement.add(peerId(entry.getAsString()));}
        target.clear();target.addAll(replacement);
    }
    private void loadProfiles()throws IOException {
        if(!Files.exists(profileFile))return;
        if(Files.isSymbolicLink(profileFile)||Files.size(profileFile)>4*1024*1024)throw new IOException("Некорректное хранилище узла");
        JsonObject data=OnlineJson.object(Files.readAllBytes(profileFile),4*1024*1024);
        long schema=OnlineJson.integer(data,"schema",1,2);
        ProfileFiles.fields(data,schema>=2?Set.of("schema","profiles","deliveries"):Set.of("schema","profiles"),Set.of());
        JsonArray list=data.getAsJsonArray("profiles");if(list==null||list.size()>MAX_USERS)throw new IOException("Некорректный список профилей");
        for(JsonElement entry:list){
            JsonObject row=entry.getAsJsonObject();ProfileFiles.fields(row,schema>=2?Set.of("profileProof","audience","blocked","sequence"):Set.of("profileProof","audience","blocked"),Set.of());User user=new User();
            user.profileProof=OnlineJson.GSON.fromJson(OnlineJson.child(row,"profileProof"),SignedPayload.class);
            user.profile=IdentityStore.validateProfileProof(user.profileProof);
            replaceIds(user.audience,row.get("audience"));replaceIds(user.blocked,row.get("blocked"));
            user.sequence=schema>=2?OnlineJson.integer(row,"sequence",0,Long.MAX_VALUE):0;
            if(users.put(user.profile.id(),user)!=null)throw new IOException("Повтор профиля");
        }
        if(schema>=2){
            JsonArray receipts=data.getAsJsonArray("deliveries");if(receipts==null||receipts.size()>MAX_DELIVERIES)throw new IOException("Некорректный журнал подтверждений");
            for(JsonElement element:receipts){
                JsonObject row=element.getAsJsonObject();ProfileFiles.fields(row,Set.of("sender","recipient","messageId","digest","expires","sequence","acknowledged"),Set.of());
                Delivery value=new Delivery(peerId(OnlineJson.string(row,"sender",64)),peerId(OnlineJson.string(row,"recipient",64)),
                        canonicalUuid(OnlineJson.string(row,"messageId",36)),OnlineJson.string(row,"digest",64),
                        OnlineJson.integer(row,"expires",0,Long.MAX_VALUE),OnlineJson.integer(row,"sequence",1,Long.MAX_VALUE),OnlineJson.bool(row,"acknowledged",false));
                User target=users.get(value.recipient());
                if(!users.containsKey(value.sender())||target==null
                        ||!value.digest().matches("[0-9a-f]{64}")||value.sequence()>target.sequence
                        ||deliveries.put(value.sender()+":"+value.messageId(),value)!=null)throw new IOException("Повреждён журнал подтверждений");
            }
        }
        prune(clock.millis());
    }
    private void saveProfiles()throws IOException {
        JsonObject data=new JsonObject();data.addProperty("schema",2);JsonArray profiles=new JsonArray();
        for(String id:new TreeSet<>(users.keySet())){
            User user=users.get(id);JsonObject row=new JsonObject();row.add("profileProof",OnlineJson.GSON.toJsonTree(user.profileProof));
            row.add("audience",OnlineJson.GSON.toJsonTree(user.audience));row.add("blocked",OnlineJson.GSON.toJsonTree(user.blocked));row.addProperty("sequence",user.sequence);profiles.add(row);
        }
        data.add("profiles",profiles);JsonArray receipts=new JsonArray();
        for(String key:new TreeSet<>(deliveries.keySet()))receipts.add(OnlineJson.GSON.toJsonTree(deliveries.get(key)));data.add("deliveries",receipts);
        Path temp=profileFile.resolveSibling("profiles-"+UUID.randomUUID()+".tmp");
        try{
            byte[] bytes=OnlineJson.bytes(data);OnlineJson.object(bytes,4*1024*1024);try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBufferWrite.write(channel,bytes);channel.force(true);}
            try{Files.move(temp,profileFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){throw new IOException("Файловая система не поддерживает атомарное сохранение узла",unsupported);}
        }finally{Files.deleteIfExists(temp);}
    }
    private static final class ByteBufferWrite{static void write(java.nio.channels.FileChannel channel,byte[] bytes)throws IOException{var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);}}
    private static String canonicalUuid(String value)throws IOException{try{if(!UUID.fromString(value).toString().equals(value))throw new IOException("Некорректный идентификатор");return value;}catch(IllegalArgumentException invalid){throw new IOException("Некорректный идентификатор",invalid);}}
    private static String sha(byte[] bytes)throws IOException{try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}}
    private static void respond(HttpExchange exchange,int status,JsonElement body)throws IOException {
        byte[] bytes=OnlineJson.bytes(body);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.sendResponseHeaders(status,bytes.length);
        // Closing this output drains an unread request inside the JDK. The handler owns close/abort in its finally.
        OutputStream output=exchange.getResponseBody();output.write(bytes);output.flush();
    }
    @Override public void close()throws IOException {
        synchronized(this){if(closed)return;closed=true;}http.stop(0);workers.shutdownNow();
        IOException failure=null;try{authority.close();}catch(IOException error){failure=error;}
        try{identity.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}if(failure!=null)throw failure;
    }
}
