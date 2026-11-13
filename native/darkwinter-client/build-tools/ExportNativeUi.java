// Build-only conversion through the same bounded SVG parser used by the mod.
package com.dark.winter.client.vector;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
public final class ExportNativeUi {
 static double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
 static double segment(double x,double y,SvgGeometry.Segment s){double dx=s.bx()-s.ax(),dy=s.by()-s.ay(),t=clamp(((x-s.ax())*dx+(y-s.ay())*dy)/Math.max(1e-20,dx*dx+dy*dy),0,1);return Math.hypot(x-s.ax()-t*dx,y-s.ay()-t*dy);}
 static double distance(double x,double y,SvgGeometry.Asset a,SvgGeometry.Shape s){var c=a.segments().get(s.first());int mode=s.mode();double r=Math.hypot(x-c.ax(),y-c.ay());
  if(mode==4)return r-c.bx();if(mode==5)return Math.abs(r-c.bx())-s.radius();
  if(mode==6){double span=a.segments().get(s.first()+1).ax(),angle=((Math.atan2(y-c.ay(),x-c.ax())-c.by())*Math.signum(span)+Math.PI*4)%(Math.PI*2);double d=angle<=Math.abs(span)?Math.abs(r-c.bx()):Math.min(Math.hypot(x-c.ax()-c.bx()*Math.cos(c.by()),y-c.ay()-c.bx()*Math.sin(c.by())),Math.hypot(x-c.ax()-c.bx()*Math.cos(c.by()+span),y-c.ay()-c.bx()*Math.sin(c.by()+span)));return d-s.radius();}
  double nearest=1e20;int winding=0;for(int k=0;k<s.count();k++){var p=a.segments().get(s.first()+k);double d=segment(x,y,p);if(mode==3){double dx=p.bx()-p.ax(),dy=p.by()-p.ay(),len=Math.hypot(dx,dy),lx=(x-(p.ax()+p.bx())*.5)*dx/Math.max(len,1e-12)+(y-(p.ay()+p.by())*.5)*dy/Math.max(len,1e-12),ly=-(x-(p.ax()+p.bx())*.5)*dy/Math.max(len,1e-12)+(y-(p.ay()+p.by())*.5)*dx/Math.max(len,1e-12),qx=Math.abs(lx)-len*.5,qy=Math.abs(ly)-s.radius();d=Math.hypot(Math.max(qx,0),Math.max(qy,0))+Math.min(Math.max(qx,qy),0);}nearest=Math.min(nearest,d);double cross=(p.bx()-p.ax())*(y-p.ay())-(p.by()-p.ay())*(x-p.ax());if(p.ay()<=y&&p.by()>y&&cross>0)winding++;if(p.ay()>y&&p.by()<=y&&cross<0)winding--;}
  if(mode==2)return nearest-s.radius();if(mode==3)return nearest;boolean inside=mode==1?Math.abs(winding)%2==1:winding!=0;return inside?-nearest:nearest;
 }
 static void export(Path source,Path out)throws Exception{SvgGeometry.Asset a;try(var in=Files.newInputStream(source)){a=SvgGeometry.read(in);}int size=128;var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);double pixel=Math.max(a.width(),a.height())/size;
  for(int y=0;y<size;y++)for(int x=0;x<size;x++){double alpha=0;for(var shape:a.shapes()){double coverage=0;for(int sy=0;sy<2;sy++)for(int sx=0;sx<2;sx++){double px=a.minX()+(x+(sx+.5)/2)*a.width()/size,py=a.minY()+(y+(sy+.5)/2)*a.height()/size;coverage+=clamp(.5-distance(px,py,a,shape)/(pixel*.5),0,1)*.25;}double sa=coverage*(shape.argb()>>>24)/255.;alpha=sa+alpha*(1-sa);}image.setRGB(x,y,((int)Math.round(alpha*255)<<24)|0xffffff);}
  Files.createDirectories(out.getParent());ImageIO.write(image,"png",out.toFile());
 }

 // Data only: UI translations and language metadata from the same build classpath.
 // Language extraction is resource-only; game-rule export initializes registries, never a client, server or world.
 private static final String[] UI_PREFIXES={
  "options.","controls.","key.","soundCategory.","gui.","menu.","selectWorld.","createWorld.",
  "worldOptions.","generator.","gameMode.","gamerule.","editGamerule.","datapack.","dataPack.",
  "narrator.","narration.","accessibility.","language.","chat_screen.",
  "pack.","resourcePack.","resourcepack.","onboarding.","title.","multiplayer.","addServer.",
  "connect.","disconnect.","loading.","screen.","tooltip.","hud.","inventory.darkwinter.",
  "darkwinter.","darkwinter_visuals.","darkwinter_motion.",
  "credits_and_attribution.","chat.","stat.","stat_type.","biome.","flat_world_preset.","selectServer."
 };
 // Flat-layer captions are a closed whitelist; do not export the block/item catalog.
 private static final Set<String> UI_EXACT_KEYS=Set.of(
  "block.minecraft.bedrock",
  "block.minecraft.dirt",
  "block.minecraft.grass_block",
  "block.minecraft.stone",
  "block.minecraft.deepslate",
  "block.minecraft.gravel",
  "block.minecraft.snow",
  "block.minecraft.cobblestone",
  "block.minecraft.sandstone",
  "block.minecraft.sand",
  "block.minecraft.air",
  "item.minecraft.water_bucket",
  "item.minecraft.lava_bucket"
 );
 private record ResourceJson(JsonObject object,String sha256) {}
 private static Path assetDirectory;
 private static JsonObject assetObjects;
 private static final Map<String,Object> cachedAssetSources=new TreeMap<>();
 private static void loadAssetIndex(Path out)throws Exception {
  Path project=out.toAbsolutePath().normalize().getParent().getParent().getParent();
  var lines=Files.readAllLines(project.resolve(".gradle/loom-cache/launch.cfg"),StandardCharsets.UTF_8).stream().map(String::trim).toList();
  int directoryAt=lines.indexOf("--assetsDir"),indexAt=lines.indexOf("--assetIndex");
  if(directoryAt<0||indexAt<0||directoryAt+1>=lines.size()||indexAt+1>=lines.size())
   throw new IllegalArgumentException("Loom launch.cfg does not identify cached assets");
  assetDirectory=Path.of(lines.get(directoryAt+1));String indexName=lines.get(indexAt+1);
  if(!indexName.matches("[A-Za-z0-9_.-]{1,128}"))throw new IllegalArgumentException("Invalid Loom asset index name");
  Path index=assetDirectory.resolve("indexes").resolve(indexName+".json");
  if(Files.size(index)>64*1024*1024)throw new IllegalArgumentException("Loom asset index exceeds 64 MiB");
  var json=JsonParser.parseString(Files.readString(index,StandardCharsets.UTF_8));
  if(!json.isJsonObject()||!json.getAsJsonObject().has("objects")||!json.getAsJsonObject().get("objects").isJsonObject())
   throw new IllegalArgumentException("Invalid Loom asset index objects");
  assetObjects=json.getAsJsonObject().getAsJsonObject("objects");
 }
 private static byte[] readCachedAsset(String name)throws Exception {
  var entry=assetObjects.get(name);if(entry==null)return null;
  if(!entry.isJsonObject())throw new IllegalArgumentException("Invalid cached asset entry: "+name);
  var item=entry.getAsJsonObject();String hash=requiredString(item,"hash");
  if(!hash.matches("[0-9a-f]{40}")||!item.has("size"))throw new IllegalArgumentException("Invalid cached asset hash: "+name);
  long size=item.get("size").getAsLong();
  if(size<1||size>16*1024*1024)throw new IllegalArgumentException("Invalid cached asset size: "+name);
  Path file=assetDirectory.resolve("objects").resolve(hash.substring(0,2)).resolve(hash);
  byte[] bytes;try(var in=Files.newInputStream(file)){bytes=in.readNBytes(16*1024*1024+1);}
  if(bytes.length!=size||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)).equals(hash))
   throw new IllegalArgumentException("Cached asset integrity mismatch: "+name);
  String sha256=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  cachedAssetSources.put(name,Map.of("sha1",hash,"sha256",sha256,"bytes",bytes.length));
  return bytes;
 }
 private static List<ResourceJson> languageMetadataResources()throws Exception {
  var sources=readResources("pack.mcmeta");
  int candidates=0;
  for(String name:new TreeSet<>(assetObjects.keySet()))if(name.equals("pack.mcmeta")||name.endsWith("/pack.mcmeta")) {
   if(++candidates>64)throw new IllegalArgumentException("Too many asset pack metadata candidates");
   byte[] bytes=readCachedAsset(name);
   var json=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));
   if(!json.isJsonObject())throw new IllegalArgumentException("Invalid cached pack metadata: "+name);
   sources.add(new ResourceJson(json.getAsJsonObject(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
  }
  return sources;
 }
 private static List<ResourceJson> readResources(String name)throws Exception {
  var result=new ArrayList<ResourceJson>();
  var urls=ExportNativeUi.class.getClassLoader().getResources(name);
  while(urls.hasMoreElements()) {
   var url=urls.nextElement();byte[] bytes;
   try(var in=url.openStream()){bytes=in.readNBytes(16*1024*1024+1);}
   if(bytes.length>16*1024*1024)throw new IllegalArgumentException("UI resource too large: "+name);
   var json=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));
   if(!json.isJsonObject())throw new IllegalArgumentException("UI resource is not an object: "+name);
   result.add(new ResourceJson(json.getAsJsonObject(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
  }
  return result;
 }
 private static String requiredString(JsonObject object,String field) {
  var value=object.get(field);
  if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())
   throw new IllegalArgumentException("Missing UI string: "+field);
  String text=value.getAsString();
  if(text.isBlank()||text.indexOf(0)>=0||text.getBytes(StandardCharsets.UTF_8).length>65536)
   throw new IllegalArgumentException("Invalid UI string: "+field);
  return text;
 }
 private static boolean uiKey(String key) {
  for(String prefix:UI_PREFIXES)if(key.startsWith(prefix))return true;
  return UI_EXACT_KEYS.contains(key);
 }
 private static void writeChanged(Path target,String text)throws Exception {
  if(Files.isRegularFile(target)&&Files.readString(target,StandardCharsets.UTF_8).equals(text))return;
  Files.createDirectories(target.getParent());
  Path tmp=Files.createTempFile(target.getParent(),"ui-source-",".tmp");
  try {
   Files.writeString(tmp,text,StandardCharsets.UTF_8);
   try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
   catch(AtomicMoveNotSupportedException ignored){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}
  } finally {Files.deleteIfExists(tmp);}
 }
 private static Map<String,Object> copyNativePng(String resource,Path target)throws Exception {
  byte[] chosen=null;String selection="Native Minecraft JAR resource; original PNG bytes";
  var urls=ExportNativeUi.class.getClassLoader().getResources(resource);
  while(urls.hasMoreElements()) {
   var url=urls.nextElement();
   var connection=url.openConnection();connection.setUseCaches(false);
   if(!(connection instanceof java.net.JarURLConnection jarConnection))continue;
   boolean minecraft;
   try(var jar=jarConnection.getJarFile()) {
    minecraft=jar.getJarEntry("assets/minecraft/lang/en_us.json")!=null
        ||jar.getJarEntry("net/minecraft/client/Minecraft.class")!=null;
   }
   if(!minecraft)continue;
   byte[] bytes;var input=url.openConnection();input.setUseCaches(false);
   try(var in=input.getInputStream()){bytes=in.readNBytes(4*1024*1024+1);}
   if(bytes.length<24||bytes.length>4*1024*1024
       ||!Arrays.equals(Arrays.copyOfRange(bytes,0,8),new byte[]{(byte)137,80,78,71,13,10,26,10})
       ||!Arrays.equals(Arrays.copyOfRange(bytes,12,16),new byte[]{73,72,68,82}))
    throw new IllegalArgumentException("Invalid native pack PNG: "+resource);
   int width=java.nio.ByteBuffer.wrap(bytes,16,4).getInt(),height=java.nio.ByteBuffer.wrap(bytes,20,4).getInt();
   if(width<1||width>4096||height<1||height>4096)
    throw new IllegalArgumentException("Native pack PNG dimensions: "+resource);
   if(chosen!=null&&!Arrays.equals(chosen,bytes))
    throw new IllegalArgumentException("Conflicting native pack PNG: "+resource);
   chosen=bytes;
  }
  if(chosen==null) {
   String name=resource.startsWith("assets/")?resource.substring(7):resource;
   chosen=readCachedAsset(name);selection="Verified Loom asset-index object "+name+"; original PNG bytes";
  }
  if(chosen==null)throw new IllegalArgumentException("Native pack PNG missing on classpath and asset index: "+resource);
  if(chosen.length<24||chosen.length>4*1024*1024
      ||!Arrays.equals(Arrays.copyOfRange(chosen,0,8),new byte[]{(byte)137,80,78,71,13,10,26,10})
      ||!Arrays.equals(Arrays.copyOfRange(chosen,12,16),new byte[]{73,72,68,82}))
   throw new IllegalArgumentException("Invalid native pack PNG: "+resource);
  int pngWidth=java.nio.ByteBuffer.wrap(chosen,16,4).getInt(),pngHeight=java.nio.ByteBuffer.wrap(chosen,20,4).getInt();
  if(pngWidth<1||pngWidth>4096||pngHeight<1||pngHeight>4096)
   throw new IllegalArgumentException("Native pack PNG dimensions: "+resource);
  Files.createDirectories(target.getParent());
  if(!Files.isRegularFile(target)||!Arrays.equals(Files.readAllBytes(target),chosen)) {
   Path tmp=Files.createTempFile(target.getParent(),"ui-pack-",".tmp");
   try {
    Files.write(tmp,chosen);
    try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    catch(AtomicMoveNotSupportedException ignored){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}
   } finally {Files.deleteIfExists(tmp);}
  }
  return Map.of("resource",resource,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chosen)),
      "bytes",chosen.length,"width",java.nio.ByteBuffer.wrap(chosen,16,4).getInt(),
      "height",java.nio.ByteBuffer.wrap(chosen,20,4).getInt(),"selection",selection);
 }
 private static Map<String,Object> exportPackArt(Path out)throws Exception {
  var result=new TreeMap<String,Object>();
  result.put("unknown_pack.png",copyNativePng("assets/minecraft/textures/misc/unknown_pack.png",out.resolve("packs/unknown_pack.png")));
  result.put("vanilla_pack.png",copyNativePng("pack.png",out.resolve("packs/vanilla_pack.png")));
  return result;
 }
 private static List<Map<String,Object>> gameRuleRows(Map<String,String> english) {
  net.minecraft.SharedConstants.tryDetectVersion();
  net.minecraft.server.Bootstrap.bootStrap();
  var rules=new net.minecraft.world.level.gamerules.GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
  var result=new TreeMap<String,Map<String,Object>>();
  for(var rule:rules.availableRules().toList()) {
   String type=rule.gameRuleType().getSerializedName();int minimum,maximum;
   if(type.equals("boolean")&&rule.defaultValue() instanceof Boolean) {minimum=0;maximum=1;}
   else if(type.equals("integer")&&rule.defaultValue() instanceof Integer
           &&rule.argument() instanceof com.mojang.brigadier.arguments.IntegerArgumentType argument) {
    minimum=argument.getMinimum();maximum=argument.getMaximum();
   } else throw new IllegalArgumentException("Unsupported native game-rule UI type: "+rule.id());
   String label=rule.getDescriptionId(),description=label+".description";
   if(!english.containsKey(label))throw new IllegalArgumentException("Missing native game-rule label: "+label);
   var row=new TreeMap<String,Object>();
   row.put("id",rule.id());row.put("identifier",rule.getIdentifier().toString());
   row.put("category",rule.category().id().getPath());row.put("type",type);
   row.put("default",rule.defaultValue().toString());row.put("min",minimum);row.put("max",maximum);
   row.put("labelKey",label);row.put("descriptionKey",english.containsKey(description)?description:"");
   if(result.putIfAbsent(rule.id(),row)!=null)throw new IllegalArgumentException("Duplicate native game-rule ID");
  }
  if(result.isEmpty()||result.size()>512)throw new IllegalArgumentException("Native game-rule catalog is incomplete");
  return new ArrayList<>(result.values());
 }
 private static int exportLanguages(Path out)throws Exception {
  var languages=new TreeMap<String,Map<String,Object>>();
  var metadataHashes=new TreeSet<String>();
  for(var source:languageMetadataResources()) {
   var language=source.object().get("language");
   if(language==null)continue;
   if(!language.isJsonObject())throw new IllegalArgumentException("Invalid pack language metadata");
   metadataHashes.add(source.sha256());
   for(var entry:language.getAsJsonObject().entrySet()) {
    String code=entry.getKey();
    if(!code.matches("[a-z0-9_]{2,32}")||!entry.getValue().isJsonObject())
     throw new IllegalArgumentException("Invalid language metadata code: "+code);
    var info=entry.getValue().getAsJsonObject();
    var row=new TreeMap<String,Object>();
    row.put("code",code);row.put("name",requiredString(info,"name"));row.put("region",requiredString(info,"region"));
    boolean bidi=info.has("bidirectional")&&info.get("bidirectional").getAsBoolean();row.put("bidirectional",bidi);
    var previous=languages.putIfAbsent(code,row);
    if(previous!=null&&!previous.equals(row))throw new IllegalArgumentException("Conflicting classpath language metadata: "+code);
   }
  }
  if(languages.size()>512||!languages.containsKey("en_us")||!languages.containsKey("ru_ru"))
   throw new IllegalArgumentException("Complete language metadata was not found in classpath/cached pack.mcmeta; languages="+languages.size()+", cached metadata="+cachedAssetSources.keySet());
  var deprecated=net.minecraft.locale.DeprecatedTranslationsInfo.loadFromDefaultResource();
  var removed=deprecated.removed().stream().filter(ExportNativeUi::uiKey).toList();
  var renamed=new LinkedHashMap<String,String>();
  deprecated.renamed().forEach((from,to)->{if(uiKey(from)||uiKey(to))renamed.put(from,to);});
  var uiDeprecated=new net.minecraft.locale.DeprecatedTranslationsInfo(removed,renamed);
  var english=new TreeMap<String,String>();var englishHashes=new TreeSet<String>();
  for(var source:readResources("assets/minecraft/lang/en_us.json")) {
   englishHashes.add(source.sha256());
   var nativeStrings=new TreeMap<String,String>();
   try(var stream=new java.io.ByteArrayInputStream(source.object().toString().getBytes(StandardCharsets.UTF_8))) {
    net.minecraft.locale.Language.loadFromJson(stream,nativeStrings::put);
   }
   for(var entry:nativeStrings.entrySet())if(uiKey(entry.getKey())||renamed.containsKey(entry.getKey())) {
    String value=entry.getValue();
    var previous=english.putIfAbsent(entry.getKey(),value);
    if(previous!=null&&!previous.equals(value))throw new IllegalArgumentException("Conflicting native English UI translation: "+entry.getKey());
   }
  }
  if(!english.containsKey("options.language.title")||!english.containsKey("options.font.title"))
   throw new IllegalArgumentException("Native English UI translations were not found");
  var rawEnglish=new TreeMap<>(english);uiDeprecated.applyToMap(english);
  english.entrySet().removeIf(entry->!uiKey(entry.getKey()));
  for(String key:UI_EXACT_KEYS)if(!english.containsKey(key))
   throw new IllegalArgumentException("Missing native flat-layer caption: "+key);
  var migrationRows=new ArrayList<List<String>>();
  renamed.forEach((from,to)->migrationRows.add(List.of(from,to)));
  var deprecatedHashes=new TreeSet<String>();
  for(var source:readResources("assets/minecraft/lang/deprecated.json"))deprecatedHashes.add(source.sha256());
  if(deprecatedHashes.isEmpty())throw new IllegalArgumentException("Native translation migration metadata is missing");
  var result=new TreeMap<String,Object>();
  result.put("translationMigrations",Map.of("removed",removed,"renamed",migrationRows));
  result.put("englishRaw",rawEnglish);
  result.put("schema",1);result.put("uiPrefixes",UI_PREFIXES);result.put("languages",new ArrayList<>(languages.values()));
  result.put("uiExactKeys",new TreeSet<>(UI_EXACT_KEYS));
  result.put("english",english);result.put("gameRules",gameRuleRows(english));
  result.put("gameRuleScope","Vanilla registry metadata with default feature flags; no client or world startup");
  result.put("packArtSources",exportPackArt(out));
  result.put("cachedAssetSources",new TreeMap<>(cachedAssetSources));
  result.put("sourceSha256",new TreeMap<>(Map.of("pack.mcmeta",metadataHashes,"assets/minecraft/lang/en_us.json",englishHashes,"assets/minecraft/lang/deprecated.json",deprecatedHashes)));
  writeChanged(out.resolve("ui-language-source.json"),new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result)+"\n");
  return languages.size();
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("Expected DarkWinter asset root and output directory");
  Path root=Path.of(args[0]),out=Path.of(args[1]);
  loadAssetIndex(out);
  int languages=exportLanguages(out);
  for(var glyph:MenuGlyph.values())export(root.resolve("svg/menu/"+glyph.resource+".svg"),out.resolve("menu/"+glyph.resource+".png"));
  String[] hud={"health","stamina","satiety","temperature","hydration","rest","wind-needle","settings","exit","signal","compass-face"};
  for(var name:hud)export(root.resolve("svg/hud/"+name+".svg"),out.resolve("hud/"+name+".png"));
  System.out.println("CORE_NATIVE_UI "+(MenuGlyph.values().length+hud.length)+" source SVG assets; "+languages+" languages");
 }
}
