// Build-time decode only; the native client ships PCM and never loads Java or STB.
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import com.google.gson.*;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.*;
public final class ExportCoreSounds {
 static void le(OutputStream out,int v)throws IOException{out.write(v);out.write(v>>>8);out.write(v>>>16);out.write(v>>>24);}
 static void longle(OutputStream out,long v)throws IOException{le(out,(int)v);le(out,(int)(v>>>32));}
 static long hash(byte[] data){long h=0xcbf29ce484222325L;for(byte b:data)h=(h^(b&255))*0x100000001b3L;return h;}
 public static void main(String[] args)throws Exception{
  Path assets=Path.of(args[0]),out=Path.of(args[2]);JsonObject objects=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject().getAsJsonObject("objects");
  var data=new ByteArrayOutputStream();le(data,16);
  for(String group:new String[]{"snow","stone","gravel","wood"})for(int index=1;index<=4;index++){
   String name="minecraft/sounds/step/"+group+index+".ogg",id=objects.getAsJsonObject(name).get("hash").getAsString();
   byte[] encoded=Files.readAllBytes(assets.resolve("objects").resolve(id.substring(0,2)).resolve(id));
   ByteBuffer input=MemoryUtil.memAlloc(encoded.length);input.put(encoded).flip();
   try(MemoryStack stack=MemoryStack.stackPush()){
    IntBuffer error=stack.mallocInt(1);long decoder=STBVorbis.stb_vorbis_open_memory(input,error,null);
    if(decoder==0)throw new IOException("Cannot decode "+name);
    try{
     STBVorbisInfo info=STBVorbisInfo.malloc(stack);STBVorbis.stb_vorbis_get_info(decoder,info);
     int channels=info.channels(),rate=info.sample_rate(),length=STBVorbis.stb_vorbis_stream_length_in_samples(decoder);
     if(channels<1||channels>2||rate<8000||rate>192000||length<=0||length>rate)throw new IOException("Footstep format");
     ShortBuffer pcm=MemoryUtil.memAllocShort(length*channels);
     try{
     int count=STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder,channels,pcm),frames=(int)Math.round(count*44100.0/rate);
     if(frames<=0||frames>44100)throw new IOException("Footstep length");
     le(data,frames);
     for(int i=0;i<frames;i++){
      double source=i*(double)rate/44100;int a=Math.min(count-1,(int)source),b=Math.min(count-1,a+1);double f=source-a,value=0;
      for(int c=0;c<channels;c++)value+=pcm.get(a*channels+c)*(1-f)+pcm.get(b*channels+c)*f;
      int v=(int)Math.round(value/channels);data.write(v);data.write(v>>>8);
     }
    }finally{MemoryUtil.memFree(pcm);}
    }finally{STBVorbis.stb_vorbis_close(decoder);}
   }finally{MemoryUtil.memFree(input);}
  }
  byte[] payload=data.toByteArray();Files.createDirectories(out.getParent());
  try(var f=Files.newOutputStream(out)){f.write(new byte[]{68,87,83,79,85,78,68,0});le(f,1);le(f,44100);longle(f,payload.length);longle(f,hash(payload));f.write(payload);}
  System.out.println("CORE_SOUNDS_PASS samples=16 rate=44100 bytes="+payload.length);
 }
}
