// Avatar choices from the actual current native session.
package com.dark.winter.client.online;

import com.dark.winter.online.server.OnlineGameHost;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Avatar choices from the actual current native session. Directory/Friends snapshots are never accepted here. */
public final class OnlineAvatarSkins {
    public record Avatar(UUID gameId,String model,String nickname,long profileRevision) {}
    private record Binding(long generation,OnlineConnectionClient.ExpectedHost host,MinecraftServer local) {}
    private record State(Binding binding,Map<UUID,Avatar> avatars) {}
    private record Cached(String model,PlayerSkin original,PlayerSkin resolved) {}
    private static final Map<UUID,Cached> RESOLVED=new ConcurrentHashMap<>();
    private static volatile State current;
    private OnlineAvatarSkins() {}
    /** Called only by the transport's authenticated root-state callback. */
    public static void acceptPacket(JsonObject root){
        var host=OnlineConnectionClient.authenticatedHost();if(host==null)return;
        JsonObject claimed=child(root,"host");
        if(!host.hostId().equals(text(claimed,"hostId"))||!host.sessionId().toString().equals(text(claimed,"sessionId"))
                ||host.epoch()!=number(claimed,"epoch",-1))return;
        accept(new Binding(OnlineConnectionClient.authenticationGeneration(),host,null),root);
    }
    /** The root argument is the complete {host,lab} packet, including for the local host. */
    public static void acceptLocal(MinecraftServer server,JsonObject root){
        Minecraft client=Minecraft.getInstance();OnlineGameHost host=OnlineGameHost.get(server);
        if(server==null||client.getSingleplayerServer()!=server||host==null)return;
        try{
            String identity=host.localIdentityId();
            if(!identity.equals(host.ownerId())&&!host.access().trustedLead(identity))return;
            var expected=new OnlineConnectionClient.ExpectedHost(identity,host.sessionId(),host.epoch(),host.compatibilityDigest());
            JsonObject claimed=child(root,"host");
            if(!identity.equals(text(claimed,"hostId"))||!host.sessionId().toString().equals(text(claimed,"sessionId"))
                    ||host.epoch()!=number(claimed,"epoch",-1))return;
            accept(new Binding(-1,expected,server),root);
        }catch(RuntimeException closed){clear();}
    }
    private static void accept(Binding binding,JsonObject root){
        if(!valid(binding))return;
        try{
            JsonObject lab=child(root,"lab");if(number(lab,"schema",-1)!=1)throw new IllegalArgumentException("Lab schema");
            JsonObject online=child(lab,"online");
            JsonArray rows=online.has("participants")?online.getAsJsonArray("participants"):new JsonArray();
            if(rows.size()>16)throw new IllegalArgumentException("Participant limit");
            Map<UUID,Avatar> avatars=new HashMap<>();
            for(JsonElement value:rows){
                JsonObject row=value.getAsJsonObject();String model=text(row,"model");
                if(model.isEmpty())continue; // No profile means no invented appearance.
                if(!model.equals("male")&&!model.equals("female"))throw new IllegalArgumentException("Avatar model");
                String identifier=text(row,"gameId");UUID gameId=UUID.fromString(identifier);
                if(!identifier.equals(gameId.toString()))throw new IllegalArgumentException("Native UUID");
                String nickname=text(row,"nickname");if(nickname.length()>64)throw new IllegalArgumentException("Nickname limit");
                long revision=number(row,"profileRevision",number(row,"revision",0));if(revision<0)throw new IllegalArgumentException("Profile revision");
                if(avatars.put(gameId,new Avatar(gameId,model,nickname,revision))!=null)throw new IllegalArgumentException("Duplicate avatar");
            }
            if(valid(binding)){current=new State(binding,Map.copyOf(avatars));RESOLVED.clear();}
        }catch(RuntimeException malformed){clear();}
    }
    private static boolean valid(Binding binding){
        try{
            Minecraft client=Minecraft.getInstance();if(client.level==null||client.player==null)return false;
            if(binding.local()!=null){
                OnlineGameHost host=OnlineGameHost.get(binding.local());
                return client.getSingleplayerServer()==binding.local()&&host!=null&&host.sessionId().equals(binding.host().sessionId())
                        &&host.epoch()==binding.host().epoch()&&(host.localIdentityId().equals(host.ownerId())||host.access().trustedLead(host.localIdentityId()));
            }
            return binding.generation()==OnlineConnectionClient.authenticationGeneration()&&OnlineConnectionClient.authenticated()
                    &&binding.host().equals(OnlineConnectionClient.authenticatedHost());
        }catch(RuntimeException closed){return false;}
    }
    private static State state(){State value=current;return value!=null&&valid(value.binding())?value:null;}
    public static void clear(){current=null;RESOLVED.clear();}
    public static void tick(){State value=current;if(value!=null&&!valid(value.binding()))clear();}
    public static Map<UUID,Avatar> avatars(){State value=state();return value==null?Map.of():value.avatars();}
    public static Avatar avatar(UUID gameId){State value=state();return value==null?null:value.avatars().get(gameId);}
    public static String nickname(UUID gameId){Avatar value=avatar(gameId);return value==null?"":value.nickname();}
    public static boolean contains(UUID gameId){State value=state();return value!=null&&value.avatars().containsKey(gameId);}
    public static PlayerSkin resolve(UUID gameId,PlayerSkin original){
        if(original==null)return null;
        State state=state();Avatar avatar=state==null?null:state.avatars().get(gameId);if(avatar==null)return original;
        Cached cached=RESOLVED.get(gameId);if(cached!=null&&cached.original()==original&&cached.model().equals(avatar.model()))return cached.resolved();
        // Existing Human meshes use an authored UV atlas. Never map a vanilla skin onto that mesh.
        PlayerModelType model=avatar.model().equals("female")?PlayerModelType.SLIM:PlayerModelType.WIDE;
        PlayerSkin resolved=new PlayerSkin(original.body(),original.cape(),original.elytra(),model,original.secure());
        RESOLVED.put(gameId,new Cached(avatar.model(),original,resolved));return resolved;
    }
    private static JsonObject child(JsonObject root,String key){return root.has(key)&&root.get(key).isJsonObject()?root.getAsJsonObject(key):new JsonObject();}
    private static String text(JsonObject root,String key){return root.has(key)&&root.get(key).isJsonPrimitive()?root.get(key).getAsString():"";}
    private static long number(JsonObject root,String key,long fallback){return root.has(key)?root.get(key).getAsBigDecimal().longValueExact():fallback;}
}
