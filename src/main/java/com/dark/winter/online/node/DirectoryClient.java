// Sends bounded signed directory requests and verifies the expected service identity.
package com.dark.winter.online.node;

import com.dark.winter.online.OnlineJson;
import com.dark.winter.online.identity.*;
import com.dark.winter.online.identity.IdentityStore.*;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Signed, bounded asynchronous RPC. HTTPS for remote nodes; plaintext only on literal loopback for local QA. */
public final class DirectoryClient implements AutoCloseable {
    /** Stable diagnostics only; remote response text is never exposed as a user-facing error. */
    public enum FailureKind { INVALID_ADDRESS, HTTPS_REQUIRED, NODE_CHANGED, CLOCK_SKEW, REJECTED, INVALID_RESPONSE }
    public static final class Failure extends IOException {
        private final FailureKind kind;
        public Failure(FailureKind kind,String message){super(message);this.kind=Objects.requireNonNull(kind);}
        public FailureKind kind(){return kind;}
    }
    private final URI endpoint;
    private final IdentityStore identity;
    private final Clock clock;
    private final ExecutorService worker=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dw-directory-client-",0).factory());
    private final HttpClient http;
    private final Semaphore inflight=new Semaphore(4);
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile IdentityProfile node;
    private volatile String pinnedNodeId;
    private volatile long lastSuccess;
    private volatile String mailboxEpoch="";
    private volatile long lastSequence;
    public DirectoryClient(URI configured,IdentityStore identity,Clock clock,String expectedNodeId)throws IOException {
        this.endpoint=validate(configured);this.identity=Objects.requireNonNull(identity);this.clock=Objects.requireNonNull(clock);
        pinnedNodeId=expectedNodeId==null?"":expectedNodeId;
        http=HttpClient.newBuilder().executor(worker).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }
    public URI endpoint(){return endpoint;}
    public IdentityProfile node(){return node;}
    public boolean connected(){return !closed.get()&&lastSuccess>0&&clock.millis()-lastSuccess<45_000;}
    public String pinnedNodeId(){return pinnedNodeId;}
    public String mailboxEpoch(){return mailboxEpoch;}
    public long lastSequence(){return lastSequence;}
    public CompletableFuture<JsonObject> request(String operation,JsonObject arguments){
        if(closed.get())return CompletableFuture.failedFuture(new IOException("Служба друзей закрыта"));
        if(!inflight.tryAcquire())return CompletableFuture.failedFuture(new IOException("Дождитесь завершения предыдущего запроса"));
        try{
            String id=UUID.randomUUID().toString();JsonObject body=new JsonObject();body.addProperty("requestId",id);body.addProperty("issuedAt",clock.millis());body.addProperty("operation",operation);body.add("arguments",arguments.deepCopy());
            SignedPayload signed=identity.sign(Purpose.SERVICE_REQUEST,OnlineJson.bytes(body));
            byte[] bytes=OnlineJson.bytes(OnlineJson.GSON.toJsonTree(signed));
            if(bytes.length>196608)throw new IOException("Запрос слишком большой");
            HttpRequest request=HttpRequest.newBuilder(endpoint.resolve("v1/request")).timeout(Duration.ofSeconds(15))
                    .header("Content-Type","application/json").header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            CompletableFuture<JsonObject> future=OnlineHttpBodies.send(http,request,196608,Duration.ofSeconds(15))
                .thenApplyAsync(response->{
                    try{
                        byte[] received=response.body();
                        if(response.statusCode()!=200)throw new Failure(rejectedResponseKind(response.statusCode(),received),"Служба друзей отклонила запрос");
                        SignedPayload envelope=OnlineJson.GSON.fromJson(OnlineJson.object(received,196608),SignedPayload.class);
                        if(!IdentityStore.verify(envelope,Purpose.SERVICE_RESPONSE))throw new Failure(FailureKind.INVALID_RESPONSE,"Подпись служебного ответа не подтверждена");
                        JsonObject answer=OnlineJson.object(IdentityStore.decodeData(envelope),65536);
                        if(!id.equals(OnlineJson.string(answer,"requestId",36)))throw new Failure(FailureKind.INVALID_RESPONSE,"Ответ относится к другому запросу");
                        long timestamp=OnlineJson.integer(answer,"issuedAt",0,Long.MAX_VALUE),now=clock.millis();
                        if(timestamp<now-120_000||timestamp>now+15_000)throw new Failure(FailureKind.CLOCK_SKEW,"Получен устаревший ответ службы");
                        if(!OnlineJson.bool(answer,"ok",false))throw new Failure(FailureKind.REJECTED,"Служба не выполнила запрос");
                        String epoch=OnlineJson.string(answer,"mailboxEpoch",36);try{if(!UUID.fromString(epoch).toString().equals(epoch))throw new IllegalArgumentException();}catch(IllegalArgumentException invalid){throw new IOException("Некорректный номер очереди",invalid);}
                        long sequence=OnlineJson.integer(answer,"lastSequence",0,Long.MAX_VALUE);
                        synchronized(this){
                            if(closed.get())throw new IOException("Запрос отменён");
                            if(!pinnedNodeId.isEmpty()&&!pinnedNodeId.equals(envelope.signer().id()))throw new Failure(FailureKind.NODE_CHANGED,"Ключ служебного узла изменился");
                            if(pinnedNodeId.isEmpty())pinnedNodeId=envelope.signer().id();node=envelope.signer();
                            mailboxEpoch=epoch;lastSequence=sequence;lastSuccess=now;
                        }return answer;
                    }catch(IOException|IllegalStateException|JsonParseException failure){throw new CompletionException(failure);}
                },worker);
            future.whenComplete((value,failure)->inflight.release());
            return future.copy();
        }catch(Exception failure){inflight.release();return CompletableFuture.failedFuture(failure);}
    }
    /**
     * An unsigned HTTP error may supply this one fixed troubleshooting hint only.
     * It remains a rejection: no node identity, pin, delivery, clock correction or authority is accepted.
     */
    static FailureKind rejectedResponseKind(int status,byte[] body){
        if(status!=400||body==null||body.length==0||body.length>512)return FailureKind.REJECTED;
        try{
            JsonObject hint=OnlineJson.object(body,512);
            if(!hint.keySet().equals(Set.of("ok","message"))||OnlineJson.bool(hint,"ok",true))return FailureKind.REJECTED;
            return "Запрос устарел: проверьте часы".equals(OnlineJson.string(hint,"message",64))
                    ?FailureKind.CLOCK_SKEW:FailureKind.REJECTED;
        }catch(IOException|RuntimeException invalid){return FailureKind.REJECTED;}
    }
    /** A node signature alone must never authorize a substitute peer encryption key. */
    public static IdentityProfile profile(JsonObject answer)throws IOException {
        try{
            SignedPayload proof=OnlineJson.GSON.fromJson(OnlineJson.child(answer,"profileProof"),SignedPayload.class);
            IdentityProfile verified=IdentityStore.validateProfileProof(proof);
            IdentityProfile advertised=OnlineJson.GSON.fromJson(OnlineJson.child(answer,"profile"),IdentityProfile.class);
            if(!verified.equals(advertised))throw new IOException("Профиль адресата не совпадает с его подписью");
            return verified;
        }catch(JsonParseException|IllegalStateException invalid){throw new IOException("Некорректный профиль адресата",invalid);}
    }
    public static URI validate(URI uri)throws IOException {
        if(uri==null||!uri.isAbsolute()||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new Failure(FailureKind.INVALID_ADDRESS,"Укажите адрес службы без паролей и параметров");
        String scheme=uri.getScheme().toLowerCase(Locale.ROOT),host=uri.getHost().toLowerCase(Locale.ROOT);
        if(!scheme.equals("https")){
            if(!scheme.equals("http")||!Set.of("localhost","127.0.0.1","[::1]","::1").contains(host))throw new Failure(FailureKind.HTTPS_REQUIRED,"Для внешней службы требуется HTTPS");
            if(host.equals("localhost"))try{uri=new URI("http",null,"127.0.0.1",uri.getPort(),uri.getPath(),null,null);}catch(URISyntaxException impossible){throw new IOException(impossible);}
        }
        if(uri.getPort()==0||uri.getPort()>65535||uri.getPath().contains(".."))throw new Failure(FailureKind.INVALID_ADDRESS,"Некорректный адрес службы");
        String text=uri.toASCIIString();if(text.length()>2048)throw new Failure(FailureKind.INVALID_ADDRESS,"Слишком длинный адрес");
        return URI.create(text.endsWith("/")?text:text+"/");
    }
    @Override public void close(){if(!closed.compareAndSet(false,true))return;http.shutdownNow();worker.shutdownNow();}
}
