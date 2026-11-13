// Build-only export of the project's approved fitted winter clothing, not inventory logic.
package com.dark.winter.client.equipment;
import com.dark.winter.equipment.GearSlot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.google.gson.Gson;
import java.nio.file.*;import java.util.*;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
public final class ExportDressedAvatar {
 static final String[] NAMES={"body","head","right_arm","left_arm","right_leg","left_leg","first_person_hand"};
 static final String SKIN="textures/entity/northern_survivor.png";
 static final ArrayList<Map<String,Object>>[] QUADS=new ArrayList[7];
 static final float[][] PIVOTS=new float[7][];
 static int bone(ClothingMeshes.Bone b){return switch(b){case BODY->0;case HEAD->1;case RIGHT_ARM,RIGHT_FOREARM->2;case LEFT_ARM,LEFT_FOREARM->3;case RIGHT_LEG,RIGHT_SHIN,RIGHT_FOOT->4;default->5;};}
 static void append(int id,ModelPart geometry,PoseStack stack,String texture,int tint){
  geometry.visit(stack,(pose,path,index,cube)->{for(var face:cube.polygons){var points=new ArrayList<float[]>();var uv=new ArrayList<float[]>();for(var v:face.vertices()){var p=new org.joml.Vector4f(v.x()/16f,v.y()/16f,v.z()/16f,1).mul(pose.pose());points.add(id==6?new float[]{p.x,p.y,p.z}:new float[]{p.x-PIVOTS[id][0],1.5f-p.y-PIVOTS[id][1],p.z-PIVOTS[id][2]});uv.add(new float[]{v.u(),v.v()});}QUADS[id].add(Map.of("p",points,"uv",uv,"texture",texture,"tint",Integer.toUnsignedLong(tint)));}});
 }
 static Object ordered(Object value){if(value instanceof Map<?,?> map){var out=new TreeMap<String,Object>();map.forEach((k,v)->out.put(k.toString(),ordered(v)));return out;}if(value instanceof List<?> list)return list.stream().map(ExportDressedAvatar::ordered).toList();return value;}
 public static void main(String[] args)throws Exception{
  var root=ThirdPersonBodyModel.createRoot(false);var model=new PlayerModel(root,false);
  for(int i=0;i<6;i++){var p=root.getChild(NAMES[i]).getInitialPose();PIVOTS[i]=new float[]{p.x()/16,(24-p.y())/16,p.z()/16};QUADS[i]=new ArrayList<>();append(i,root.getChild(NAMES[i]),new PoseStack(),SKIN,-1);}
  for(var slot:List.of(GearSlot.PANTS,GearSlot.JACKET,GearSlot.GLOVES,GearSlot.HAT,GearSlot.FOOTWEAR)){
   int variant=slot==GearSlot.JACKET?7:5;var pieces=ClothingMeshes.WARDROBE.get(ClothingMeshes.key(slot,variant));
   for(var source:pieces){var piece=source;var poses=new PoseStack();HumanJoints.transform(model,piece.bone(),poses);ThirdPersonBodyModel.fit(piece.bone(),poses);var texture=NorthernWandererClothing.texture(slot,piece.color());append(bone(piece.bone()),ThirdPersonBodyModel.garment(piece),poses,texture.getPath(),piece.color());}
  }
  for(var piece:NorthernWandererPack.fitted(NorthernWandererPack.create(1,true),1)){var poses=new PoseStack();model.body.translateAndRotate(poses);ThirdPersonBodyModel.fit(ClothingMeshes.Bone.BODY,poses);append(0,piece.geometry(),poses,NorthernWandererPack.texture(piece.color()).getPath(),piece.color());}
  // Neutral empty-hand frame from FirstPersonHandsAndItemsRenderer.renderPlayerArm (26.3).
  PIVOTS[6]=new float[]{0,0,0};QUADS[6]=new ArrayList<>();var hand=LegacyFirstPersonModel.prepare(false,net.minecraft.world.entity.HumanoidArm.RIGHT,true);var hp=new PoseStack();hp.translate(.64000005f,-.6f,-.71999997f);hp.rotateDegrees(com.mojang.math.Axis.YP,45);hp.translate(-1,3.6f,3.5f);hp.rotateDegrees(com.mojang.math.Axis.ZP,120);hp.rotateDegrees(com.mojang.math.Axis.XP,200);hp.rotateDegrees(com.mojang.math.Axis.YP,-135);hp.translate(5.6f,0,0);append(6,hand.arm(),hp,SKIN,-1);
  for(var slot:List.of(GearSlot.JACKET,GearSlot.GLOVES)){int variant=slot==GearSlot.JACKET?7:5;for(var piece:ClothingMeshes.WARDROBE.get(ClothingMeshes.key(slot,variant))){if(HumanJoints.parent(hand.model(),piece.bone())!=hand.arm())continue;hp.pushPose();HumanJoints.transform(hand.model(),piece.bone(),hp);append(6,piece.geometry(),hp,NorthernWandererClothing.texture(slot,piece.color()).getPath(),piece.color());hp.popPose();}}
  // AvatarRenderer.scale in Minecraft 26.3 applies 0.9375 to the full third-person pose.
  // Scale pivots and local vertices together; the separate first-person hand is unchanged.
  for(int i=0;i<6;i++){
   for(int k=0;k<3;k++)PIVOTS[i][k]*=.9375f;
   for(var face:QUADS[i])for(var point:(List<float[]>)face.get("p"))for(int k=0;k<3;k++)point[k]*=.9375f;
  }
  var result=new ArrayList<Map<String,Object>>();for(int i=0;i<7;i++)result.add(Map.of("name",NAMES[i],"pivot",PIVOTS[i],"quads",QUADS[i]));Path out=Path.of(args[0]);Files.createDirectories(out);String json=new Gson().toJson(ordered(result));Path target=out.resolve("avatar-dressed.json");if(!Files.isRegularFile(target)||!Files.readString(target).equals(json))Files.writeString(target,json);System.out.println("CORE_DRESSED_AVATAR "+Arrays.stream(QUADS).mapToInt(List::size).sum()+" quads");
 }
}
