// Persists editor camera views within their owning Testing save.
package com.dark.winter.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

/** Saved camera views belong to the save and never change ordinary-world controls. */
public final class TestingCameraState {
    private record View(String name,double x,double y,double z,float yaw,float pitch,boolean eye) {}
    private static final List<View> VIEWS=new ArrayList<>();
    private static boolean loaded;
    private static String message="";
    private TestingCameraState() {}
    public static void reset() {
        TestingDrone.stop();
        VIEWS.clear();loaded=false;message="";
    }
    private static Path file() {
        return Minecraft.getInstance().getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                .resolve("data/darkwinter-testing-viewpoints.json");
    }
    private static void load() {
        if(loaded || !TestingClient.active())return;
        loaded=true;
        try {
            Path file=file();
            if(!Files.exists(file))return;
            if(Files.size(file)>65536)throw new IllegalArgumentException("Viewpoint file exceeds 64 KiB");
            var root=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            if(root.get("schema").getAsInt()!=1&&root.get("schema").getAsInt()!=2)return;
            for(var el:root.getAsJsonArray("views")) {
                var j=el.getAsJsonObject();
                var v=new View(j.get("name").getAsString(),j.get("x").getAsDouble(),j.get("y").getAsDouble(),
                        j.get("z").getAsDouble(),j.get("yaw").getAsFloat(),j.get("pitch").getAsFloat(),j.has("eye")&&j.get("eye").getAsBoolean());
                if(VIEWS.size()<32 && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z)
                        && Float.isFinite(v.yaw) && Float.isFinite(v.pitch)) VIEWS.add(v);
            }
        } catch(Exception e) { message="Ошибка файла точек обзора";LoggerFactory.getLogger("DarkWinter/Testing").warn(message,e); }
    }
    public static List<String> names() { load();return VIEWS.stream().map(View::name).toList(); }
    public static String status() { return message; }
    public static boolean drone() { return TestingDrone.active(); }
    public static void releaseDrone() { TestingDrone.stop(); }
    public static void toggleDrone() {
        if(TestingDrone.active())TestingDrone.stop();else TestingDrone.start();
        message=TestingDrone.status();
    }
    public static void teleport(String coordinates) {
        try{TestingDrone.teleport(coordinates);message="Положение свободной камеры обновлено";}
        catch(RuntimeException e){message="Ошибка координат камеры: "+e.getMessage();}
    }
    public static void save(String name) {
        if(!TestingClient.active())return;
        load();
        String clean=name.strip();
        if(clean.isEmpty() || clean.length()>64){message="Имя точки: 1–64 символа";return;}
        var p=TestingDrone.active()?TestingDrone.entity():Minecraft.getInstance().player;
        VIEWS.removeIf(v->v.name.equals(clean));
        if(VIEWS.size()>=32){message="Лимит: 32 точки обзора";return;}
        VIEWS.add(new View(clean,p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot(),TestingDrone.active()));
        persist();message="Сохранена точка: "+clean;
    }
    public static void restore(String name) {
        load();
        for(View view:VIEWS)if(view.name.equals(name)) {
            double y=view.y;
            float eye=Minecraft.getInstance().player.getEyeHeight();
            if(TestingDrone.active()&&!view.eye)y+=eye;
            if(!TestingDrone.active()&&view.eye)y-=eye;
            TestingClient.action("teleport",view.x+","+y+","+view.z+","+view.yaw+","+view.pitch);
            message="Точка: "+name;return;
        }
        message="Точка обзора не найдена";
    }
    public static void delete(String name) {
        load();if(VIEWS.removeIf(v->v.name.equals(name)))persist();
    }
    private static void persist() {
        try {
            var root=new JsonObject();root.addProperty("schema",2);
            var array=new JsonArray();
            for(View v:VIEWS){
                var j=new JsonObject();j.addProperty("name",v.name);j.addProperty("x",v.x);j.addProperty("y",v.y);j.addProperty("z",v.z);
                j.addProperty("yaw",v.yaw);j.addProperty("pitch",v.pitch);j.addProperty("eye",v.eye);array.add(j);
            }
            root.add("views",array);Path target=file();Files.createDirectories(target.getParent());
            Path tmp=Files.createTempFile(target.getParent(),"dw-views-",".tmp");
            try {
                Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(root),StandardCharsets.UTF_8);
                try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(java.nio.file.AtomicMoveNotSupportedException ignored){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}
            }finally{Files.deleteIfExists(tmp);}
        }catch(Exception e){message="Не удалось сохранить точки обзора";LoggerFactory.getLogger("DarkWinter/Testing").warn(message,e);}
    }
}
