// Validates a native 64x64 PNG icon before any library consumer sees its bytes.
package com.dark.winter.testing;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Validates a native 64x64 PNG icon before any library consumer sees its bytes. */
public final class TestingLibraryMetadata {
    private static final byte[] SIGNATURE={(byte)137,80,78,71,13,10,26,10};
    private TestingLibraryMetadata() {}
    public static byte[] png(Path file)throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||Files.size(file)<45||Files.size(file)>262144)
            throw new IOException("Миниатюра PNG отсутствует или превышает 256 КиБ");
        byte[] bytes=Files.readAllBytes(file);
        if(!Arrays.equals(Arrays.copyOf(bytes,8),SIGNATURE))throw new IOException("Миниатюра должна быть PNG");
        boolean header=false,data=false,end=false;
        try(var input=new DataInputStream(new ByteArrayInputStream(bytes,8,bytes.length-8))) {
            while(input.available()>0) {
                long length=Integer.toUnsignedLong(input.readInt());byte[] type=input.readNBytes(4);
                if(type.length!=4||length>262144||length>input.available()-4L)throw new IOException("Повреждена структура PNG");
                byte[] payload=input.readNBytes((int)length);long crc=Integer.toUnsignedLong(input.readInt());CRC32 check=new CRC32();check.update(type);check.update(payload);
                if(check.getValue()!=crc)throw new IOException("CRC миниатюры PNG не совпадает");
                String name=new String(type,java.nio.charset.StandardCharsets.US_ASCII);
                if(!header&&!name.equals("IHDR"))throw new IOException("PNG должен начинаться с IHDR");
                if(name.equals("IHDR")) {
                    if(header||length!=13)throw new IOException("Повторный или неверный IHDR");header=true;
                    try(var dimensions=new DataInputStream(new ByteArrayInputStream(payload))) {
                        if(dimensions.readInt()!=64||dimensions.readInt()!=64)throw new IOException("Native icon.png должен иметь размер 64x64");
                    }
                }else if(name.equals("IDAT"))data=true;
                else if(name.equals("IEND")){if(length!=0||!data||input.available()!=0)throw new IOException("Некорректный конец PNG");end=true;break;}
            }
        }
        if(!header||!data||!end)throw new IOException("PNG содержит неполные данные");
        var readers=ImageIO.getImageReadersByFormatName("png");if(!readers.hasNext())throw new IOException("PNG decoder недоступен");
        var reader=readers.next();
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input,true,true);
            if(reader.getWidth(0)!=64||reader.getHeight(0)!=64)throw new IOException("Некорректные размеры PNG");
            var image=reader.read(0);if(image==null||image.getWidth()!=64||image.getHeight()!=64)throw new IOException("Декодирование PNG не завершено");
            image.flush();return bytes;
        }catch(RuntimeException failure){throw new IOException("Миниатюра PNG не декодируется",failure);}
        finally{reader.dispose();}
    }
}
