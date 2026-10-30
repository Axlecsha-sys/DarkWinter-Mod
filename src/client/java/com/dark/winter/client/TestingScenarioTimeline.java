// Builds scenario timeline controls for isolated test-session playback.
package com.dark.winter.client;

import com.dark.winter.testing.TestingScenarios;
import com.google.gson.*;
import net.minecraft.client.Minecraft;

/** UI bridge only: immutable-version restore and author isolation are owned by TestingSessions. */
public final class TestingScenarioTimeline {
    private static volatile String status="Переходы доступны только к настоящим версиям мира";
    private static volatile boolean sessionFeedback;
    private static volatile String checkpointKey="";
    private static volatile java.util.List<String> checkpointRows=java.util.List.of();
    private static volatile boolean refreshing;
    private static long lastRefresh;
    public static java.util.List<String> checkpoints(Minecraft client,String name){
        if(client==null||client.getSingleplayerServer()==null||name==null)return java.util.List.of();
        var server=client.getSingleplayerServer();
        String key=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()+"|"+name;
        long now=System.nanoTime();
        if(!refreshing&&(!key.equals(checkpointKey)||now-lastRefresh>1_000_000_000L)){
            refreshing=true;lastRefresh=now;
            server.execute(()->{
                try{
                    var rows=TestingSessions.scenarioCheckpoints(server,name);
                    client.execute(()->{if(client.getSingleplayerServer()==server){checkpointRows=rows;checkpointKey=key;}refreshing=false;});
                }catch(Exception failure){status="История сценария недоступна: "+failure.getMessage();refreshing=false;}
            });
        }
        return key.equals(checkpointKey)?checkpointRows:java.util.List.of();
    }
    private TestingScenarioTimeline(){}
    public static String status(){return sessionFeedback?TestingSessions.status():status;}
    public static void loadDocument(Minecraft client,String name,java.util.function.Consumer<JsonObject> consumer){
        sessionFeedback=false;
        document(client,name,document->{consumer.accept(JsonParser.parseString(document).getAsJsonObject());status="Загружен сценарий "+name;});
    }
    public static void start(Minecraft client,String name){
        document(client,name,(document)->{
            JsonObject root=JsonParser.parseString(document).getAsJsonObject();String baseline="";
            if(root.has("replay")&&!root.get("replay").isJsonNull())baseline=root.getAsJsonObject("replay").get("baselineVersionId").getAsString();
            if(baseline.isEmpty()){sessionFeedback=false;TestingClient.action("scenario_run",name);status="Запрос запуска в текущей сцене отправлен на сервер";}
            else{TestingDrone.stop();sessionFeedback=true;TestingSessions.scenarioReplay(client,baseline,document,0,0);}
        });
    }
    public static void seek(Minecraft client,String name,String checkpoint){
        String[] fields=checkpoint.split("\\|",4);
        if(fields.length!=4){status="Неверная контрольная точка";return;}
        document(client,name,document->{
            try{
                String version=fields[1].strip();int index=Integer.parseInt(fields[2].strip());long elapsed=Long.parseLong(fields[3].strip());
                TestingDrone.stop();sessionFeedback=true;TestingSessions.scenarioReplay(client,version,document,index,elapsed);
            }catch(RuntimeException failure){sessionFeedback=false;status="Переход отклонён: "+failure.getMessage();}
        });
    }
    public static void checkpoint(Minecraft client,String name,String label){
        if(client==null||client.getSingleplayerServer()==null)return;
        TestingDrone.stop();sessionFeedback=true;TestingSessions.scenarioCheckpoint(client,name,label);
    }
    private static void document(Minecraft client,String name,java.util.function.Consumer<String> consumer){
        if(client==null||client.getSingleplayerServer()==null){status="Откройте тестовый остров";return;}
        var server=client.getSingleplayerServer();
        server.execute(()->{
            try{
                String document=TestingSessions.scenarioDocument(server,name);
                client.execute(()->{try{consumer.accept(document);}catch(Exception failure){sessionFeedback=false;status="Воспроизведение отклонено: "+failure.getMessage();}});
            }catch(Exception failure){sessionFeedback=false;status="Сценарий недоступен: "+failure.getMessage();}
        });
    }
}
