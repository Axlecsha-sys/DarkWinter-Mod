// Defines versioned, integrity-checked online payload frames.
package com.dark.winter.online.network;

import com.dark.winter.online.OnlineJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Versioned, integrity-checked frames. Native channel admission and per-action authority are separate mandatory checks. */
public record OnlinePayload(int protocol,String kind,UUID requestId,long epoch,int index,int count,String sha256,String body)
        implements CustomPacketPayload {
    public static final int VERSION=1,CHARS=16384,MAX_PARTS=128,MAX_BYTES=2*1024*1024;
    public static final Type<OnlinePayload> TYPE=new Type<>(Identifier.fromNamespaceAndPath("darkwinter","online_v1"));
    public OnlinePayload {
        if(protocol!=VERSION||kind==null||!kind.matches("[a-z][a-z0-9_]{0,31}")||requestId==null||epoch<0
                ||count<1||count>MAX_PARTS||index<0||index>=count||sha256==null||!sha256.matches("[0-9a-f]{64}")
                ||body==null||body.length()>CHARS)throw new IllegalArgumentException("Invalid Online frame");
        try{OnlineJson.unicode(body);}catch(IOException invalid){throw new IllegalArgumentException("Invalid frame Unicode",invalid);}
    }
    public static final StreamCodec<FriendlyByteBuf,OnlinePayload> CODEC=new StreamCodec<>(){
        @Override public OnlinePayload decode(FriendlyByteBuf buffer){
            return new OnlinePayload(buffer.readVarInt(),buffer.readUtf(32),buffer.readUUID(),buffer.readVarLong(),
                    buffer.readVarInt(),buffer.readVarInt(),buffer.readUtf(64),buffer.readUtf(CHARS));
        }
        @Override public void encode(FriendlyByteBuf buffer,OnlinePayload value){
            buffer.writeVarInt(value.protocol);buffer.writeUtf(value.kind,32);buffer.writeUUID(value.requestId);buffer.writeVarLong(value.epoch);
            buffer.writeVarInt(value.index);buffer.writeVarInt(value.count);buffer.writeUtf(value.sha256,64);buffer.writeUtf(value.body,CHARS);
        }
    };
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static List<OnlinePayload> frames(String kind,UUID requestId,long epoch,String json)throws IOException {
        OnlineJson.unicode(json);byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("Состояние Online превышает допустимый размер");
        String digest=hash(bytes);List<String> slices=new ArrayList<>();
        for(int start=0;start<json.length();){
            int end=Math.min(json.length(),start+CHARS);
            if(end<json.length()&&Character.isHighSurrogate(json.charAt(end-1)))end--;
            slices.add(json.substring(start,end));start=end;
        }
        if(slices.isEmpty())slices.add("");
        if(slices.size()>MAX_PARTS)throw new IOException("Слишком много частей сообщения Online");
        List<OnlinePayload> frames=new ArrayList<>();for(int i=0;i<slices.size();i++)frames.add(new OnlinePayload(VERSION,kind,requestId,epoch,i,slices.size(),digest,slices.get(i)));
        return List.copyOf(frames);
    }
    private static String hash(byte[] bytes)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    /** One instance per authenticated native connection; no unbounded partial messages survive disconnect. */
    public static final class Assembler {
        private static final long DEADLINE_NANOS=10_000_000_000L;
        private final int byteLimit,partLimit;
        private final Map<UUID,Partial> pending=new HashMap<>();
        private int bytes;
        private static final class Partial {
            final String kind,digest;final long epoch,deadline;final String[] pieces;int received,bytes;
            Partial(OnlinePayload packet,long now){kind=packet.kind;digest=packet.sha256;epoch=packet.epoch;deadline=now+DEADLINE_NANOS;pieces=new String[packet.count];}
        }
        public Assembler(){this(MAX_BYTES,MAX_PARTS);}
        public Assembler(int byteLimit,int partLimit){
            if(byteLimit<1||byteLimit>MAX_BYTES||partLimit<1||partLimit>MAX_PARTS)throw new IllegalArgumentException("Invalid assembler limits");
            this.byteLimit=byteLimit;this.partLimit=partLimit;
        }
        public synchronized Optional<String> accept(OnlinePayload packet,long expectedEpoch)throws IOException {
            prune();if(expectedEpoch>=0&&packet.epoch!=expectedEpoch)throw new IOException("Полномочия сеанса изменились");
            if(packet.count>partLimit)throw new IOException("Слишком много частей сообщения");
            Partial part=pending.get(packet.requestId);
            if(part==null){if(pending.size()>=4)throw new IOException("Слишком много незавершённых сообщений");part=new Partial(packet,System.nanoTime());pending.put(packet.requestId,part);}
            if(!part.kind.equals(packet.kind)||!part.digest.equals(packet.sha256)||part.epoch!=packet.epoch||part.pieces.length!=packet.count)
                throw new IOException("Конфликт частей сообщения Online");
            String previous=part.pieces[packet.index];
            if(previous!=null){if(!previous.equals(packet.body))throw new IOException("Повтор части содержит другие данные");return Optional.empty();}
            int size=packet.body.getBytes(StandardCharsets.UTF_8).length;
            if(size>byteLimit-bytes){pending.remove(packet.requestId);bytes-=part.bytes;throw new IOException("Буфер Online заполнен");}
            part.pieces[packet.index]=packet.body;part.received++;part.bytes+=size;bytes+=size;
            if(part.received!=part.pieces.length)return Optional.empty();
            pending.remove(packet.requestId);bytes-=part.bytes;
            String full=String.join("",part.pieces);
            if(!hash(full.getBytes(StandardCharsets.UTF_8)).equals(part.digest))throw new IOException("Контрольная сумма сообщения Online не совпала");
            return Optional.of(full);
        }
        public synchronized void prune(){
            long now=System.nanoTime();pending.entrySet().removeIf(entry->{if(now>=entry.getValue().deadline){bytes-=entry.getValue().bytes;return true;}return false;});
        }
        public synchronized void clear(){pending.clear();bytes=0;}
        public synchronized int bufferedBytes(){return bytes;}
    }
}
