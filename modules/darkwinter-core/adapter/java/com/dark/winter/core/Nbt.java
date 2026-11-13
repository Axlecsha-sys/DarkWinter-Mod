// Bounded standalone reader for modern Anvil import. It does not initialize Minecraft.
package com.dark.winter.core;
import java.io.*;
import java.util.*;

final class Nbt {
    private final DataInputStream input;
    private int nodes;
    private Nbt(byte[] bytes){input=new DataInputStream(new ByteArrayInputStream(bytes));}
    static Map<String,Object> root(byte[] bytes)throws IOException {
        Nbt reader=new Nbt(bytes);
        if(reader.input.readUnsignedByte()!=10)throw new IOException("NBT root must be a compound");
        reader.input.readUTF();
        var root=compound(reader.tag(10,0));
        if(reader.input.available()!=0)throw new IOException("Trailing NBT bytes");
        return root;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> compound(Object value)throws IOException {
        if(!(value instanceof Map<?,?>))throw new IOException("Expected NBT compound");
        return (Map<String,Object>)value;
    }
    private int length(int maximum)throws IOException {int n=input.readInt();if(n<0||n>maximum)throw new IOException("NBT length limit");return n;}
    private Object tag(int type,int depth)throws IOException {
        if(++nodes>100000||depth>64)throw new IOException("NBT complexity limit");
        return switch(type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> {int n=length(8*1024*1024);byte[] out=new byte[n];input.readFully(out);yield out;}
            case 8 -> input.readUTF();
            case 9 -> {
                int element=input.readUnsignedByte(),n=length(65536);
                if(element==0&&n!=0)throw new IOException("Nonempty END list");
                List<Object> out=new ArrayList<>(n);for(int i=0;i<n;i++)out.add(tag(element,depth+1));yield out;
            }
            case 10 -> {
                Map<String,Object> out=new LinkedHashMap<>();
                for(;;){int element=input.readUnsignedByte();if(element==0)break;String name=input.readUTF();
                    if(out.putIfAbsent(name,tag(element,depth+1))!=null)throw new IOException("Duplicate NBT key");}
                yield out;
            }
            case 11 -> {int n=length(2*1024*1024);int[] out=new int[n];for(int i=0;i<n;i++)out[i]=input.readInt();yield out;}
            case 12 -> {int n=length(1024*1024);long[] out=new long[n];for(int i=0;i<n;i++)out[i]=input.readLong();yield out;}
            default -> throw new IOException("Unsupported NBT tag "+type);
        };
    }
}
