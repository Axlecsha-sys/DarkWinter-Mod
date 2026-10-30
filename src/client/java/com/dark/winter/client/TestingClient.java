// Coordinates client editor tools for the current marked Testing save.
package com.dark.winter.client;

import com.dark.winter.testing.TestingServer;
import com.dark.winter.client.online.OnlineTestingView;
import com.mojang.blaze3d.platform.InputConstants;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Client tools are strictly scoped to the currently marked integrated save. */
public final class TestingClient {
    public static final String[] MODULES = {"hud","weather","wind","precipitation","visuals","atmosphere","stamina","survival","motion","camera"};
    public static final String[] LABELS = {"Игровой HUD","Погода","Ветер","Снег и позёмка","Шейдеры / постэффекты","Атмосфера / туман","Выносливость","Голод / выживание","Движение","Камера персонажа"};
    private static final Map<String,KeyMapping> MODULE_KEYS = new LinkedHashMap<>();
    private static KeyMapping collapseKey, characterKey, diagnosticKey, selectKey, secondKey, undoKey, redoKey, rotateKey;
    private static Object observedLevel;
    private static boolean collapsed, diagnostic;
    private static Boolean requestedDiagnostic;
    private static long requestedDiagnosticAt;
    private static String previousMode = "";
    private static BlockPos selected, adjacent, first, second;
    private static long lastRender;
    private static final float[] FRAME_MS = new float[120];
    private static final String[] FRAME_SCREEN = new String[120];
    private static long lastFrameSampleMillis,cpuSampleMillis;
    private static int frameIndex, frameCount;
    private static double cpuLoad = -1;
    private static long cpuMeasuredAt;
    private static String reportStatus="";
    private static String lastScreenshot="",screenshotStatus="Снимок ещё не сохранён";
    private static String lastProtectionPreview="";
    private static volatile List<String> recentErrors=List.of();
    private static String errorScope="Сбор журнала выключен";
    private static long lastLogPoll;
    private TestingClient() {}

    public static void init() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("darkwinter","testing"));
        collapseKey=key("panel",InputConstants.KEY_F7,category);
        characterKey=key("character",InputConstants.KEY_F9,category);
        diagnosticKey=key("diagnostics",InputConstants.KEY_F10,category);
        selectKey=key("select",InputConstants.UNKNOWN.getValue(),category);
        secondKey=key("select_second",InputConstants.UNKNOWN.getValue(),category);
        undoKey=key("undo",InputConstants.UNKNOWN.getValue(),category);
        redoKey=key("redo",InputConstants.UNKNOWN.getValue(),category);
        rotateKey=key("rotate",InputConstants.UNKNOWN.getValue(),category);
        int[] defaults={InputConstants.KEY_NUMPAD1,InputConstants.KEY_NUMPAD2,InputConstants.KEY_NUMPAD3,InputConstants.KEY_NUMPAD4,
                InputConstants.KEY_NUMPAD5,InputConstants.KEY_NUMPAD6,InputConstants.KEY_NUMPAD7,InputConstants.KEY_NUMPAD8,
                InputConstants.KEY_NUMPAD9,InputConstants.KEY_NUMPAD0};
        for(int i=0;i<MODULES.length;i++)MODULE_KEYS.put(MODULES[i],key(MODULES[i],defaults[i],category));
    }
    private static KeyMapping key(String name,int code,KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.darkwinter.testing_"+name,InputConstants.Type.KEYBOARD,code,category));
    }
    public static boolean active() {
        Minecraft c=Minecraft.getInstance();
        return TestingWorlds.isTesting() && c.level!=null && c.player!=null && (OnlineTestingView.active()||c.getSingleplayerServer()!=null);
    }
    public static void reset() {
        selected=null;adjacent=null;first=null;second=null;collapsed=false;diagnostic=false;requestedDiagnostic=null;requestedDiagnosticAt=0;
        previousMode="";frameCount=0;frameIndex=0;lastRender=0;lastFrameSampleMillis=0;cpuSampleMillis=0;lastScreenshot="";screenshotStatus="Снимок ещё не сохранён";lastProtectionPreview="";recentErrors=List.of();lastLogPoll=0;errorScope="Сбор журнала выключен";TestingCameraState.reset();
    }
    public static void tick(Minecraft c) {
        if(c.level!=observedLevel) { observedLevel=c.level;reset(); }
        if(!TestingSessions.onlineCoordinated()&&!OnlineTestingView.active()&&com.dark.winter.online.server.OnlineGameHost.get(c.getSingleplayerServer())==null)TestingSessions.tick(c);
        if(!active()) { discardClicks();return; }
        var labSettings=TestingAccess.settings();
        boolean configuredDiagnostics=labSettings!=null&&labSettings.diagnostics;
        if(configuredDiagnostics!=diagnostic){
            diagnostic=configuredDiagnostics;lastRender=0;
            if(diagnostic){frameCount=0;frameIndex=0;cpuLoad=-1;cpuMeasuredAt=0;lastFrameSampleMillis=0;cpuSampleMillis=0;}
        }
        if(requestedDiagnostic!=null&&(configuredDiagnostics==requestedDiagnostic||System.nanoTime()-requestedDiagnosticAt>2_000_000_000L))requestedDiagnostic=null;
        if(diagnostic)pollErrorLog(c);
        TestingDrone.tick(c);
        String mode=value("mode");
        if(!mode.equals(previousMode)) {
            if(mode.startsWith("TEST_")) { collapsed=true;TestingCameraState.releaseDrone(); }
            previousMode=mode;
        }
        if(c.gui.screen()==null) {
            while(collapseKey.consumeClick()) collapsed=!collapsed;
            while(characterKey.consumeClick()) if(!mode.startsWith("TEST_"))action("mode","EDIT".equals(mode)?"CHARACTER":"EDIT");
            while(diagnosticKey.consumeClick()) diagnostics(!(requestedDiagnostic==null?diagnostic:requestedDiagnostic));
            while(selectKey.consumeClick()) capture(false);
            while(secondKey.consumeClick()) capture(true);
            while(undoKey.consumeClick()) action("undo","");
            while(redoKey.consumeClick()) action("redo","");
            while(rotateKey.consumeClick()) if(selected!=null) action("rotate",coords(selected));
            for(var entry:MODULE_KEYS.entrySet())
                while(entry.getValue().consumeClick()) action("toggle",entry.getKey());
        }
        if(c.gui.screen() instanceof TestingEditorScreen screen && screen.previewBrush() && selected!=null){
            String preview=coords(selected)+"|"+value("radius")+"|"+value("brushShape");
            if(!preview.equals(lastProtectionPreview)){
                lastProtectionPreview=preview;
                action("preview_brush",coords(selected)+"|"+value("radius"));
            }
        }else lastProtectionPreview="";
        if(c.gui.screen() instanceof TestingEditorScreen || !collapsed) drawWorldGizmos(c);
    }
    private static void discardClicks() {
        if(collapseKey==null)return;
        for(KeyMapping key:List.of(collapseKey,characterKey,diagnosticKey,selectKey,secondKey,undoKey,redoKey,rotateKey))while(key.consumeClick()){}
        for(KeyMapping key:MODULE_KEYS.values())while(key.consumeClick()){}
    }
    public static void action(String action,String value) {
        Minecraft c=Minecraft.getInstance();
        if(!active()) return;
        if(OnlineTestingView.active()&&!OnlineTestingView.allowed(action)){OnlineTestingView.action(action,value);return;}
        if(action.equals("camera")){
            if("drone".equals(value))TestingDrone.start();else TestingDrone.stop();
            return;
        }
        if(action.equals("teleport")&&TestingDrone.active()){
            TestingCameraState.teleport(value);return;
        }
        if(action.equals("mode")&&TestingDrone.active())TestingDrone.stop();
        if(action.equals("group_rotate") && (value==null||value.isBlank()) && first!=null&&second!=null)
            value=coords(first)+"|"+coords(second);
        if(OnlineTestingView.active()){OnlineTestingView.action(action,value);return;}
        if(requiresCheckpoint(action,value)){
            TestingDrone.stop();
            TestingSessions.guardedAction(c,action,value);
            return;
        }
        UUID player=c.player.getUUID();
        TestingServer.action(c.getSingleplayerServer(),player,action,value);
    }
    private static boolean requiresCheckpoint(String action,String value) {
        if(action.equals("biome")||action.equals("vegetation")||action.equals("resize"))return true;
        if(java.util.Set.of("scene_move","scene_copy","scene_rotate","scene_remove").contains(action)){
            String id=value.split("\\|",2)[0];
            for(var node:com.dark.winter.testing.TestingScene.views(Minecraft.getInstance().getSingleplayerServer()))
                if(node.id().equals(id))return node.blockCount()>64||node.npcCount()>0;
        }
        try{
            String[] parts=value.split("\\|");
            if(action.equals("brush"))return parts.length>2&&Integer.parseInt(parts[2])>8;
            if((action.equals("move")||action.equals("copy")||action.equals("group_rotate"))&&parts.length>=2){
                String[] a=parts[0].split(","),b=parts[1].split(",");
                long volume=1;
                for(int i=0;i<3;i++)volume*=Math.abs((long)Integer.parseInt(a[i])-Integer.parseInt(b[i]))+1;
                return volume>64;
            }
        }catch(RuntimeException ignored){ /* The server rejects malformed requests without touching the world. */ }
        return false;
    }
    public static String value(String key) {
        Minecraft c=Minecraft.getInstance();
        if(!active()) return "";
        String result=OnlineTestingView.active()?OnlineTestingView.value(key):TestingServer.value(c.getSingleplayerServer(),key);
        return result==null?"":result;
    }
    public static boolean enabled(String module) {
        Minecraft c=Minecraft.getInstance();
        return active() && (OnlineTestingView.active()?OnlineTestingView.enabled(module):TestingServer.enabled(c.getSingleplayerServer(),module));
    }
    public static String worldName(){var server=Minecraft.getInstance().getSingleplayerServer();return OnlineTestingView.active()?OnlineTestingView.worldName():server==null?"":server.getWorldData().getLevelName();}
    public static String status(){return OnlineTestingView.active()?OnlineTestingView.status():TestingServer.status(Minecraft.getInstance().getSingleplayerServer());}
    public static List<com.dark.winter.testing.TestingScene.ObjectView> scene(){return OnlineTestingView.active()?OnlineTestingView.scene():com.dark.winter.testing.TestingScene.views(Minecraft.getInstance().getSingleplayerServer());}
    public static List<TestingServer.ZoneView> zones(){return OnlineTestingView.active()?OnlineTestingView.zones():TestingServer.protectedZones(Minecraft.getInstance().getSingleplayerServer());}
    public static List<com.dark.winter.testing.TestingScenarios.TriggerView> triggers(){return OnlineTestingView.active()?OnlineTestingView.triggers():com.dark.winter.testing.TestingScenarios.triggerViews(Minecraft.getInstance().getSingleplayerServer());}
    public static List<BlockPos> protectedPreview(){return OnlineTestingView.active()?OnlineTestingView.protectedPreview():TestingServer.previewProtected(Minecraft.getInstance().getSingleplayerServer());}
    public static OnlineTestingView.Generator generator(){
        if(OnlineTestingView.active())return OnlineTestingView.generator();
        var server=Minecraft.getInstance().getSingleplayerServer();
        if(server!=null&&server.overworld().getChunkSource().getGenerator() instanceof com.dark.winter.testing.TestingIslandGenerator island)
            return new OnlineTestingView.Generator(true,island.radius(),island.shape(),island.hills());
        return new OnlineTestingView.Generator(false,96,"ROUND",1);
    }
    public static String label(String module) {
        for(int i=0;i<MODULES.length;i++) if(MODULES[i].equals(module)) return LABELS[i];
        return module;
    }
    public static String shortcut(String module) {
        var key=MODULE_KEYS.get(module);
        if(key==null)return "—";
        String binding=key.saveString();
        if(binding.startsWith("key.keyboard.keypad."))return "Num"+binding.substring("key.keyboard.keypad.".length());
        return key.getTranslatedKeyMessage().getString();
    }
    public static BlockPos selected() { return selected; }
    public static BlockPos adjacent() { return adjacent; }
    public static BlockPos first() { return first; }
    public static BlockPos second() { return second; }
    public static void select(BlockPos position,BlockPos placement) {
        selected=position==null?null:position.immutable();
        adjacent=placement==null?null:placement.immutable();
    }
    public static void capture(boolean secondCorner) {
        Minecraft c=Minecraft.getInstance();
        if(c.hitResult instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK) {
            select(hit.getBlockPos(),hit.getBlockPos().relative(hit.getDirection()));
            mark(secondCorner);
        }
    }
    public static void mark(boolean secondCorner) {
        if(selected==null) return;
        if(secondCorner) second=selected; else first=selected;
        action(secondCorner?"select_b":"select_a",coords(selected));
        if(!OnlineTestingView.active())TestingPrefabs.mark(selected,secondCorner);
    }
    public static void transform(String kind,BlockPos delta) {
        if(selected==null) return;
        BlockPos a=first==null?selected:first,b=second==null?a:second;
        action(kind,coords(a)+"|"+coords(b)+"|"+coords(delta));
        // Selection remains at its observed location until the player selects the result.
        // A rejected asynchronous server edit must never move client selection optimistically.
    }
    public static String coords(BlockPos pos) { return pos.getX()+","+pos.getY()+","+pos.getZ(); }
    public static boolean diagnostics() { return diagnostic; }
    public static void diagnostics(boolean value) {
        if(!active()){diagnostic=false;requestedDiagnostic=null;lastRender=0;return;}
        requestedDiagnostic=value;requestedDiagnosticAt=System.nanoTime();
        Minecraft client=Minecraft.getInstance();
        action("parameter","diagnostics="+value);
    }
    public static void collapse(boolean value) { collapsed=value; }
    public static void screenshot() {
        if(!active())return;Minecraft client=Minecraft.getInstance();Object level=client.level;
        String name="darkwinter-testing-"+java.time.Instant.now().toEpochMilli()+"-"+java.util.UUID.randomUUID()+".png";
        Path file=client.gameDirectory.toPath().resolve("screenshots").resolve(name);lastScreenshot="";screenshotStatus="Снимок сохраняется";
        Screenshot.grab(client.gameDirectory,name,client.gameRenderer.mainRenderTarget(),1,message->client.execute(()->{
            if(client.level==level){
                if(Files.isRegularFile(file)){lastScreenshot="screenshots/"+name;screenshotStatus="Сохранён: "+lastScreenshot;}
                else screenshotStatus="Не удалось сохранить снимок";
            }
            client.showDebugChat(message);
        }));
    }
    public static String screenshotStatus(){return screenshotStatus;}

    public static List<String> recentErrors(){return recentErrors;}
    public static String errorScope(){return errorScope;}
    private static void pollErrorLog(Minecraft c){
        long now=System.nanoTime();if(lastLogPoll!=0&&now-lastLogPoll<2_000_000_000L)return;lastLogPoll=now;
        Path file=c.gameDirectory.toPath().resolve("logs/latest.log");
        try(var channel=Files.newByteChannel(file,java.nio.file.StandardOpenOption.READ)){
            long start=Math.max(0,channel.size()-65536);channel.position(start);
            var buffer=java.nio.ByteBuffer.allocate((int)Math.min(65536,channel.size()-start));
            while(buffer.hasRemaining()&&channel.read(buffer)>0){}
            buffer.flip();String text=java.nio.charset.StandardCharsets.UTF_8.decode(buffer).toString();
            if(start>0){int newline=text.indexOf('\n');if(newline>=0)text=text.substring(newline+1);}
            List<String> rows=text.lines().filter(line->line.contains("/WARN")||line.contains("/ERROR")||line.contains(" WARN ")||line.contains(" ERROR ")||line.startsWith("Caused by:"))
                    .map(line->line.length()>512?line.substring(0,512)+"…":line).toList();
            recentErrors=List.copyOf(rows.subList(Math.max(0,rows.size()-32),rows.size()));
            errorScope="Последние 64 KiB: "+recentErrors.size()+" строк WARN/ERROR; "+java.time.Instant.now();
        }catch(Exception e){errorScope="Журнал недоступен: "+e.getMessage();recentErrors=List.of();}
    }
    private static Path reportsDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("darkwinter-testing/client-reports");
    }
    public static String reportStatus() { return reportStatus; }
    public static List<String> reports() {return TestingReports.list(Minecraft.getInstance());}
    public static void saveClientReport(String name) {
        if(!active())return;
        try {
            Minecraft c=Minecraft.getInstance();
            JsonObject root=new JsonObject();root.addProperty("schema",2);
            root.addProperty("name",name);root.addProperty("utc",java.time.Instant.now().toString());
            root.addProperty("world",worldName());
            if(OnlineTestingView.active())root.addProperty("onlineWorldId",OnlineTestingView.worldId());
            root.addProperty("mode",value("mode"));root.addProperty("guiWidth",c.getWindow().getGuiScaledWidth());
            root.addProperty("guiHeight",c.getWindow().getGuiScaledHeight());root.addProperty("fps",c.getFps());
            root.addProperty("samples",frameCount);root.addProperty("diagnosticsEnabled",diagnostic);
            float[] sorted=new float[frameCount];float sum=0;
            JsonArray frames=new JsonArray();java.util.Set<String> sampledScreens=new java.util.LinkedHashSet<>();
            for(int i=0;i<frameCount;i++){
                int slot=(frameIndex-frameCount+i+FRAME_MS.length)%FRAME_MS.length;
                float ms=FRAME_MS[slot];sorted[i]=ms;sum+=ms;frames.add(ms);if(FRAME_SCREEN[slot]!=null)sampledScreens.add(FRAME_SCREEN[slot]);
            }
            java.util.Arrays.sort(sorted);
            if(frameCount>0){
                root.addProperty("frameMeanMs",sum/frameCount);
                root.addProperty("frameP95Ms",sorted[Math.min(frameCount-1,(int)Math.ceil(frameCount*.95)-1)]);
                root.addProperty("frameMaxMs",sorted[frameCount-1]);
            }
            root.add("frameIntervalsMs",frames);
            JsonArray screenTypes=new JsonArray();for(String screen:sampledScreens)screenTypes.add(screen);root.add("sampledScreens",screenTypes);
            root.addProperty("screenAtCapture",c.gui.screen()==null?"world":c.gui.screen().getClass().getSimpleName());
            if(lastFrameSampleMillis>0)root.addProperty("frameWindowEndUtc",java.time.Instant.ofEpochMilli(lastFrameSampleMillis).toString());
            if(cpuSampleMillis>0)root.addProperty("cpuSampleUtc",java.time.Instant.ofEpochMilli(cpuSampleMillis).toString());
            var rt=Runtime.getRuntime();root.addProperty("jvmUsedBytes",rt.totalMemory()-rt.freeMemory());
            if(cpuLoad>=0)root.addProperty("processCpuFraction",cpuLoad);
            root.addProperty("gpuTiming","unavailable");
            root.addProperty("errorSampleScope",errorScope);JsonArray warnings=new JsonArray();
            for(String line:recentErrors)warnings.add(line);root.add("sampledWarningsAndErrors",warnings);
            JsonObject modules=new JsonObject();for(String module:MODULES)modules.addProperty(module,enabled(module));root.add("modules",modules);
            root.add("settings",new GsonBuilder().create().toJsonTree(TestingAccess.settings()));
            JsonArray captures=new JsonArray();if(!lastScreenshot.isEmpty())captures.add(lastScreenshot);root.add("screenshots",captures);
            root.addProperty("screenshotStatus",screenshotStatus);

            String clean=(name==null?"report":name).replaceAll("[^a-zA-Z0-9_-]","_");
            if(clean.length()>48)clean=clean.substring(0,48);
            String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
            Path directory=reportsDirectory();Files.createDirectories(directory);
            Path target=directory.resolve(stamp+"-"+clean+".json"),tmp=Files.createTempFile(directory,"dw-report-",".tmp");
            try{
                Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(root),java.nio.charset.StandardCharsets.UTF_8);
                try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);}
                catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(tmp,target);}
            }finally{Files.deleteIfExists(tmp);}
            reportStatus="Отчёт: "+target.getFileName();TestingReports.manualAdded(c,target);
        }catch(Exception e){reportStatus="Ошибка отчёта: "+e.getMessage();}
    }
    public static List<String> compareReports(String before,String after) {return TestingReports.compare(before,after);}
    private static void drawWorldGizmos(Minecraft c) {
        if(!"EDIT".equals(value("mode")) && !"CHARACTER".equals(value("mode")))return;
        try(var collector=c.collectPerTickGizmos()) {
            for(var t:zones()){
                Gizmos.cuboid(new AABB(t.x0(),t.y0(),t.z0(),t.x1()+1,t.y1()+1,t.z1()+1),
                        GizmoStyle.stroke(0xFFE68186,1.5f)).setAlwaysOnTop();
            }
            if(first!=null && !(c.gui.screen() instanceof TestingEditorScreen panel && panel.previewScene())) {
                BlockPos b=second==null?first:second;
                Gizmos.cuboid(new AABB(Math.min(first.getX(),b.getX()),Math.min(first.getY(),b.getY()),Math.min(first.getZ(),b.getZ()),
                        Math.max(first.getX(),b.getX())+1,Math.max(first.getY(),b.getY())+1,Math.max(first.getZ(),b.getZ())+1),
                        GizmoStyle.stroke(0xFFB5DBE8,2f)).setAlwaysOnTop();
            }
            if(c.gui.screen() instanceof TestingEditorScreen panel && panel.previewScene()){
                String id=panel.selectedSceneId();
                for(var node:scene())if(node.id().equals(id)){
                    var a=node.min();var b=node.max();
                    Gizmos.cuboid(new AABB(a.getX(),a.getY(),a.getZ(),b.getX()+1,b.getY()+1,b.getZ()+1),
                            GizmoStyle.stroke(0xFF9DE3D3,2f)).setAlwaysOnTop();
                }
            }
            BlockPos pivot=selected;
            if(c.gui.screen() instanceof TestingEditorScreen panel&&panel.previewScene())pivot=panel.gizmoPosition();
            if(pivot!=null) {
                Vec3 p=Vec3.atCenterOf(pivot);
                Gizmos.cuboid(pivot,GizmoStyle.stroke(0xFFE8D49A,2f)).setAlwaysOnTop();
                Gizmos.arrow(p,p.add(3,0,0),0xFFE26464,2f).setAlwaysOnTop();
                Gizmos.arrow(p,p.add(0,3,0),0xFF7ACC91,2f).setAlwaysOnTop();
                Gizmos.arrow(p,p.add(0,0,3),0xFF6AA4E5,2f).setAlwaysOnTop();
            }
            if(c.gui.screen() instanceof TestingEditorScreen screen && screen.previewDevices()) {
                for(var t:triggers()) {
                    Gizmos.cuboid(new AABB(t.x0(),t.y0(),t.z0(),t.x1()+1,t.y1()+1,t.z1()+1),
                            GizmoStyle.strokeAndFill(0xFFB991E7,1.5f,0x12B991E7)).setAlwaysOnTop();
                    Gizmos.billboardTextOverBlock(t.name(),new BlockPos(t.x0(),t.y1()+1,t.z0()),0xFFDEC9F5,0x80101920,.7f).setAlwaysOnTop();
                }
            }
            if(c.gui.screen() instanceof TestingEditorScreen screen && screen.previewBrush() && selected!=null) {
                float radius;
                try { radius=Float.parseFloat(value("radius")); } catch(NumberFormatException e) { radius=6; }
                if("square".equals(value("brushShape"))){
                    Gizmos.cuboid(new AABB(selected.getX()-radius,selected.getY()+.55,selected.getZ()-radius,
                            selected.getX()+radius+1,selected.getY()+.65,selected.getZ()+radius+1),
                            GizmoStyle.stroke(0xFFE7C17C,2f)).setAlwaysOnTop();
                }else Gizmos.circle(Vec3.atCenterOf(selected).add(0,.1,0),radius,GizmoStyle.stroke(0xFFE7C17C,2f)).setAlwaysOnTop();
                for(BlockPos blocked:protectedPreview()){
                    if("square".equals(value("brushShape"))||Math.hypot(blocked.getX()-selected.getX(),blocked.getZ()-selected.getZ())<=radius)
                        Gizmos.cuboid(blocked,GizmoStyle.strokeAndFill(0xFFE36D77,1.2f,0x38E36D77)).setAlwaysOnTop();
                }
            }
        }
    }
    public static void render(GuiGraphicsExtractor g) {
        Minecraft c=Minecraft.getInstance();
        if(!active()){lastRender=0;return;}
        if(diagnostic)sampleDiagnostics(c);else lastRender=0;
        if(c.gui.screen()!=null)return;
        int panelY=enabled("hud")?44:8;
        int w=Math.min(198,Math.max(125,g.guiWidth()/3)),h=collapsed?30:Math.max(30,Math.min(g.guiHeight()-panelY-34,198));
        int x=g.guiWidth()-w-8;
        HudStyle.instrumentPanel(g,x,panelY,w,h);
        HudStyle.fit(g,Component.literal("DARKWINTER • ЛАБОРАТОРИЯ"),x+8,panelY+7,w-16,.72f,HudStyle.INK);
        if(collapsed) {
            HudStyle.fit(g,Component.literal("F7 • панель   F8 • инструменты"),x+8,panelY+19,w-16,.63f,HudStyle.MUTED);
        } else {
            String name=worldName();
            HudStyle.fit(g,Component.literal(name+"  /  "+value("mode")+(TestingDrone.active()?" / DRONE":"")),x+8,panelY+20,w-16,.68f,HudStyle.MUTED);
            int y=panelY+34;
            for(String module:MODULES) {
                if(y+10>panelY+h-28)break;
                HudStyle.fit(g,Component.literal((enabled(module)?"● ":"○ ")+label(module)),x+8,y,w-58,.66f,enabled(module)?HudStyle.STABLE:HudStyle.MUTED);
                HudStyle.fit(g,Component.literal(shortcut(module)),x+w-45,y,40,.59f,HudStyle.MUTED);
                y+=11;
            }
            HudStyle.fit(g,Component.literal("Num1–Num0 • быстрые системы"),x+8,panelY+h-26,w-16,.59f,HudStyle.MUTED);
            HudStyle.fit(g,Component.literal("F8 • настройки   F7 • свернуть"),x+8,panelY+h-13,w-16,.62f,HudStyle.INK);
        }
        if(diagnostic){int diagnosticWidth=Math.min(230,x-16);if(diagnosticWidth>=100)renderDiagnostics(g,x-diagnosticWidth-8,panelY,diagnosticWidth);}
    }
    private static void sampleDiagnostics(Minecraft client) {
        long now=System.nanoTime();
        if(lastRender!=0) {
            FRAME_MS[frameIndex]=(now-lastRender)/1_000_000f;
            FRAME_SCREEN[frameIndex]=client.gui.screen()==null?"world":client.gui.screen().getClass().getSimpleName();
            lastFrameSampleMillis=System.currentTimeMillis();
            frameIndex=(frameIndex+1)%FRAME_MS.length;frameCount=Math.min(frameCount+1,FRAME_MS.length);
        }
        lastRender=now;
        if(now-cpuMeasuredAt>1_000_000_000L) {
            var bean=ManagementFactory.getOperatingSystemMXBean();
            cpuLoad=bean instanceof com.sun.management.OperatingSystemMXBean os?os.getProcessCpuLoad():-1;
            cpuMeasuredAt=now;cpuSampleMillis=System.currentTimeMillis();
        }
    }
    public static int measuredFrames(){return frameCount;}
    private static void renderDiagnostics(GuiGraphicsExtractor g,int x,int y,int width) {
        HudStyle.instrumentPanel(g,x,y,width,102);
        var rt=Runtime.getRuntime();
        HudStyle.text(g,"FPS "+Minecraft.getInstance().getFps()+" • память "+(rt.totalMemory()-rt.freeMemory())/1048576+" MiB",x+8,y+8,.7f,HudStyle.INK);
        float sum=0,max=0;
        for(int i=0;i<frameCount;i++){float ms=FRAME_MS[(frameIndex-frameCount+i+FRAME_MS.length)%FRAME_MS.length];sum+=ms;max=Math.max(max,ms);}
        HudStyle.text(g,String.format(java.util.Locale.ROOT,"Кадр: средн. %.1f / max %.1f мс",frameCount==0?0:sum/frameCount,max),x+8,y+20,.65f,HudStyle.MUTED);
        HudStyle.text(g,"CPU процесса: "+(cpuLoad<0?"недоступно":Math.round(cpuLoad*100)+"%")+" • GPU: недоступно",x+8,y+31,.62f,HudStyle.MUTED);
        int gw=width-16;
        for(int i=0;i<Math.min(frameCount,gw);i++) {
            float ms=FRAME_MS[(frameIndex-1-i+FRAME_MS.length)%FRAME_MS.length];
            int gh=Math.min(40,Math.round(ms*.7f));
            g.fill(x+width-9-i,y+91-gh,x+width-8-i,y+91,ms>33?HudStyle.AMBER:HudStyle.STABLE);
        }
        g.horizontalLine(x+8,x+width-8,y+92,HudStyle.LINE);
    }
}
