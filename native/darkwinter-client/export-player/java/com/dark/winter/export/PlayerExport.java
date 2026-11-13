// Build-time only: collision shapes queried against the immutable imported snapshot.
package com.dark.winter.export;
import com.dark.winter.loading.WorldPreloadServer;
import com.dark.winter.block.WallSegmentBlock;
import com.google.gson.Gson;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.*;import net.minecraft.core.registries.*;
import net.minecraft.resources.Identifier;import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.sdl.SDLVideo;
public final class PlayerExport implements ClientModInitializer,FabricClientGameTest {
 static final TagKey<Block> TERRAIN=TagKey.create(Registries.BLOCK,Identifier.fromNamespaceAndPath("dark-winter","step_assist_terrain"));
 static final TagKey<Block> FORBIDDEN=TagKey.create(Registries.BLOCK,Identifier.fromNamespaceAndPath("dark-winter","step_assist_forbidden"));
 static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
 static <T extends Comparable<T>> BlockState property(BlockState s,Property<T> p,String value){return s.setValue(p,p.getValue(value).orElseThrow());}
 static BlockState parse(String name){
  int bracket=name.indexOf('[');String id=bracket<0?name:name.substring(0,bracket);
  Block block=BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElseThrow();
  BlockState state=block.defaultBlockState();
  if(bracket>=0)for(String item:name.substring(bracket+1,name.length()-1).split(",")){
   String[] kv=item.split("=");var p=block.getStateDefinition().getProperty(kv[0]);require(p!=null,"Unknown property "+item);state=property(state,p,kv[1]);
  }return state;
 }
 record Key(int x,int y,int z){}
 static final class Snapshot implements BlockGetter {
  List<BlockState> palette=new ArrayList<>();List<String> names=new ArrayList<>();Map<Key,int[]> sections=new LinkedHashMap<>();
  Snapshot(Path source)throws IOException{
   ByteBuffer b=ByteBuffer.wrap(Files.readAllBytes(source)).order(ByteOrder.LITTLE_ENDIAN);
   byte[] magic=new byte[8];b.get(magic);require(Arrays.equals(magic,new byte[]{68,87,86,79,88,69,76,0})&&b.getInt()==1&&b.getInt()==16,"DWV header");
   int nm=b.getInt(),ns=b.getInt();b.getLong();b.getLong();
   for(int i=0;i<nm;i++){require(b.getInt()==i,"Material order");b.getInt();byte[] text=new byte[b.getInt()];b.get(text);String name=new String(text,java.nio.charset.StandardCharsets.UTF_8);names.add(name);palette.add(parse(name));}
   for(int i=0;i<ns;i++){Key k=new Key(b.getInt(),b.getInt(),b.getInt());b.getLong();int[] cells=new int[4096];for(int j=0;j<4096;j++)cells[j]=b.getInt();sections.put(k,cells);}
   require(!b.hasRemaining(),"DWV trailing bytes");
  }
  public BlockState getBlockState(BlockPos p){int[] cells=sections.get(new Key(Math.floorDiv(p.getX(),16),Math.floorDiv(p.getY(),16),Math.floorDiv(p.getZ(),16)));return cells==null?Blocks.AIR.defaultBlockState():palette.get(cells[Math.floorMod(p.getX(),16)+16*Math.floorMod(p.getZ(),16)+256*Math.floorMod(p.getY(),16)]);}
  public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
  public BlockEntity getBlockEntity(BlockPos p){return null;}
  public int getHeight(){return 112;}public int getMinY(){return 48;}
 }
 static boolean terrain(Snapshot s,BlockPos p,BlockState state){
  if(state.getBlock() instanceof WallSegmentBlock||state.is(FORBIDDEN))return false;
  // Existing native test terrain: authored half-blocks and scattered rocks are ground.
  if(state.is(com.dark.winter.worldgen.TestTerrainBlocks.SNOWY_GRASS_SLAB))return true;
  if(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("darkwinter:rock_scatter"))return true;
  // Snow support is evaluated at runtime because editing can replace its supporting block.
  return state.is(TERRAIN);
 }
 public void onInitializeClient(){
  ClientLifecycleEvents.CLIENT_STARTED.register(c->SDLVideo.SDL_HideWindow(c.getWindow().handle()));
  ServerPlayConnectionEvents.JOIN.register((h,s,server)->WorldPreloadServer.cancel(server));
 }
 public void runTest(ClientGameTestContext context){
  Path out=Path.of(System.getProperty("darkwinter.playerExport"));
  try{
   Files.createDirectories(out);
   try(var fixture=context.worldBuilder().create()){
    context.waitFor(c->c.level!=null&&c.player!=null,1200);
    context.runOnClient(c->{try{
     Snapshot snapshot=new Snapshot(Path.of(System.getProperty("darkwinter.playerWorld")));
     var collisionShapes=new ArrayList<Map<String,Object>>();var cache=new HashMap<Object,Integer>();
     Integer[] defaults=new Integer[snapshot.palette.size()];var overrides=new ArrayList<int[]>();
     for(var section:snapshot.sections.entrySet()){
      var k=section.getKey();int[] cells=section.getValue();
      for(int i=0;i<4096;i++){
       int mid=cells[i];BlockPos pos=new BlockPos(k.x()*16+(i&15),k.y()*16+(i>>>8),k.z()*16+((i>>>4)&15));var state=snapshot.palette.get(mid);
       var boxes=state.getCollisionShape(snapshot,pos).toAabbs().stream().map(b->List.of(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ)).toList();
       boolean natural=terrain(snapshot,pos,state);Object key=List.of(mid,natural,boxes);Integer shape=cache.get(key);
       if(shape==null){shape=collisionShapes.size();cache.put(key,shape);collisionShapes.add(Map.of("material",mid,"terrain",natural,"boxes",boxes));}
       if(defaults[mid]==null)defaults[mid]=shape;
       if(!defaults[mid].equals(shape))overrides.add(new int[]{pos.getX(),pos.getY(),pos.getZ(),mid,shape});
      }
     }
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

     Files.writeString(out.resolve("scene.json"),new Gson().toJson(Map.of("completed",true,"snapshotSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(System.getProperty("darkwinter.playerWorld"))))),"materials",snapshot.names,"collisionShapes",collisionShapes,"collisionDefaults",Arrays.asList(defaults),"collisionOverrides",overrides)));
     System.out.println("CORE_PLAYER_EXPORT_PASS shapes="+collisionShapes.size()+" overrides="+overrides.size());
    }catch(Exception e){throw new RuntimeException(e);}});
   }
  }catch(Exception e){throw new AssertionError("Player asset export failed",e);}
 }
}
