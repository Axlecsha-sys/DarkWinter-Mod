// Explicit operator-supplied ICE endpoints.
package com.dark.winter.online.node;

import com.dark.winter.online.OnlineJson;
import com.google.gson.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/** Explicit operator-supplied ICE endpoints. The long-lived TURN secret never enters a response or world. */
public final class NodeIceConfiguration {
    private record Server(List<String> urls,boolean turn) {
        Server {urls=List.copyOf(urls);}
        @Override public String toString(){return "IceEndpoint[urls="+urls.size()+"]";}
    }
    private final List<Server> servers;
    private final Set<String> audience;
    private final String secretEnvironment;
    private final int ttlSeconds;
    private final Function<String,String> environment;
    private NodeIceConfiguration(List<Server> servers,Set<String> audience,String name,int ttl,Function<String,String> environment){
        this.servers=List.copyOf(servers);this.audience=Set.copyOf(audience);secretEnvironment=name;ttlSeconds=ttl;this.environment=Objects.requireNonNull(environment);
    }
    public static JsonObject defaults(){
        JsonObject json=new JsonObject();json.add("servers",new JsonArray());json.add("credentialAudience",new JsonArray());
        json.addProperty("turnSecretEnvironment","DARKWINTER_TURN_SECRET");json.addProperty("ttlSeconds",120);return json;
    }
    public static NodeIceConfiguration empty(){try{return parse(defaults());}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    public static NodeIceConfiguration parse(JsonObject json)throws IOException{return parse(json,System::getenv);}
    /** Embedders may supply their process environment reader; configuration never contains a secret value. */
    public static NodeIceConfiguration parse(JsonObject json,Function<String,String> environment)throws IOException {
        try {
            OnlineJson.keys(json,"servers","credentialAudience","turnSecretEnvironment","ttlSeconds");
            if(!json.has("servers")||!json.get("servers").isJsonArray()||!json.has("credentialAudience")||!json.get("credentialAudience").isJsonArray())
                throw new IOException("ICE requires endpoint and credential audience arrays");
            String variable=OnlineJson.optional(json,"turnSecretEnvironment","DARKWINTER_TURN_SECRET",64);
            if(!variable.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IOException("Invalid TURN secret environment name");
            int ttl=(int)(json.has("ttlSeconds")?OnlineJson.integer(json,"ttlSeconds",30,300):120);
            JsonArray endpoints=json.getAsJsonArray("servers"),recipients=json.getAsJsonArray("credentialAudience");
            if(endpoints.size()>8||recipients.size()>256)throw new IOException("ICE configuration limit exceeded");
            Set<String> audience=new HashSet<>();for(JsonElement recipient:recipients){
                if(!recipient.isJsonPrimitive()||!recipient.getAsJsonPrimitive().isString())throw new IOException("Invalid TURN credential audience");
                String id=recipient.getAsString();identity(id);if(!audience.add(id))throw new IOException("Duplicate TURN credential audience");
            }
            List<Server> servers=new ArrayList<>();Set<String> unique=new HashSet<>();boolean hasTurn=false;
            for(JsonElement endpoint:endpoints){
                if(!endpoint.isJsonObject())throw new IOException("Invalid ICE endpoint");JsonObject row=endpoint.getAsJsonObject();OnlineJson.keys(row,"urls");
                if(!row.has("urls")||!row.get("urls").isJsonArray()||row.getAsJsonArray("urls").isEmpty()||row.getAsJsonArray("urls").size()>8)
                    throw new IOException("ICE endpoint requires one to eight explicit URLs");
                List<String> urls=new ArrayList<>();Boolean turn=null;
                for(JsonElement raw:row.getAsJsonArray("urls")){
                    if(!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isString())throw new IOException("Invalid ICE URL");
                    String value=raw.getAsString();boolean next=validateUrl(value);
                    if(turn!=null&&turn!=next)throw new IOException("STUN and TURN URLs require separate entries");
                    if(!unique.add(value))throw new IOException("Duplicate ICE URL");turn=next;urls.add(value);
                }
                servers.add(new Server(urls,turn));hasTurn|=turn;
            }
            if(hasTurn&&audience.isEmpty())throw new IOException("TURN requires an explicit credential audience");
            return new NodeIceConfiguration(servers,audience,variable,ttl,environment);
        }catch(RuntimeException invalid){throw new IOException("Invalid ICE configuration",invalid);}
    }
    private static boolean validateUrl(String value)throws IOException {
        if(value==null||value.length()>2048||!StandardCharsets.US_ASCII.newEncoder().canEncode(value)||value.chars().anyMatch(c->c<=32||c==127)||value.contains("@")||value.contains("%")||value.contains("#")||value.contains("/")||value.contains("\\"))
            throw new IOException("Invalid explicit ICE URL");
        int separator=value.indexOf(':');if(separator<1)throw new IOException("ICE URL requires an explicit scheme");
        String scheme=value.substring(0,separator);if(!Set.of("stun","stuns","turn","turns").contains(scheme))throw new IOException("Unsupported ICE scheme");
        boolean turn=scheme.startsWith("turn");String address=value.substring(separator+1);int query=address.indexOf('?');
        if(query>=0){
            String transport=address.substring(query+1);address=address.substring(0,query);
            if(!turn||!Set.of("transport=udp","transport=tcp").contains(transport)||scheme.equals("turns")&&!transport.equals("transport=tcp"))throw new IOException("Invalid ICE transport");
        }
        try{
            URI authority=URI.create("https://"+address);
            if(address.isEmpty()||authority.getRawUserInfo()!=null||authority.getHost()==null||!authority.getRawPath().isEmpty()||authority.getRawQuery()!=null||authority.getPort()==0||authority.getPort()>65535)
                throw new IOException("Invalid ICE address");
            if(address.endsWith(":")||address.contains("?"))throw new IOException("Invalid ICE port");
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid ICE address",invalid);}
        return turn;
    }
    private static void identity(String id)throws IOException {if(id==null||!id.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid TURN credential identity");}
    private byte[] secret()throws IOException {
        String value=environment.apply(secretEnvironment);
        if(value==null||!value.matches("[A-Za-z0-9_-]{43,128}"))throw new IOException("Configured TURN secret environment is missing or invalid");
        return value.getBytes(StandardCharsets.US_ASCII);
    }
    /** Startup validation happens before a node creates its identity or opens its HTTP listener. */
    public void validateReady()throws IOException {
        if(servers.stream().noneMatch(Server::turn))return;byte[] secret=secret();Arrays.fill(secret,(byte)0);
    }
    public JsonObject issue(String recipient,Clock clock)throws IOException {
        identity(recipient);Objects.requireNonNull(clock);JsonObject result=new JsonObject();JsonArray issued=new JsonArray();long expiry=0;
        boolean authorized=audience.contains(recipient);
        for(Server server:servers){
            if(server.turn()&&!authorized)continue;
            JsonObject row=new JsonObject();JsonArray urls=new JsonArray();server.urls().forEach(urls::add);row.add("urls",urls);
            if(server.turn()){
                if(expiry==0){long now=clock.instant().getEpochSecond();if(now<0||now>Long.MAX_VALUE-ttlSeconds)throw new IOException("Invalid TURN credential clock");expiry=now+ttlSeconds;}
                String username=expiry+":"+recipient;byte[] secret=secret();
                try{
                    Mac hmac=Mac.getInstance("HmacSHA1");hmac.init(new SecretKeySpec(secret,"HmacSHA1"));
                    row.addProperty("username",username);row.addProperty("credential",Base64.getEncoder().encodeToString(hmac.doFinal(username.getBytes(StandardCharsets.US_ASCII))));
                }catch(GeneralSecurityException unavailable){throw new IOException("TURN credential signing is unavailable",unavailable);}
                finally{Arrays.fill(secret,(byte)0);}
            }
            issued.add(row);
        }
        result.add("servers",issued);result.addProperty("expiresAt",expiry);return result;
    }
    public int serverCount(){return servers.size();}
    public Set<String> credentialAudience(){return audience;}
    public int ttlSeconds(){return ttlSeconds;}
    @Override public String toString(){return "NodeIceConfiguration[servers="+servers.size()+",audience="+audience.size()+",credentials=redacted]";}
}
