// Resolves and publishes named connections through the HTTPS registry with owner-claim verification.
package com.dark.winter.online.rooms;

import com.dark.winter.online.identity.IdentityStore;
import com.dark.winter.online.identity.ProfileFiles;
import com.dark.winter.online.node.OnlineHttpBodies;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTPS name registry only. Owner-signed information still requires independent actual room/Node verification. */
public final class ConnectionCatalogClient implements AutoCloseable {
    public static final int MAX_BODY_BYTES=32768;
    private static final Gson JSON=new Gson();
    private static final java.util.concurrent.ConcurrentMap<String,java.util.concurrent.atomic.AtomicLong> ISSUED=new ConcurrentHashMap<>();
    private static final Set<String> PUBLISH_FIELDS=Set.of("schema","type","action","name","issuedAt","nonce","id","shareInfo");
    public enum Code{NAME_TAKEN,NOT_FOUND,UNAUTHORIZED,RATE_LIMITED,INVALID_REQUEST,INVALID_PASSWORD,NETWORK,INVALID_RESPONSE,CLOSED}
    public enum Phase{LOCAL,TRANSPORT,HTTP,JSON,SCHEMA,CLAIM,DESCRIPTOR,PASSWORD}
    public enum ContentKind{NONE,JSON,HTML,TEXT,OTHER}
    public static final class Failure extends IOException{
        private final Code code;private final Phase phase;private final int httpStatus;private final ContentKind contentKind;
        public Failure(Code code){this(code,Phase.LOCAL,0,ContentKind.NONE);}
        private Failure(Code code,Phase phase,int httpStatus,ContentKind contentKind){
            super("Connection catalog operation failed: "+code.name());this.code=code;this.phase=phase;
            this.httpStatus=httpStatus>=100&&httpStatus<=599?httpStatus:0;this.contentKind=contentKind;
        }
        public Code code(){return code;}
        public Phase phase(){return phase;}
        /** Zero means unavailable, including full-body transport failures after response headers. */
        public int httpStatus(){return httpStatus;}
        public ContentKind contentKind(){return contentKind;}
        private Failure at(Phase fallback,int status,ContentKind kind){
            return new Failure(code,phase==Phase.LOCAL?fallback:phase,httpStatus==0?status:httpStatus,contentKind==ContentKind.NONE?kind:contentKind);
        }
    }
    /** Validated original owner claim, never a game invitation or proof of a live network path. */
    public static final class Result{
        private final String name,ownerId,shareInfo;private final UUID id;
        private final IdentityStore.SignedPayload claim;private final boolean available;private final long lastSeen;
        private final int httpStatus;private final ContentKind contentKind;
        private Result(String name,UUID id,String ownerId,String shareInfo,IdentityStore.SignedPayload claim,boolean available,long lastSeen,int httpStatus,ContentKind contentKind){
            this.name=name;this.id=id;this.ownerId=ownerId;this.shareInfo=shareInfo;this.claim=claim;this.available=available;this.lastSeen=lastSeen;this.httpStatus=httpStatus;this.contentKind=contentKind;
        }
        public String name(){return name;}
        public UUID id(){return id;}
        public String ownerId(){return ownerId;}
        public String shareInfo(){return shareInfo;}
        public IdentityStore.SignedPayload claim(){return claim;}
        public boolean available(){return available;}
        public long lastSeen(){return lastSeen;}
        /** The password is consumed even if the decrypted descriptor fails the exact namespace/owner binding. */
        public ConnectionInfo.Created open(char[] password)throws IOException{
            try{
                ConnectionInfo.Created opened;
                try{opened=ConnectionInfo.open(shareInfo,password);}
                catch(ConnectionInfo.Invalid invalid){
                    boolean protectedInfo=ConnectionInfo.passwordRequired(shareInfo);
                    throw new Failure(protectedInfo?Code.INVALID_PASSWORD:Code.INVALID_RESPONSE,protectedInfo?Phase.PASSWORD:Phase.DESCRIPTOR,httpStatus,contentKind);
                }
                var descriptor=opened.descriptor();
                String actualName;try{actualName=canonicalName(descriptor.name());}catch(IOException invalid){throw new Failure(Code.INVALID_RESPONSE,Phase.DESCRIPTOR,httpStatus,contentKind);}
                if(!id.equals(descriptor.id())||!ownerId.equals(descriptor.ownerId())||!name.equals(actualName))
                    throw new Failure(Code.INVALID_RESPONSE,Phase.DESCRIPTOR,httpStatus,contentKind);
                return opened;
            }finally{if(password!=null)Arrays.fill(password,'\0');}
        }
        @Override public String toString(){return "ConnectionCatalog.Result[ownerSignedInformationOnly]";}
    }
    private final URI endpoint;
    private final HttpClient http;
    private final ExecutorService executor=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dw-catalog-http-",0).factory());
    private final Semaphore slots=new Semaphore(4);
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();
    public ConnectionCatalogClient(URI endpoint)throws IOException{this(endpoint,false);}
    private ConnectionCatalogClient(URI configured,boolean testLoopback)throws IOException{
        endpoint=endpoint(configured,testLoopback);
        http=HttpClient.newBuilder().executor(executor).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }
    /** Explicit local-fixture injection. Production constructor never accepts plaintext HTTP, including localhost. */
    public static ConnectionCatalogClient forLoopbackTests(URI endpoint)throws IOException{return new ConnectionCatalogClient(endpoint,true);}
    public CompletableFuture<Result> publish(IdentityStore owner,SavedConnections.Entry entry){
        try{
            if(owner==null||entry==null||!entry.hosting()||!entry.descriptor().ownerId().equals(owner.profile().id()))throw new Failure(Code.UNAUTHORIZED);
            JsonObject body=request("publish",entry.descriptor().name());body.addProperty("id",entry.id());body.addProperty("shareInfo",entry.shareInfo());
            return send(owner,body,entry.descriptor().ownerId(),entry.descriptor().id(),entry.shareInfo());
        }catch(IOException|RuntimeException rejected){return CompletableFuture.failedFuture(safe(rejected,Code.INVALID_REQUEST));}
    }
    public CompletableFuture<Result> resolve(IdentityStore identity,String name){
        try{return send(identity,request("resolve",name),null,null,null);}
        catch(IOException|RuntimeException rejected){return CompletableFuture.failedFuture(safe(rejected,Code.INVALID_REQUEST));}
    }
    public CompletableFuture<Result> heartbeat(IdentityStore owner,String name,UUID id){return own(owner,name,id,"heartbeat");}
    public CompletableFuture<Result> unpublish(IdentityStore owner,String name,UUID id){return own(owner,name,id,"unpublish");}
    private CompletableFuture<Result> own(IdentityStore owner,String name,UUID id,String action){
        try{
            if(owner==null||id==null)throw new Failure(Code.INVALID_REQUEST);
            JsonObject body=request(action,name);body.addProperty("id",id.toString());
            return send(owner,body,owner.profile().id(),id,null);
        }catch(IOException|RuntimeException rejected){return CompletableFuture.failedFuture(safe(rejected,Code.INVALID_REQUEST));}
    }
    public static String canonicalName(String requested)throws IOException{
        if(requested==null)throw new Failure(Code.INVALID_REQUEST);
        String name=Normalizer.normalize(requested,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        if(name.isEmpty()||name.codePointCount(0,name.length())>48||name.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7))
            throw new Failure(Code.INVALID_REQUEST);
        return name;
    }
    private static JsonObject request(String action,String name)throws IOException{
        JsonObject body=new JsonObject();body.addProperty("schema",1);body.addProperty("type","connection_catalog_request");body.addProperty("action",action);
        body.addProperty("name",canonicalName(name));body.addProperty("issuedAt",System.currentTimeMillis());body.addProperty("nonce",UUID.randomUUID().toString());return body;
    }
    private CompletableFuture<Result> send(IdentityStore identity,JsonObject body,String owner,UUID expectedId,String expectedInfo){
        if(closed.get())return CompletableFuture.failedFuture(new Failure(Code.CLOSED));
        if(!slots.tryAcquire())return CompletableFuture.failedFuture(new Failure(Code.RATE_LIMITED));
        CompletableFuture<Result> result=new CompletableFuture<>();pending.add(result);
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<?>> upstream=new java.util.concurrent.atomic.AtomicReference<>();
        result.whenComplete((answer,failure)->{pending.remove(result);slots.release();if(result.isCancelled()&&upstream.get()!=null)upstream.get().cancel(true);});
        try{
            if(closed.get()){result.cancel(true);return result;}
            if(identity==null)throw new Failure(Code.INVALID_REQUEST);
            body.addProperty("issuedAt",issuedAt(identity));
            IdentityStore.SignedPayload signed=identity.sign(IdentityStore.Purpose.SERVICE_REQUEST,ProfileFiles.bytes(body));
            byte[] wire=ProfileFiles.bytes(JSON.toJsonTree(signed));if(wire.length>MAX_BODY_BYTES)throw new Failure(Code.INVALID_REQUEST);
            HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                    .header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(wire)).build();
            CompletableFuture<java.net.http.HttpResponse<byte[]>> io=OnlineHttpBodies.send(http,request,MAX_BODY_BYTES,Duration.ofSeconds(10));upstream.set(io);
            if(result.isDone()){io.cancel(true);return result;}
            String expectedName=ProfileFiles.string(body,"name",128);
            io.whenCompleteAsync((response,failure)->{
                if(result.isDone())return;
                try{
                    if(failure!=null)throw new Failure(Code.NETWORK,Phase.TRANSPORT,0,ContentKind.NONE);
                    int status=response.statusCode();ContentKind kind=contentKind(response.headers().firstValue("Content-Type").orElse(""));
                    if(status>=300&&status<400)throw new Failure(Code.NETWORK,Phase.HTTP,status,kind);
                    Result answer=parse(response.body(),status,expectedName,kind);
                    if(owner!=null&&!owner.equals(answer.ownerId())||expectedId!=null&&!expectedId.equals(answer.id())||expectedInfo!=null&&!expectedInfo.equals(answer.shareInfo()))
                        throw new Failure(Code.INVALID_RESPONSE,Phase.CLAIM,answer.httpStatus,answer.contentKind);
                    result.complete(answer);
                }catch(Exception rejected){result.completeExceptionally(safe(rejected,Code.INVALID_RESPONSE));}
            },executor);
        }catch(Exception rejected){result.completeExceptionally(safe(rejected,Code.INVALID_REQUEST));}
        return result;
    }
    private static long issuedAt(IdentityStore identity)throws IOException{
        String id=identity.profile().id();
        if(ISSUED.size()>=4096&&!ISSUED.containsKey(id))throw new Failure(Code.RATE_LIMITED);
        var clock=ISSUED.computeIfAbsent(id,unused->new java.util.concurrent.atomic.AtomicLong());
        while(true){
            long previous=clock.get();if(previous==Long.MAX_VALUE)throw new Failure(Code.INVALID_REQUEST);
            long next=Math.max(System.currentTimeMillis(),previous+1);
            if(next<0)throw new Failure(Code.INVALID_REQUEST);if(clock.compareAndSet(previous,next))return next;
        }
    }
    private static Result parse(byte[] bytes,int status,String expectedName,ContentKind kind)throws IOException{
        Phase phase=status<200||status>=300?Phase.HTTP:Phase.JSON;
        try{
            JsonObject response=ProfileFiles.parse(bytes,MAX_BODY_BYTES);phase=Phase.SCHEMA;
            if(ProfileFiles.number(response,"schema")!=1||!booleanField(response,"ok")) {
                ProfileFiles.fields(response,Set.of("schema","ok","error"),Set.of());
                if(ProfileFiles.number(response,"schema")!=1||booleanField(response,"ok"))throw new Failure(Code.INVALID_RESPONSE);
                Code code=switch(ProfileFiles.string(response,"error",32)){
                    case "name_taken"->Code.NAME_TAKEN;case "not_found"->Code.NOT_FOUND;case "unauthorized"->Code.UNAUTHORIZED;
                    case "rate_limited"->Code.RATE_LIMITED;case "invalid_request"->Code.INVALID_REQUEST;case "unavailable"->Code.NETWORK;default->Code.INVALID_RESPONSE;};
                throw new Failure(code,Phase.HTTP,status,kind);
            }
            if(status<200||status>=300)throw new Failure(Code.INVALID_RESPONSE);
            ProfileFiles.fields(response,Set.of("schema","ok","record","available","lastSeen"),Set.of());
            JsonObject record=child(response,"record");ProfileFiles.fields(record,Set.of("claim"),Set.of());
            phase=Phase.CLAIM;JsonObject envelope=child(record,"claim");strictSignedFields(envelope);
            IdentityStore.SignedPayload claim=JSON.fromJson(envelope,IdentityStore.SignedPayload.class);
            if(!IdentityStore.verify(claim,IdentityStore.Purpose.SERVICE_REQUEST))throw new Failure(Code.INVALID_RESPONSE);
            byte[] data=IdentityStore.decodeData(claim);JsonObject body=ProfileFiles.parse(data,MAX_BODY_BYTES);
            if(!Arrays.equals(data,ProfileFiles.bytes(body)))throw new Failure(Code.INVALID_RESPONSE);
            ProfileFiles.fields(body,PUBLISH_FIELDS,Set.of());
            if(ProfileFiles.number(body,"schema")!=1||!ProfileFiles.string(body,"type",64).equals("connection_catalog_request")
                    ||!ProfileFiles.string(body,"action",16).equals("publish")||ProfileFiles.number(body,"issuedAt")<0)throw new Failure(Code.INVALID_RESPONSE);
            String name=ProfileFiles.string(body,"name",128);if(!name.equals(canonicalName(name))||!name.equals(expectedName))throw new Failure(Code.INVALID_RESPONSE);
            UUID id=uuid(ProfileFiles.string(body,"id",36));uuid(ProfileFiles.string(body,"nonce",36));
            String info=ProfileFiles.string(body,"shareInfo",ConnectionInfo.MAX_LENGTH);
            if(!info.startsWith(ConnectionInfo.PREFIX))throw new Failure(Code.INVALID_RESPONSE);
            ConnectionInfo.passwordRequired(info);
            phase=Phase.SCHEMA;long seen=ProfileFiles.number(response,"lastSeen");if(seen<0)throw new Failure(Code.INVALID_RESPONSE);
            return new Result(name,id,claim.signer().id(),info,claim,booleanField(response,"available"),seen,status,kind);
        }catch(Failure classified){throw classified.at(phase,status,kind);}
        catch(IOException|RuntimeException invalid){throw new Failure(Code.INVALID_RESPONSE,phase,status,kind);}
    }
    private static ContentKind contentKind(String header){
        if(header==null||header.isBlank())return ContentKind.NONE;
        String value=header.substring(0,Math.min(256,header.length())).split(";",2)[0].strip().toLowerCase(Locale.ROOT);
        if(value.equals("application/json")||value.startsWith("application/")&&value.endsWith("+json"))return ContentKind.JSON;
        if(value.equals("text/html")||value.equals("application/xhtml+xml"))return ContentKind.HTML;
        return value.startsWith("text/")?ContentKind.TEXT:ContentKind.OTHER;
    }
    private static void strictSignedFields(JsonObject value)throws IOException{
        ProfileFiles.fields(value,Set.of("schema","purpose","signer","data","signature"),Set.of());
        JsonObject signer=child(value,"signer");ProfileFiles.fields(signer,Set.of("schema","id","publicKey","encryptionPublicKey","nickname","model","privacy","revision","createdAt"),Set.of());
        ProfileFiles.fields(child(signer,"privacy"),Set.of("presence","requests","messages","invitations"),Set.of());
    }
    private static JsonObject child(JsonObject value,String key)throws IOException{JsonElement child=value.get(key);if(child==null||!child.isJsonObject())throw new Failure(Code.INVALID_RESPONSE);return child.getAsJsonObject();}
    private static boolean booleanField(JsonObject value,String key)throws IOException{JsonElement field=value.get(key);if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isBoolean())throw new Failure(Code.INVALID_RESPONSE);return field.getAsBoolean();}
    private static UUID uuid(String raw)throws IOException{try{UUID id=UUID.fromString(raw);if(!raw.equals(id.toString()))throw new Failure(Code.INVALID_RESPONSE);return id;}catch(IllegalArgumentException invalid){throw new Failure(Code.INVALID_RESPONSE);}}
    private static Failure safe(Throwable failure,Code fallback){return failure instanceof Failure known?known:new Failure(fallback);}
    private static URI endpoint(URI uri,boolean loopback)throws IOException{
        if(uri==null||!uri.isAbsolute()||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new Failure(Code.INVALID_REQUEST);
        boolean https=uri.getScheme().equalsIgnoreCase("https");
        boolean local=Set.of("127.0.0.1","::1","[::1]").contains(uri.getHost());
        if(loopback&&!local)throw new Failure(Code.INVALID_REQUEST);
        if(!https&&!(loopback&&local&&uri.getScheme().equalsIgnoreCase("http")))throw new Failure(Code.INVALID_REQUEST);
        String path=uri.getRawPath();if(path==null||path.isEmpty()||path.equals("/"))return uri.resolve("/v1/catalog");
        if(!path.equals("/v1/catalog"))throw new Failure(Code.INVALID_REQUEST);return uri;
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        for(CompletableFuture<?> future:pending)future.cancel(true);
        http.shutdownNow();executor.shutdownNow();
    }
    @Override public String toString(){return "ConnectionCatalogClient[httpsNameRegistry]";}
}
