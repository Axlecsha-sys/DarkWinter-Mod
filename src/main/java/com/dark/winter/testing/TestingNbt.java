// Preserves native NBT tag types during editor snapshot serialization.
package com.dark.winter.testing;

import java.io.*;
import java.util.Base64;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Lossless native NBT. JSON numbers cannot preserve Byte/Short/Float tag identity. */
public final class TestingNbt {
    private static final long LIMIT=4L*1024*1024;
    private TestingNbt() {}
    public static String encode(CompoundTag tag) throws IOException {
        if(tag==null)return null;
        if(tag.sizeInBytes()>LIMIT)throw new IOException("NBT payload exceeds 4 MiB");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag,out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
    public static CompoundTag decode(String encoded) throws IOException {
        if(encoded==null)return null;
        if(encoded.length()>8*1024*1024)throw new IOException("Encoded NBT payload exceeds limit");
        byte[] bytes;
        try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw new IOException("Invalid NBT base64",e);}
        try(InputStream in=new ByteArrayInputStream(bytes)){
            return NbtIo.readCompressed(in,NbtAccounter.create(LIMIT));
        }catch(RuntimeException e){throw new IOException("Invalid native NBT",e);}
    }
}
