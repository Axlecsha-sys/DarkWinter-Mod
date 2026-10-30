// Reads and atomically writes bounded private documents with operating-system owner protection.
package com.dark.winter.online.identity;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Bounded strict documents and OS-owner protection; no native game classes or network I/O. */
public final class ProfileFiles {
    public static final int MAX_BYTES=16*1024*1024;
    private ProfileFiles(){}
    public static Path directory(Path requested)throws IOException {
        Path base=requested.toAbsolutePath().normalize();
        requireOutsideWorld(base);
        Files.createDirectories(base);protect(base,true);
        return base.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }
    public static void requireOutsideWorld(Path path)throws IOException {
        for(Path parent=path.toAbsolutePath().normalize();parent!=null;parent=parent.getParent()){
            if(Files.isSymbolicLink(parent))throw new IOException("Profile path must not contain symbolic links");
            if(Files.isRegularFile(parent.resolve("level.dat"))||Files.isRegularFile(parent.resolve("data/darkwinter-testing.properties")))
                throw new IOException("Identity storage cannot be placed in a game world");
        }
    }
    public static void protect(Path path,boolean directory)throws IOException {
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null){
            Set<PosixFilePermission> permissions=directory?PosixFilePermissions.fromString("rwx------"):PosixFilePermissions.fromString("rw-------");
            posix.setPermissions(permissions);
            if(!posix.readAttributes().permissions().equals(permissions))throw new IOException("Owner-only permissions could not be verified");
            return;
        }
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null)throw new IOException("This filesystem cannot protect local identity files");
        UserPrincipal user=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if(directory)entry.setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(entry.build()));
        List<AclEntry> entries=acl.getAcl();
        if(entries.isEmpty()||entries.stream().anyMatch(value->value.type()==AclEntryType.ALLOW&&!value.principal().equals(user)))
            throw new IOException("Owner-only ACL could not be verified");
    }
    public static JsonObject read(Path file,int limit)throws IOException {
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>limit)
            throw new IOException("Missing, linked or oversized profile document");
        byte[] bytes=Files.readAllBytes(file);if(bytes.length>limit)throw new IOException("Profile grew beyond its size limit");
        return parse(bytes,limit);
    }
    public static JsonObject parse(byte[] bytes,int limit)throws IOException {
        if(bytes.length>limit)throw new IOException("Document exceeds its size limit");
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(JsonReader reader=new JsonReader(new StringReader(text))){
                reader.setStrictness(Strictness.STRICT);JsonElement value=parseValue(reader,0,new int[]{0});
                if(reader.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject())throw new IOException("Expected one JSON object");
                return value.getAsJsonObject();
            }
        }catch(RuntimeException failure){throw new IOException("Invalid profile document",failure);}
    }
    private static JsonElement parseValue(JsonReader reader,int depth,int[] count)throws IOException {
        if(depth>32||++count[0]>200000)throw new IOException("Document nesting or item budget exceeded");
        return switch(reader.peek()){
            case BEGIN_OBJECT->{reader.beginObject();JsonObject value=new JsonObject();while(reader.hasNext()){String key=reader.nextName();if(value.has(key))throw new IOException("Duplicate JSON field");value.add(key,parseValue(reader,depth+1,count));}reader.endObject();yield value;}
            case BEGIN_ARRAY->{reader.beginArray();JsonArray value=new JsonArray();while(reader.hasNext())value.add(parseValue(reader,depth+1,count));reader.endArray();yield value;}
            case STRING->new JsonPrimitive(reader.nextString());case NUMBER->new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN->new JsonPrimitive(reader.nextBoolean());case NULL->{reader.nextNull();yield JsonNull.INSTANCE;}
            default->throw new IOException("Invalid JSON token");
        };
    }
    public static byte[] bytes(JsonElement value){return new Gson().toJson(canonical(value)).getBytes(StandardCharsets.UTF_8);}
    private static JsonElement canonical(JsonElement value){
        if(value.isJsonObject()){JsonObject result=new JsonObject();for(String key:new TreeSet<>(value.getAsJsonObject().keySet()))result.add(key,canonical(value.getAsJsonObject().get(key)));return result;}
        if(value.isJsonArray()){JsonArray result=new JsonArray();for(JsonElement row:value.getAsJsonArray())result.add(canonical(row));return result;}
        return value.deepCopy();
    }
    public static void atomic(Path destination,JsonObject value,boolean replace)throws IOException {
        byte[] bytes=bytes(value);if(bytes.length>MAX_BYTES)throw new IOException("Profile storage limit reached");
        Path parent=destination.toAbsolutePath().normalize().getParent();Files.createDirectories(parent);
        if(Files.isSymbolicLink(destination)||Files.isSymbolicLink(parent))throw new IOException("Linked profile destination");
        Path temporary=Files.createTempFile(parent,"dw-profile-",".tmp");boolean published=false;
        try{
            protect(temporary,false);
            try(FileChannel output=FileChannel.open(temporary,StandardOpenOption.WRITE)){
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
            }
            try{
                if(replace)Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                else Files.createLink(destination,temporary);
                published=true;
            }catch(AtomicMoveNotSupportedException unsupported){throw new IOException("Profile storage requires atomic file publication",unsupported);}
            // The published inode already has verified owner-only protection. No fallible mutation after commit.
        }finally{
            if(published){try{Files.deleteIfExists(temporary);}catch(IOException cleanup){/* A protected orphan is safer than reporting an already committed write as failed. */}}
            else Files.deleteIfExists(temporary);
        }
    }
    public static void fields(JsonObject value,Set<String> required,Set<String> optional)throws IOException {
        if(!value.keySet().containsAll(required))throw new IOException("Missing required document field");
        Set<String> allowed=new HashSet<>(required);allowed.addAll(optional);
        if(!allowed.containsAll(value.keySet()))throw new IOException("Unknown document field");
    }
    public static String string(JsonObject value,String key,int max)throws IOException {
        var element=value.get(key);if(element==null||!element.isJsonPrimitive()||!element.getAsJsonPrimitive().isString()||element.getAsString().length()>max)
            throw new IOException("Invalid string field: "+key);return element.getAsString();
    }
    public static long number(JsonObject value,String key)throws IOException {
        try{var element=value.get(key);if(element==null||!element.isJsonPrimitive()||!element.getAsJsonPrimitive().isNumber())throw new IOException("Invalid numeric field");return element.getAsBigDecimal().longValueExact();}
        catch(ArithmeticException failure){throw new IOException("Invalid integer field",failure);}
    }
}
