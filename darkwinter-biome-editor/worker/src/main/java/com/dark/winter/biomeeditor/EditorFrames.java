// Copies native readbacks on their render owner and publishes frames on one bounded I/O executor.
package com.dark.winter.biomeeditor;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class EditorFrames implements AutoCloseable {
    private static final int SLOTS=3;
    private final GpuBuffer[] buffers=new GpuBuffer[SLOTS];
    private final boolean[] busy=new boolean[SLOTS];
    private final ByteBuffer[] pixels=new ByteBuffer[SLOTS],encoded=new ByteBuffer[SLOTS];
    private final EditorFiles files;
    private final ThreadPoolExecutor publisher=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(SLOTS),Thread.ofPlatform().daemon(true).name("BiomeEditor-frame-io").factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong publications=new AtomicLong(),maxCopyNanos=new AtomicLong(),maxEncodeNanos=new AtomicLong(),maxWriteNanos=new AtomicLong();
    private int width,height,next;
    private long serial;
    EditorFrames(EditorFiles files){this.files=files;}
    boolean capture(RenderTarget target,long revision,long scene,JsonObject camera,java.util.function.BooleanSupplier current,java.util.function.Consumer<Throwable> completed){
        int w=target.width,h=target.height;
        if(w<1||h<1||w>4096||h>4096||target.getColorTexture()==null)return false;
        var texture=target.getColorTexture();if(texture.getFormat()!=com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM)throw new IllegalStateException("Editor requires native RGBA8 readback");
        if(width!=w||height!=h){
            if(!idle())return false;
            releaseBuffers();width=w;height=h;
            for(int i=0;i<SLOTS;i++){
                buffers[i]=RenderSystem.getDevice().createBuffer(()->"Biome editor asynchronous frame",9,(long)w*h*4);
                pixels[i]=ByteBuffer.allocateDirect(w*h*4);encoded[i]=ByteBuffer.allocateDirect(w*h*4);
            }
        }
        int selected=-1;for(int i=0;i<SLOTS;i++){int slot=(next+i)%SLOTS;if(!busy[slot]){selected=slot;break;}}
        if(selected<0)return false;
        final int slot=selected;next=(slot+1)%SLOTS;busy[slot]=true;long sequence=++serial;
        JsonObject pose=camera.deepCopy();
        try{
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture,buffers[slot],0L,()->{
                try{
                    if(!current.getAsBoolean())throw new CancellationException("Stale preview frame");
                    long started=System.nanoTime();
                    try(var mapped=buffers[slot].map(true,false)){
                        ByteBuffer raw=pixels[slot];raw.clear();raw.put(mapped.data().duplicate().position(0).limit(w*h*4));raw.flip();
                    }
                    maxCopyNanos.accumulateAndGet(System.nanoTime()-started,Math::max);
                    publisher.execute(()->publish(slot,w,h,revision,scene,sequence,pose,current,completed));
                }catch(Throwable failure){finish(slot,completed,failure);}
            },0);
        }catch(Throwable failure){busy[slot]=false;throw failure;}
        return true;
    }
    private void publish(int slot,int w,int h,long revision,long scene,long sequence,JsonObject camera,
            java.util.function.BooleanSupplier current,java.util.function.Consumer<Throwable> completed){
        Throwable result=null;
        try{
            if(!current.getAsBoolean())throw new CancellationException("Stale preview frame");
            long started=System.nanoTime();
            ByteBuffer raw=pixels[slot],output=encoded[slot];output.clear();
            for(int y=0;y<h;y++)output.put((h-y-1)*w*4,raw,y*w*4,w*4);
            for(int alpha=3;alpha<w*h*4;alpha+=4)output.put(alpha,(byte)255);
            output.position(0).limit(w*h*4);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(output.duplicate());
            maxEncodeNanos.accumulateAndGet(System.nanoTime()-started,Math::max);
            String filename="frame-"+slot+".rgba";Path path=files.root.resolve(filename),temporary=files.root.resolve(filename+".writing");
            started=System.nanoTime();
            try(FileChannel out=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
                while(output.hasRemaining())out.write(output);
            }
            try{Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}
            if(!current.getAsBoolean())throw new CancellationException("Superseded frame publication");
            JsonObject frame=new JsonObject();frame.addProperty("schema",1);frame.addProperty("revision",revision);frame.addProperty("sceneRevision",scene);
            frame.addProperty("sequence",sequence);frame.addProperty("width",w);frame.addProperty("height",h);frame.addProperty("format","RGBA8");
            frame.addProperty("file",filename);frame.addProperty("sha256",HexFormat.of().formatHex(digest.digest()));frame.add("camera",camera);
            EditorFiles.atomic(files.root.resolve("frame.json"),frame);
            maxWriteNanos.accumulateAndGet(System.nanoTime()-started,Math::max);publications.incrementAndGet();
        }catch(Throwable failure){result=failure;}
        finish(slot,completed,result);
    }
    private void finish(int slot,java.util.function.Consumer<Throwable> completed,Throwable result){
        Minecraft owner=Minecraft.getInstance();
        Runnable callback=()->{try{completed.accept(result);}finally{busy[slot]=false;}};
        if(owner.isSameThread())callback.run();else owner.execute(callback);
    }
    JsonObject diagnostics(){
        JsonObject result=new JsonObject();result.addProperty("publications",publications.get());
        result.addProperty("maxRenderCopyMs",maxCopyNanos.get()/1e6);result.addProperty("maxEncodeHashMs",maxEncodeNanos.get()/1e6);
        result.addProperty("maxPublicationMs",maxWriteNanos.get()/1e6);result.addProperty("ioQueueSize",publisher.getQueue().size());
        result.addProperty("renderThreadPayloadWrites",0);result.addProperty("slots",SLOTS);return result;
    }
    boolean idle(){for(boolean inFlight:busy)if(inFlight)return false;return true;}
    private void releaseBuffers(){
        for(int i=0;i<SLOTS;i++)if(buffers[i]!=null){buffers[i].close();buffers[i]=null;}
        for(int i=0;i<SLOTS;i++){pixels[i]=null;encoded[i]=null;}
        width=height=0;
    }
    public void close(){
        if(!idle())throw new IllegalStateException("Readback ownership must be released before closing");
        publisher.shutdown();releaseBuffers();
    }
}
