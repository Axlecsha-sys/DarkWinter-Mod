// Exposes sanitized online state and actions to client screens and notifications.
package com.dark.winter.client.online;

import com.dark.winter.client.TestingWorlds;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** A view boundary only. Network/authentication/persistence remain in the installed coordinator. */
public final class OnlineUiBridge {
    public interface Backend {
        JsonObject snapshot();
        void command(String action,JsonObject args);
    }
    private static volatile Backend installed;
    private static String safeError="";
    private static final Set<String> notified=new LinkedHashSet<>();
    private static boolean notificationsInitialized;
    private static int ticks;
    private OnlineUiBridge(){}

    public static void install(Backend backend){
        installed=backend;safeError="";notified.clear();notificationsInitialized=false;ticks=0;
    }
    public static Backend backend(){return installed;}
    public static JsonObject read(){
        Backend target=installed;
        if(target==null)return unavailable("Онлайн-служба ещё не подключена.");
        try{
            JsonObject source=target.snapshot();
            if(source==null)return unavailable("Данные соединений пока не получены.");
            JsonObject view=source.deepCopy();
            if(number(view,"schema",1)!=1)return unavailable("Версия данных соединений не поддерживается этой сборкой.");
            // Secret material has no place in a UI snapshot or a Lab semantic state.
            for(String key:Set.of("password","privateKey","secretKey","secret","accessToken","refreshToken"))view.remove(key);
            JsonObject profile=object(view,"profile");
            for(String key:Set.of("password","privateKey","secretKey","secret","accessToken","refreshToken"))profile.remove(key);
            if(!safeError.isEmpty())view.addProperty("uiError",safeError);
            return view;
        }catch(RuntimeException failure){return unavailable("Не удалось прочитать состояние соединений. Повторите позже.");}
    }
    public static boolean submit(String action,JsonObject args){
        if(action==null||!action.matches("[a-z][a-z0-9_]{0,63}"))return false;
        Backend target=installed;JsonObject view=read();
        if(target==null||!allowed(view,action)){
            safeError=reason(view,action);return false;
        }
        try{
            // Never print command arguments: profile backup/import includes a transient password.
            target.command(action,args==null?new JsonObject():args.deepCopy());
            safeError="";return true;
        }catch(RuntimeException failure){
            safeError="Не удалось отправить действие. Проверьте состояние подключения.";return false;
        }
    }
    public static boolean allowed(JsonObject view,String action){
        if(!flag(object(view,"capabilities"),action,false))return false;
        return !flag(view,"busy",false)||Set.of("online_close","reconnect_cancel").contains(action);
    }
    public static String reason(JsonObject view,String action){
        String result=string(object(view,"capabilityReasons"),action,"");
        if(result.isBlank())result=string(object(view,"reasons"),action,"");
        if(!result.isBlank())return result;
        if(flag(view,"busy",false))return "Дождитесь завершения текущего действия.";
        if("invite_create".equals(action))return "Причина недоступности приглашения ещё не получена. Проверьте состояние острова.";
        result=string(object(view,"service"),"reason","");
        return result.isBlank()?"Это действие сейчас недоступно.":result;
    }
    /** Called by the main client coordinator; only real newly observed notification IDs create a toast. */
    public static void tick(Minecraft client){
        if(++ticks%10!=0)return;
        JsonObject view=read();
        boolean relevant=client.level==null||TestingWorlds.isTesting()||flag(object(view,"session"),"active",false);
        if(!relevant)return;
        JsonArray notes=array(view,"notifications");
        int start=Math.max(0,notes.size()-128),shown=0;
        for(int index=start;index<notes.size();index++){
            if(!notes.get(index).isJsonObject())continue;
            JsonObject note=notes.get(index).getAsJsonObject();String id=string(note,"id","");
            if(id.isBlank()||!notified.add(id))continue;
            if(id.startsWith("friend:")||id.startsWith("chat:")||id.startsWith("rejected:"))continue;
            String text=publicText(string(note,"status",string(note,"text","")));
            if(notificationsInitialized&&!text.isBlank()&&shown++<3&&!(client.gui.screen() instanceof FriendsScreen))
                SystemToast.add(client.gui.toastManager(),SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.literal("DarkWinter"),Component.literal(text.substring(0,Math.min(text.length(),240))));
        }
        notificationsInitialized=true;
        while(notified.size()>2048)notified.remove(notified.iterator().next());
    }
    private static JsonObject unavailable(String message){
        JsonObject result=new JsonObject();result.addProperty("schema",1);result.addProperty("status",message);
        JsonObject service=new JsonObject();service.addProperty("reason",message);
        result.add("service",service);result.add("profile",new JsonObject());result.add("session",new JsonObject());result.add("capabilities",new JsonObject());
        for(String key:new String[]{"friends","requests","invites","participants","messages","notifications"})result.add(key,new JsonArray());
        return result;
    }
    /** Invitations and room bootstraps may be copied explicitly, never rendered in status text or toasts. */
    public static String publicText(String value){return value==null?"":value.replaceAll("dwi1_[A-Za-z0-9_-]+","[Код приглашения скрыт]")
            .replaceAll("dwroom1_[A-Za-z0-9_-]+","[Код сети скрыт]")
            .replaceAll("dwconnect1_[A-Za-z0-9_-]+","[Данные соединения скрыты]");}
    public static String string(JsonObject object,String key,String fallback){
        try{return object.has(key)&&object.get(key).isJsonPrimitive()?object.get(key).getAsString():fallback;}catch(RuntimeException failure){return fallback;}
    }
    public static boolean flag(JsonObject object,String key,boolean fallback){
        try{return object.has(key)&&object.get(key).isJsonPrimitive()?object.get(key).getAsBoolean():fallback;}catch(RuntimeException failure){return fallback;}
    }
    public static int number(JsonObject object,String key,int fallback){
        try{return object.has(key)&&object.get(key).isJsonPrimitive()?object.get(key).getAsInt():fallback;}catch(RuntimeException failure){return fallback;}
    }
    public static JsonObject object(JsonObject object,String key){
        return object!=null&&object.has(key)&&object.get(key).isJsonObject()?object.getAsJsonObject(key):new JsonObject();
    }
    public static JsonArray array(JsonObject object,String key){
        return object!=null&&object.has(key)&&object.get(key).isJsonArray()?object.getAsJsonArray(key):new JsonArray();
    }
}
