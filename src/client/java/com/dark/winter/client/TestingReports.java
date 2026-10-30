// Unified read-only index for measured manual client reports and automatic native scenario reports.
package com.dark.winter.client;

import com.dark.winter.testing.TestingArchive;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

/** Unified read-only index for measured manual client reports and automatic native scenario reports. */
public final class TestingReports {
    private record Entry(String reference,Path file,Path root,long modified) {}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"DarkWinter Report Index");t.setDaemon(true);return t;});
    private static volatile Map<String,Entry> entries=Map.of();
    private static volatile String status="Отчёты ещё не прочитаны";
    private static volatile long revision;
    private static boolean refreshing;
    private static long requested,lastScan;
    private static Path observed;
    private TestingReports(){}
    public static String status(){return status;}
    public static long revision(){return revision;}
    public static List<String> list(Minecraft client){
        refresh(client,false);return List.copyOf(entries.keySet());
    }
    public static void refresh(Minecraft client,boolean force){
        Path current=client.getSingleplayerServer()==null?null:client.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        long now=System.nanoTime();
        if(!force&&(refreshing||Objects.equals(current,observed)&&now-lastScan<2_000_000_000L))return;
        long generation=++requested;refreshing=true;lastScan=now;observed=current;
        Path game=client.gameDirectory.toPath().toAbsolutePath().normalize();
        IO.execute(()->{
            try{
                Map<String,Entry> found=new LinkedHashMap<>();
                collect(game.resolve("darkwinter-testing/client-reports"),"",found,1);
                if(current!=null)collect(current.resolve("data/darkwinter-testing/reports"),"current:",found,3);
                Path sessions=TestingArchive.root().resolve("session-journals");
                if(Files.isDirectory(sessions,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(sessions))try(var folders=Files.list(sessions)){
                    for(Path folder:folders.filter(path->Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path))
                            .sorted(Comparator.comparingLong(TestingReports::modified).reversed()).limit(64).toList())
                        collect(folder.resolve("reports"),"session:"+folder.getFileName()+":",found,3);
                }
                var sorted=found.values().stream().sorted(Comparator.comparingLong(Entry::modified).reversed()).limit(128).toList();
                LinkedHashMap<String,Entry> next=new LinkedHashMap<>();for(Entry entry:sorted)next.put(entry.reference(),entry);
                client.execute(()->{if(generation==requested){entries=Collections.unmodifiableMap(next);refreshing=false;status="Доступны "+next.size()+" последних клиентских и сценарных отчётов";revision++;}});
            }catch(Exception failure){client.execute(()->{if(generation==requested){refreshing=false;status="Индекс отчётов недоступен: "+failure.getMessage();revision++;}});}
        });
    }
    private static long modified(Path path){try{return Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis();}catch(IOException ignored){return 0;}}
    private static void collect(Path root,String prefix,Map<String,Entry> into,int depth)throws IOException{
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root))return;
        try(var files=Files.walk(root,depth)){
            for(Path file:files.filter(path->path.getFileName().toString().endsWith(".json")).limit(512).toList())if(valid(file,root)){
                String relative=root.relativize(file).toString().replace('\\','/');
                into.put(prefix+relative,new Entry(prefix+relative,file,root,modified(file)));
            }
        }
    }
    private static boolean valid(Path file,Path root)throws IOException{
        Path candidate=file.toAbsolutePath().normalize(),base=root.toAbsolutePath().normalize();
        if(!candidate.startsWith(base)||Files.isSymbolicLink(base))return false;
        Path checked=base;for(Path part:base.relativize(candidate)){checked=checked.resolve(part);if(Files.isSymbolicLink(checked))return false;}
        return Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)&&Files.size(candidate)<=1024*1024;
    }
    public static void manualAdded(Minecraft client,Path file){
        Path root=file.getParent();Map<String,Entry> next=new LinkedHashMap<>();String reference=file.getFileName().toString();
        next.put(reference,new Entry(reference,file,root,modified(file)));next.putAll(entries);entries=Collections.unmodifiableMap(next);revision++;
        refresh(client,true);
    }
    public static JsonObject read(String reference)throws IOException{
        Entry entry=entries.get(reference);
        if(entry==null){
            if(reference==null||reference.isBlank()||reference.contains("/")||reference.indexOf('\\')>=0||reference.contains("..")||reference.contains(":"))
                throw new IOException("Выберите доступный отчёт из списка");
            Path root=Minecraft.getInstance().gameDirectory.toPath().resolve("darkwinter-testing/client-reports");
            entry=new Entry(reference,root.resolve(reference),root,0);
        }
        if(!valid(entry.file(),entry.root()))throw new IOException("Отчёт отсутствует, повреждён или слишком велик");
        try{
            JsonObject root=JsonParser.parseString(Files.readString(entry.file())).getAsJsonObject();
            int schema=root.get("schema").getAsInt();if(schema!=2&&schema!=3)throw new IOException("Неподдерживаемый формат отчёта");
            return root;
        }catch(RuntimeException error){throw new IOException("Некорректный отчёт",error);}
    }
    private static Double number(JsonObject report,String... keys){
        for(String key:keys)if(report.has(key))try{double value=report.get(key).getAsDouble();if(Double.isFinite(value))return value;}catch(RuntimeException ignored){}
        return null;
    }
    private static void metric(List<String> lines,String label,JsonObject a,JsonObject b,double divisor,String... keys){
        Double first=number(a,keys),second=number(b,keys);
        if(first==null&&second==null)return;
        lines.add(label+" A/B: "+(first==null?"недоступно":String.format(Locale.ROOT,"%.2f",first/divisor))+" / "+
                (second==null?"недоступно":String.format(Locale.ROOT,"%.2f",second/divisor)));
    }
    private static JsonElement modules(JsonObject report){
        if(report.has("settings")&&report.get("settings").isJsonObject())return report.getAsJsonObject("settings").get("modules");
        return report.get("modules");
    }
    public static List<String> compare(String first,String second){
        try{
            JsonObject a=read(first),b=read(second);List<String> lines=new ArrayList<>();
            lines.add("A: "+(a.has("name")?a.get("name").getAsString():first));lines.add("B: "+(b.has("name")?b.get("name").getAsString():second));
            metric(lines,"FPS",a,b,1,"fps");metric(lines,"Кадр mean, мс",a,b,1,"frameMeanMs");metric(lines,"Кадр p95, мс",a,b,1,"frameP95Ms");
            metric(lines,"Память JVM, MiB",a,b,1048576,"jvmUsedBytes","heapUsedBytes");
            metric(lines,"CPU процесса, %",a,b,.01,"processCpuFraction");metric(lines,"Загруженные чанки",a,b,1,"loadedChunks");
            metric(lines,"Здоровье",a,b,1,"health");metric(lines,"Голод",a,b,1,"food");
            if(!Objects.equals(modules(a),modules(b)))lines.add("Условия отличаются: состояния модулей.");
            if(a.has("settings")&&b.has("settings")&&!a.get("settings").equals(b.get("settings")))lines.add("Параметры и/или режимы тестов отличаются.");
            if(a.has("guiWidth")&&b.has("guiWidth")&&(!a.get("guiWidth").equals(b.get("guiWidth"))||!a.get("guiHeight").equals(b.get("guiHeight"))))
                lines.add("Размеры GUI различаются.");
            lines.add("GPU timing недоступен. Сравниваются только записанные измерения.");
            for(JsonObject report:List.of(a,b))if(report.has("screenshots"))for(JsonElement file:report.getAsJsonArray("screenshots"))lines.add("Скриншот: "+file.getAsString());
            return List.copyOf(lines);
        }catch(Exception failure){return List.of("Сравнение недоступно: "+failure.getMessage());}
    }
}
