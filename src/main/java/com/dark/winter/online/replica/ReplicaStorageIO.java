// Provides durable, bounded file operations for the private checkpoint replica store.
package com.dark.winter.online.replica;

import com.dark.winter.online.identity.*;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

final class ReplicaStorageIO {
    record Stamp(long bytes,long modified,Object key){}
    @FunctionalInterface interface Reader{InputStream open(String path)throws IOException;}
    private ReplicaStorageIO(){}
    static void safe(Path path)throws IOException{
        for(Path parent=path.toAbsolutePath().normalize();parent!=null;parent=parent.getParent())
            if(Files.isSymbolicLink(parent)||Files.exists(parent,LinkOption.NOFOLLOW_LINKS)&&!parent.toRealPath().equals(parent.toAbsolutePath().normalize()))
                throw new IOException("Linked or redirected replica storage/source path");
    }
    static Stamp stamp(Path file)throws IOException{
        safe(file);BasicFileAttributes attrs=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile())throw new IOException("Replica source is not a regular immutable blob");
        return new Stamp(attrs.size(),attrs.lastModifiedTime().toMillis(),attrs.fileKey());
    }
    static String hash(Path file,long expectedBytes)throws IOException{
        Stamp before=stamp(file);if(before.bytes()!=expectedBytes)throw new IOException("Replica blob size changed");
        MessageDigest digest=digest();byte[] buffer=new byte[ReplicaProtocol.CHUNK_BYTES];long read=0;
        try(InputStream input=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            for(int n;(n=input.read(buffer))!=-1;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Replica verification canceled");
                read+=n;if(read>expectedBytes)throw new IOException("Replica blob grew");digest.update(buffer,0,n);}
        }
        if(read!=expectedBytes||!before.equals(stamp(file)))throw new IOException("Replica immutable blob changed while reading");
        return HexFormat.of().formatHex(digest.digest());
    }
    static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    static void verify(Path file,ReplicaProtocol.Entry entry)throws IOException{if(!hash(file,entry.bytes()).equals(entry.sha256()))throw new IOException("Replica full file SHA mismatch");}
    static void world(ReplicaProtocol.Index index,Reader reader,com.dark.winter.online.authority.WorldAuthority.Certificate certificate)throws IOException{
        byte[] marker=small(index,reader,"data/darkwinter-testing.properties",65536);Properties props=new Properties();
        try(InputStream input=new ByteArrayInputStream(marker)){props.load(input);}
        if(!Set.of("1","2").contains(props.getProperty("schema"))||!"testing".equals(props.getProperty("mode")))throw new IOException("Replica is not a supported Testing world");
        byte[] access=small(index,reader,"data/darkwinter-online/access.json",512*1024);JsonObject document=ProfileFiles.parse(access,512*1024);
        if(ProfileFiles.number(document,"schema")!=1)throw new IOException("Unsupported replica public access schema");
        String expected=ReplicaProtocol.digest(ProfileFiles.string(document,"sha256",64));JsonObject body=document.deepCopy();body.remove("sha256");
        if(!ReplicaProtocol.hash(ProfileFiles.bytes(body)).equals(expected))throw new IOException("Replica public access checksum mismatch");
        String owner=ProfileFiles.string(body,"ownerId",64),world=ProfileFiles.string(body,"islandId",36);
        if(!owner.matches("dw1_[A-Za-z0-9_-]{43}"))throw new IOException("Invalid replica owner identity");ReplicaProtocol.uuid(world);
        if(certificate!=null)com.dark.winter.online.authority.WorldAuthority.requireAccess(certificate,world,owner);
    }
    private static byte[] small(ReplicaProtocol.Index index,Reader reader,String path,int cap)throws IOException{
        ReplicaProtocol.Entry entry=index.files().get(path);if(entry==null||entry.bytes()>cap)throw new IOException("Missing/oversized replica world marker/access");
        try(InputStream input=reader.open(path)){byte[] bytes=input.readNBytes(cap+1);if(bytes.length!=entry.bytes()||!ReplicaProtocol.hash(bytes).equals(entry.sha256()))
            throw new IOException("Replica world marker/access bytes changed");return bytes;}
    }
    static void writeNew(Path target,byte[] bytes)throws IOException{
        safe(target);Files.createDirectories(target.getParent());
        try(FileChannel channel=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ProfileFiles.protect(target,false);ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
    static void removeOwnedTree(Path root,Path expectedParent)throws IOException{
        root=root.toAbsolutePath().normalize();expectedParent=expectedParent.toAbsolutePath().normalize();safe(root);
        if(!root.getParent().equals(expectedParent))throw new IOException("Replica cleanup outside owned staging parent");
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        List<Path> files;try(var paths=Files.walk(root)){files=paths.limit(32769).sorted(Comparator.reverseOrder()).toList();}
        if(files.size()>32768)throw new IOException("Owned replica cleanup entry quota");
        for(Path file:files){if(!file.toAbsolutePath().normalize().startsWith(root)||Files.isSymbolicLink(file))throw new IOException("Unsafe replica cleanup entry");Files.delete(file);}
    }
}
