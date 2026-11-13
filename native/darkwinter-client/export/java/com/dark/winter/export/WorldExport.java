// Build-time bridge only. The standalone game never loads this class or a JVM.
package com.dark.winter.export;
import com.dark.winter.loading.WorldPreloadServer;
import com.dark.winter.client.TestingWorlds;
import com.google.gson.*;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.*;import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.gamerules.GameRules;
import org.lwjgl.sdl.SDLVideo;
public final class WorldExport implements ClientModInitializer,FabricClientGameTest {
 static final ResourceKey<Biome> TEST=ResourceKey.create(Registries.BIOME,Identifier.fromNamespaceAndPath("darkwinter","darkwinter_test"));
 static final int MIN=-2,MAX=3,Y0=48,Y1=160;
 static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
 @Override public void onInitializeClient(){
  ClientLifecycleEvents.CLIENT_STARTED.register(c->SDLVideo.SDL_HideWindow(c.getWindow().handle()));
  ServerPlayConnectionEvents.JOIN.register((h,s,server)->WorldPreloadServer.cancel(server));
 }
 static <T extends Comparable<T>> String value(Property<T> p,Comparable<?> v){return p.getName((T)v);}
 static String name(BlockState s){
  String id=BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();if(s.getProperties().isEmpty())return id;
  var p=new TreeMap<String,String>();for(var k:s.getProperties())p.put(k.getName(),value(k,s.getValue(k)));
  return id+"["+String.join(",",p.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList())+"]";
 }
 static void le(OutputStream out,int n)throws IOException {out.write(n);out.write(n>>>8);out.write(n>>>16);out.write(n>>>24);}
 static void longle(OutputStream out,long n)throws IOException {le(out,(int)n);le(out,(int)(n>>>32));}
 static long fnv(byte[] b){long h=0xcbf29ce484222325L;for(byte v:b)h=(h^(v&255))*0x100000001b3L;return h;}
 @Override public void runTest(ClientGameTestContext context){
  Path out=Path.of(System.getProperty("darkwinter.worldExport")).toAbsolutePath();
  try{
   Files.createDirectories(out);context.runOnClient(c->{c.options.renderDistance().set(8);c.options.simulationDistance().set(4);c.options.pauseOnLostFocus=false;});
   TestingWorlds.arm(TestingWorlds.Template.NORMAL);
   try(var world=context.worldBuilder().adjustSettings(ui->{
    var a=ui.getSettings().worldgenLoadContext();
    ui.setWorldType(new WorldCreationUiState.WorldTypeEntry(a.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.SINGLE_BIOME_SURFACE)));
    ui.setSeed("19021");ui.getGameRules().set(GameRules.RANDOM_TICK_SPEED,0,null);
    ui.updateDimensions((r,d)->d.replaceOverworldGenerator(r,new NoiseBasedChunkGenerator(new FixedBiomeSource(r.lookupOrThrow(Registries.BIOME).getOrThrow(TEST)),r.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD))));
   }).create()){
    context.waitFor(c->c.player!=null&&c.level!=null,1200);
    world.getServer().runOnServer(server->{
     var l=server.overworld();require(l.getSeed()==19021,"Export seed");
     require(l.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().allMatch(b->b.is(TEST)),"Actual DarkWinter_Test biome");
     for(int z=MIN-2;z<=MAX+2;z++)for(int x=MIN-2;x<=MAX+2;x++)l.getChunk(x,z);
     var player=server.getPlayerList().getPlayers().getFirst();player.teleportTo(16.5,100,16.5);
    });
    context.waitFor(c->c.level!=null&&c.level.hasChunk(MIN,MIN)&&c.level.hasChunk(MAX,MAX),1200);
    context.waitTicks(80);
    context.runOnClient(c->{
     try{
      var materials=new LinkedHashMap<BlockState,Integer>();materials.put(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),0);
      var sections=new ArrayList<int[]>();var keys=new ArrayList<int[]>();
      var collisionShapes=new ArrayList<Map<String,Object>>();var collisionCache=new HashMap<Object,Integer>();var collisionDefaults=new HashMap<Integer,Integer>();var collisionOverrides=new ArrayList<int[]>();
      var shapes=new ArrayList<Map<String,Object>>();var cache=new HashMap<Object,Integer>();var overrides=new ArrayList<int[]>();
      var textures=new LinkedHashMap<ExportAtlas.Sprite,Integer>();var modelSet=c.getModelManager().getBlockStateModelSet();var random=RandomSource.create(42);
      for(int sy=Y0/16;sy<Y1/16;sy++)for(int sz=MIN;sz<=MAX;sz++)for(int sx=MIN;sx<=MAX;sx++){
       int[] cells=new int[4096];
       for(int i=0;i<4096;i++){
        BlockPos pos=new BlockPos(sx*16+(i&15),sy*16+(i>>>8),sz*16+((i>>>4)&15));var state=c.level.getBlockState(pos);
        int mid=materials.computeIfAbsent(state,k->materials.size());cells[i]=mid;
        var collision=state.getCollisionShape(c.level,pos).toAabbs().stream().map(b->List.of(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ)).toList();
        Object collisionKey=List.of(mid,collision);Integer cid=collisionCache.get(collisionKey);
        if(cid==null){cid=collisionShapes.size();collisionShapes.add(Map.of("material",mid,"boxes",collision));collisionCache.put(collisionKey,cid);}
        collisionDefaults.putIfAbsent(mid,cid);if(!collisionDefaults.get(mid).equals(cid))collisionOverrides.add(new int[]{pos.getX(),pos.getY(),pos.getZ(),mid,cid});
        if(state.isAir())continue;
        var model=modelSet.get(state);random.setSeed(42);
        Object geometry=model.createGeometryKey(c.level,pos,state,random);var offset=state.getOffset(pos);Object key=Arrays.asList(state,geometry,offset.x,offset.y,offset.z);
        Integer sid=cache.get(key);
        if(sid==null){
         sid=shapes.size();var quads=new ArrayList<Map<String,Object>>();var mesh=Renderer.get().mutableMesh();var emitter=mesh.emitter();
         emitter.pushTransform(q->{
          float cu=0,cv=0;for(int v=0;v<4;v++){cu+=q.u(v);cv+=q.v(v);}var sprite=ExportAtlas.find(cu/4,cv/4);
          int tid=textures.computeIfAbsent(sprite,k->textures.size());var points=new ArrayList<float[]>();var uv=new ArrayList<float[]>();
          for(int v=0;v<4;v++){points.add(new float[]{(float)(q.x(v)+offset.x),(float)(q.y(v)+offset.y),(float)(q.z(v)+offset.z)});uv.add(new float[]{(q.u(v)-sprite.u0())/(sprite.u1()-sprite.u0()),(q.v(v)-sprite.v0())/(sprite.v1()-sprite.v0())});}
          int tint=q.color(0);int bi=q.tintIndex();if(bi>=0)tint=c.getBlockColors().getTintSource(state,bi).colorInWorld(state,c.level,pos)|0xff000000;
          int rgba=(tint&0xff00ff00)|((tint>>>16)&255)|((tint&255)<<16);
          quads.add(Map.of("p",points,"uv",uv,"texture",tid,"tint",Integer.toUnsignedLong(rgba),"cutout",true));return false;
         });
         try{random.setSeed(42);model.emitQuads(emitter,c.level,pos,state,random,side->false);}finally{emitter.popTransform();}
         shapes.add(Map.of("material",mid,"quads",quads));cache.put(key,sid);
        }
        overrides.add(new int[]{pos.getX(),pos.getY(),pos.getZ(),mid,sid});
       }
       keys.add(new int[]{sx,sy,sz});sections.add(cells);
      }
      var payload=new ByteArrayOutputStream();
      for(var e:materials.entrySet()){byte[] n=name(e.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8);le(payload,e.getValue());le(payload,e.getKey().isAir()?1:0);le(payload,n.length);payload.write(n);}
      for(int j=0;j<sections.size();j++){for(int k:keys.get(j))le(payload,k);longle(payload,1);for(int v:sections.get(j))le(payload,v);}
      byte[] body=payload.toByteArray();try(var f=Files.newOutputStream(out.resolve("world.dwv"))){f.write(new byte[]{68,87,86,79,88,69,76,0});le(f,1);le(f,16);le(f,materials.size());le(f,sections.size());longle(f,body.length);longle(f,fnv(body));f.write(body);}
      var fluidHeights=new TreeMap<String,Float>();
      for(var state:materials.keySet())if(!state.getFluidState().isEmpty())fluidHeights.put(name(state),state.getFluidState().getOwnHeight());
      int waterTexture=-1;for(var sprite:ExportAtlas.sprites)if(sprite.name().equals("minecraft:block/water_still")){waterTexture=textures.computeIfAbsent(sprite,k->textures.size());break;}
      var texlist=new ArrayList<Map<String,Object>>();Files.createDirectories(out.resolve("sprites"));
      for(var e:textures.entrySet()){var s=e.getKey();String file="sprites/"+e.getValue()+".rgba";Files.write(out.resolve(file),s.rgba());texlist.add(Map.of("id",e.getValue(),"name",s.name(),"width",s.width(),"height",s.height(),"file",file));}
      int ground=c.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,16,0);
      var avatar=new ArrayList<Map<String,Object>>();var root=com.dark.winter.client.equipment.ThirdPersonBodyModel.createRoot(false);
      for(String part:List.of("body","head","right_arm","left_arm","right_leg","left_leg")){
       var model=root.getChild(part);var pivot=model.getInitialPose();var quads=new ArrayList<Map<String,Object>>();
       model.visit(new com.mojang.blaze3d.vertex.PoseStack(),(poses,path,index,cube)->{
        for(var face:cube.polygons){var points=new ArrayList<float[]>();var uv=new ArrayList<float[]>();
         for(var v:face.vertices()){var transformed=new org.joml.Vector4f(v.x()/16f,v.y()/16f,v.z()/16f,1).mul(poses.pose());points.add(new float[]{transformed.x-pivot.x()/16f,-transformed.y+pivot.y()/16f,transformed.z-pivot.z()/16f});uv.add(new float[]{v.u(),v.v()});}
         quads.add(Map.of("p",points,"uv",uv,"texture",0,"tint",4294967295L));
        }
       });
       avatar.add(Map.of("name",part,"pivot",List.of(pivot.x()/16f,(24-pivot.y())/16f,pivot.z()/16f),"quads",quads));
      }
      Files.writeString(out.resolve("avatar.json"),new Gson().toJson(avatar));
      var result=new LinkedHashMap<String,Object>();result.put("collisionShapes",collisionShapes);result.put("collisionDefaults",materials.values().stream().map(collisionDefaults::get).toList());result.put("collisionOverrides",collisionOverrides);result.put("waterTexture",waterTexture);result.put("fluidHeights",fluidHeights);result.put("completed",true);result.put("biome","darkwinter:darkwinter_test");result.put("seed",19021);result.put("bounds",List.of(MIN*16,Y0,MIN*16,(MAX+1)*16,Y1,(MAX+1)*16));result.put("camera",List.of(16.5,ground+2.5,-12.5,0,-.12));result.put("materials",materials.keySet().stream().map(WorldExport::name).toList());result.put("shapes",shapes);result.put("overrides",overrides);result.put("textures",texlist);
      Files.writeString(out.resolve("scene.json"),new Gson().toJson(result));
      System.out.println("CORE_BIOME_EXPORT_PASS materials="+materials.size()+" shapes="+shapes.size()+" textures="+textures.size()+" sections="+sections.size());
     }catch(Exception e){throw new RuntimeException(e);}
    });
   }
  }catch(Exception e){throw new AssertionError("Actual biome export failed",e);}
 }
}
