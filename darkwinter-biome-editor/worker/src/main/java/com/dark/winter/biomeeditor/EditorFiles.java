// Owns bounded local IPC documents and atomic metadata publication for one editor process.
package com.dark.winter.biomeeditor;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.UUID;
final class EditorFiles {
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    final Path root;
    private Object requestFileKey;
    private java.nio.file.attribute.FileTime requestModified;
    private long requestSize=-1;
    EditorFiles()throws IOException{
        String configured=System.getProperty("darkwinter.biomeEditor.ipc","");
        if(configured.isBlank())throw new IOException("Missing editor IPC directory");
        root=Path.of(configured).toAbsolutePath().normalize();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root))throw new IOException("IPC directory is not a real directory");
        JsonObject owner=new JsonObject();owner.addProperty("schema",1);owner.addProperty("pid",ProcessHandle.current().pid());
        atomic(root.resolve("worker-owner.json"),owner);
    }
    JsonObject request()throws IOException{
        Path p=root.resolve("request.json");
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))return null;
        var attributes=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(attributes.isSymbolicLink()||!attributes.isRegularFile()||attributes.size()>10*1024*1024)throw new IOException("Excessive or linked editor request");
        if(requestSize==attributes.size()&&java.util.Objects.equals(requestFileKey,attributes.fileKey())
                &&java.util.Objects.equals(requestModified,attributes.lastModifiedTime()))return null;
        JsonElement value=JsonParser.parseString(Files.readString(p,StandardCharsets.UTF_8));
        if(!value.isJsonObject())throw new IOException("Invalid request object");
        requestSize=attributes.size();requestFileKey=attributes.fileKey();requestModified=attributes.lastModifiedTime();
        return value.getAsJsonObject();
    }
    void state(long revision,long scene,String state,double progress,String message,String error,Path export){
        JsonObject j=new JsonObject();j.addProperty("schema",1);j.addProperty("revision",revision);j.addProperty("sceneRevision",scene);
        j.addProperty("status",state);j.addProperty("progress",progress);j.addProperty("message",message);j.addProperty("error",error==null?"":error);
        if(export!=null)j.addProperty("exportDirectory",export.toAbsolutePath().toString());
        j.addProperty("backend","VULKAN");j.addProperty("exactGeneration",true);
        try{atomic(root.resolve("state.json"),j);}catch(IOException failure){System.err.println("Editor state publication failed: "+failure.getMessage());}
    }
    static void atomic(Path target,JsonObject j)throws IOException{
        Files.createDirectories(target.getParent());
        Path temporary=target.resolveSibling(target.getFileName()+".tmp-"+UUID.randomUUID());
        try{
            Files.writeString(temporary,JSON.toJson(j),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
