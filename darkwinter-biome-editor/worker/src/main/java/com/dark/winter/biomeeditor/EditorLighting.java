// Captures the native 16x16 lightmap only when editor scene lighting changes.
package com.dark.winter.biomeeditor;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.Minecraft;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.function.*;

final class EditorLighting implements AutoCloseable {
    private final EditorFiles files;
    private final ExecutorService io=Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("BiomeEditor-lightmap-io").factory());
    private GpuBuffer buffer;
    private boolean busy;
    private long serial;
    EditorLighting(EditorFiles files){this.files=files;}
    boolean idle(){return !busy;}
    boolean capture(Minecraft c,long revision,long scene,long epoch,JsonObject environment,
            BooleanSupplier current,boolean publish,Consumer<JsonObject> complete,Consumer<Throwable> failed){
        if(busy)return false;
        var texture=c.gameRenderer.levelLightmap().texture();
        if(texture.getFormat()!=GpuFormat.RGBA8_UNORM||texture.getWidth(0)!=16||texture.getHeight(0)!=16)
            throw new IllegalStateException("Native lightmap must be 16x16 RGBA8");
        if(buffer==null)buffer=RenderSystem.getDevice().createBuffer(()->"Biome editor native lightmap readback",9,1024);
        final String filename="lightmap-"+scene+"-"+revision+"-"+(++serial)+".rgba";
        final JsonObject env=environment.deepCopy();
        busy=true;
        try{
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture,buffer,0L,()->{
                try{
                    if(!current.getAsBoolean())throw new CancellationException("Stale lightmap capture");
                    final byte[] rgba=new byte[1024];
                    try(var mapped=buffer.map(true,false)){mapped.data().duplicate().position(0).limit(1024).get(rgba);}
                    io.execute(()->{
                        try{
                            if(!current.getAsBoolean())throw new CancellationException("Superseded lightmap");
                            Path target=files.root.resolve(filename),temporary=files.root.resolve(filename+".writing");
                            Files.write(temporary,rgba,StandardOpenOption.CREATE_NEW);
                            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
                            JsonObject asset=new JsonObject();
                            asset.addProperty("file",filename);asset.addProperty("bytes",1024);
                            asset.addProperty("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba)));
                            asset.addProperty("width",16);asset.addProperty("height",16);asset.addProperty("format","RGBA8");
                            if(!current.getAsBoolean())throw new CancellationException("Superseded lightmap publication");
                            if(publish){
                                JsonObject lighting=new JsonObject();
                                lighting.addProperty("schema",1);lighting.addProperty("sceneRevision",scene);
                                lighting.addProperty("revision",revision);lighting.addProperty("resourceEpoch",epoch);
                                lighting.add("environment",env);lighting.add("lightmap",asset);
                                EditorFiles.atomic(files.root.resolve("lighting.json"),lighting);
                            }
                            c.execute(()->{busy=false;if(current.getAsBoolean())complete.accept(asset);});
                        }catch(Throwable failure){c.execute(()->{busy=false;failed.accept(failure);});}
                    });
                }catch(Throwable failure){busy=false;failed.accept(failure);}
            },0);
        }catch(Throwable failure){busy=false;throw failure;}
        return true;
    }
    public void close(){
        if(busy)throw new IllegalStateException("Native lightmap readback still in flight");
        io.shutdown();
        if(buffer!=null){buffer.close();buffer=null;}
    }
}
