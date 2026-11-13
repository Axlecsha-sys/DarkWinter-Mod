package com.dark.winter.export;
import com.dark.winter.export.mixin.SpriteAccess;
import net.minecraft.client.renderer.texture.*;
import java.nio.*;import java.nio.file.*;import java.util.*;
public final class ExportAtlas {
 public record Sprite(String name,float u0,float v0,float u1,float v1,int width,int height,byte[] rgba){}
 public static volatile List<Sprite> sprites=List.of();
 public static void capture(TextureAtlas atlas,SpriteLoader.Preparations p){
  if(!atlas.location().equals(TextureAtlas.LOCATION_BLOCKS))return;
  var out=new ArrayList<Sprite>();
  for(var s:p.regions().values()){
   var c=s.contents();var image=((SpriteAccess)c).world$mips()[0];int w=c.width(),h=c.height();
   int frame=c.isAnimated()?c.getUniqueFrames().getInt(0):0,cols=image.getWidth()/w,sx=frame%cols*w,sy=frame/cols*h;
   byte[] rgba=new byte[w*h*4];var src=image.getPixelBytes();
   for(int y=0;y<h;y++)src.get(((sy+y)*image.getWidth()+sx)*4,rgba,y*w*4,w*4);
   out.add(new Sprite(c.name().toString(),s.getU0(),s.getV0(),s.getU1(),s.getV1(),w,h,rgba));
  }sprites=List.copyOf(out);
 }
 public static Sprite find(float u,float v){
  for(var s:sprites)if(u>=s.u0()-1e-7f&&u<=s.u1()+1e-7f&&v>=s.v0()-1e-7f&&v<=s.v1()+1e-7f)return s;
  throw new IllegalStateException("No sprite for UV "+u+","+v);
 }
}
