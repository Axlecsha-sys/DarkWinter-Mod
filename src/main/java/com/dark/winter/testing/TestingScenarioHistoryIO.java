// Pure JSON version-reference migration.
package com.dark.winter.testing;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Pure JSON version-reference migration. Native NBT, entity identities and reports are deliberately opaque. */
public final class TestingScenarioHistoryIO {
    private static final String ROOT="data/darkwinter-testing/",DOCS=ROOT+"document-history/",SCENARIOS=ROOT+"scenarios/";
    private static final long MAX_BYTES=128L*1024*1024;
    private static final int MAX_FILES=8192,MAX_OPERATIONS=8192,MAX_DEPTH=128,MAX_NODES=8_000_000;
    private static final Gson COMPACT=new Gson(),PRETTY=new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> BODY=List.of("schema","name","id","changes","entities","biomeBefore","biomeAfter","resizeId","sceneBefore","sceneAfter","documentsBefore","documentsAfter","actor","actorSession","actorPermissions","historySource");
    private enum Kind { NONE,AGGREGATE,SCENARIO,CURSOR,DOCUMENT,HISTORY,PENDING }
    public record Rewrite(Map<String,byte[]> replacements,Set<String> removals) {
        public Rewrite {Map<String,byte[]> copy=new LinkedHashMap<>();replacements.forEach((path,bytes)->copy.put(path,bytes.clone()));replacements=Collections.unmodifiableMap(copy);removals=Set.copyOf(removals);}
        @Override public Map<String,byte[]> replacements(){Map<String,byte[]> copy=new LinkedHashMap<>();replacements.forEach((path,bytes)->copy.put(path,bytes.clone()));return Collections.unmodifiableMap(copy);}
    }
    private TestingScenarioHistoryIO() {}
    public static boolean isMetadataPath(String path)throws IOException {return kind(path)!=Kind.NONE;}
    public static Set<String> references(String path,byte[] bytes)throws IOException {
        Kind kind=kind(path);if(kind==Kind.NONE)return Set.of();
        JsonObject root=parse(path,bytes);Set<String> result=new LinkedHashSet<>();walk(kind,root,null,result);return Set.copyOf(result);
    }
    /** Validates every known old hash/reference before returning a complete, non-mutating rewrite plan. */
    public static Rewrite rewrite(Map<String,byte[]> knownFiles,Map<String,String> ids)throws IOException {
        if(knownFiles==null||ids==null)throw new IOException("Missing scenario metadata input");
        Set<String> targets=new HashSet<>();for(var entry:ids.entrySet()){version(entry.getKey());version(entry.getValue());if(!targets.add(entry.getValue()))throw new IOException("Version remap is not independent/injective");}
        TreeMap<String,JsonObject> parsed=new TreeMap<>();long total=0;
        for(var file:knownFiles.entrySet()) {
            Kind kind=kind(file.getKey());if(kind==Kind.NONE)continue;
            if(file.getValue()==null||(total+=file.getValue().length)>MAX_BYTES||parsed.size()>=MAX_FILES)throw new IOException("Scenario metadata exceeds128MiB/8192 files");
            JsonObject root=parse(file.getKey(),file.getValue());walk(kind,root,null,new LinkedHashSet<>());parsed.put(file.getKey(),root);
        }
        // A native operation's immutable document link must resolve in this same saved world.
        for(var file:parsed.entrySet())if(kind(file.getKey())==Kind.HISTORY||kind(file.getKey())==Kind.PENDING)
            for(JsonObject operation:operations(kind(file.getKey()),file.getValue()))for(String field:List.of("documentsBefore","documentsAfter"))if(operation.has(field)) {
                String ref=reference(operation.get(field));if(!parsed.containsKey(DOCS+ref+".json"))throw new IOException("Missing immutable scenario document "+ref);
            }
        Map<String,byte[]> replacements=new LinkedHashMap<>();Set<String> removals=new LinkedHashSet<>();Map<String,String> documents=new HashMap<>();
        for(var file:parsed.entrySet())if(kind(file.getKey())==Kind.DOCUMENT) {
            JsonObject root=file.getValue().deepCopy();boolean changed=walk(Kind.DOCUMENT,root,ids,new LinkedHashSet<>());
            String old=file.getKey().substring(DOCS.length(),file.getKey().length()-5);
            if(!changed){documents.put(old,old);continue;}
            byte[] bytes=PRETTY.toJson(canonical(root)).getBytes(StandardCharsets.UTF_8);String hash=hash(bytes),path=DOCS+hash+".json";
            byte[] existing=knownFiles.get(path);if(existing!=null&&!Arrays.equals(existing,bytes))throw new IOException("Immutable document hash/path collision");
            put(replacements,path,bytes);removals.add(file.getKey());documents.put(old,hash);
        }
        for(var file:parsed.entrySet()) {
            Kind kind=kind(file.getKey());if(kind==Kind.DOCUMENT)continue;
            JsonObject root=file.getValue().deepCopy();boolean changed;
            if(kind==Kind.HISTORY||kind==Kind.PENDING) {
                changed=false;
                for(JsonObject operation:operations(kind,root)) {
                    boolean modified=false;
                    for(String field:List.of("documentsBefore","documentsAfter"))if(operation.has(field)) {
                        String before=reference(operation.get(field)),after=documents.get(before);
                        if(after==null)throw new IOException("Immutable document mapping missing");
                        if(!before.equals(after)){operation.addProperty(field,after);modified=true;}
                    }
                    if(modified&&operation.has("bodySha256"))operation.addProperty("bodySha256",bodyHash(operation));
                    changed|=modified;
                }
            }else changed=walk(kind,root,ids,new LinkedHashSet<>());
            if(changed)put(replacements,file.getKey(),PRETTY.toJson(root).getBytes(StandardCharsets.UTF_8));
        }
        removals.removeAll(replacements.keySet());
        long written=0;for(byte[] bytes:replacements.values())if((written+=bytes.length)>MAX_BYTES)throw new IOException("Rewritten scenario metadata exceeds128MiB");
        return new Rewrite(replacements,removals);
    }
    private static void put(Map<String,byte[]> files,String path,byte[] bytes)throws IOException {
        byte[] previous=files.putIfAbsent(path,bytes);if(previous!=null&&!Arrays.equals(previous,bytes))throw new IOException("Conflicting rewritten metadata path");
    }
    private static Kind kind(String path)throws IOException {
        if(path==null)throw new IOException("Missing metadata path");
        if(path.equals(ROOT+"scenario-documents.json"))return Kind.AGGREGATE;
        if(path.equals(ROOT+"replay-cursor.json"))return Kind.CURSOR;
        if(path.equals(ROOT+"undo.json"))return Kind.HISTORY;
        if(path.equals(ROOT+"pending-edit.json"))return Kind.PENDING;
        if(path.startsWith(DOCS))return path.matches("data/darkwinter-testing/document-history/[0-9a-f]{64}\\.json")?Kind.DOCUMENT:Kind.NONE;
        if(path.startsWith(SCENARIOS)&&path.endsWith(".json")) {
            String leaf=path.substring(SCENARIOS.length());
            if(leaf.length()<=5||leaf.length()>256||leaf.contains("/")||leaf.contains("\\")||leaf.contains("..")||leaf.contains(":"))throw new IOException("Invalid scenario definition leaf");
            return Kind.SCENARIO;
        }
        return Kind.NONE;
    }
    private static JsonObject parse(String path,byte[] bytes)throws IOException {
        if(bytes==null||bytes.length>MAX_BYTES)throw new IOException("Scenario metadata exceeds128MiB");
        Kind kind=kind(path);
        if(kind==Kind.DOCUMENT) {
            String expected=path.substring(DOCS.length(),path.length()-5);
            if(!hash(bytes).equals(expected))throw new IOException("Immutable document SHA-256 mismatch");
        }
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(JsonReader reader=new JsonReader(new StringReader(text))) {
                reader.setStrictness(Strictness.STRICT);JsonElement root=read(reader,0,new int[]{0});
                if(reader.peek()!=JsonToken.END_DOCUMENT||!root.isJsonObject())throw new IOException("Metadata root must be one JSON object");
                JsonObject object=root.getAsJsonObject();
                if(kind==Kind.HISTORY||kind==Kind.PENDING)for(JsonObject operation:operations(kind,object))verifyOperation(operation);
                if(kind==Kind.DOCUMENT&&(integer(object.get("schema"))!=1||!Set.of("schema","settings","scenarios","biomes","zones","spawn").containsAll(object.keySet())))throw new IOException("Invalid document snapshot schema");
                return object;
            }
        }catch(RuntimeException failure){throw new IOException("Invalid scenario metadata "+path,failure);}
    }
    private static JsonElement read(JsonReader reader,int depth,int[] nodes)throws IOException {
        if(depth>MAX_DEPTH||++nodes[0]>MAX_NODES)throw new IOException("Scenario JSON nesting/node budget exceeded");
        return switch(reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();JsonObject object=new JsonObject();Set<String> names=new HashSet<>();
                while(reader.hasNext()){String name=reader.nextName();if(!names.add(name))throw new IOException("Duplicate JSON metadata key");object.add(name,read(reader,depth+1,nodes));}
                reader.endObject();yield object;
            }
            case BEGIN_ARRAY -> {reader.beginArray();JsonArray array=new JsonArray();while(reader.hasNext())array.add(read(reader,depth+1,nodes));reader.endArray();yield array;}
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> JsonParser.parseString(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {reader.nextNull();yield JsonNull.INSTANCE;}
            default -> throw new IOException("Unexpected scenario JSON token");
        };
    }
    private static boolean walk(Kind kind,JsonObject root,Map<String,String> ids,Set<String> refs)throws IOException {
        try {
            return switch(kind) {
                case AGGREGATE -> aggregate(root,ids,refs);
                case SCENARIO -> scenario(root,ids,refs);
                case DOCUMENT -> root.has("scenarios")?aggregate(root.getAsJsonObject("scenarios"),ids,refs):false;
                case CURSOR -> {
                    JsonElement value=root.get("document");if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IOException("Replay cursor document missing");
                    JsonObject document=parse(SCENARIOS+"cursor.json",value.getAsString().getBytes(StandardCharsets.UTF_8));
                    boolean changed=scenario(document,ids,refs);if(changed)root.addProperty("document",COMPACT.toJson(document));yield changed;
                }
                default -> false;
            };
        }catch(RuntimeException failure){throw new IOException("Unsupported scenario definition metadata",failure);}
    }
    private static boolean aggregate(JsonObject root,Map<String,String> ids,Set<String> refs)throws IOException {
        if(integer(root.get("schema"))!=1||!root.has("devices")||!root.get("devices").isJsonObject()||!root.has("scenarios")||!root.get("scenarios").isJsonArray())throw new IOException("Invalid scenario aggregate");
        JsonArray array=root.getAsJsonArray("scenarios");if(array.size()>128)throw new IOException("Too many scenario definitions");
        boolean changed=false;for(JsonElement row:array)changed|=scenario(row.getAsJsonObject(),ids,refs);return changed;
    }
    private static boolean scenario(JsonObject root,Map<String,String> ids,Set<String> refs)throws IOException {
        int schema=integer(root.get("schema"));if(!Set.of(2,3,4).contains(schema))throw new IOException("Unsupported scenario schema");
        // Native schema2 migration builds a fresh scenario and intentionally ignores its old replay field.
        if(schema==2||!root.has("replay")||root.get("replay").isJsonNull())return false;
        JsonObject replay=root.getAsJsonObject("replay");
        if(!replay.has("baselineVersionId")||!replay.has("checkpoints")||!replay.get("checkpoints").isJsonArray())throw new IOException("Invalid replay definition");
        boolean changed=remap(replay,"baselineVersionId",true,ids,refs);JsonArray points=replay.getAsJsonArray("checkpoints");if(points.size()>32)throw new IOException("Too many replay checkpoints");
        for(JsonElement value:points)changed|=remap(value.getAsJsonObject(),"versionId",false,ids,refs);
        return changed;
    }
    private static boolean remap(JsonObject object,String field,boolean allowEmpty,Map<String,String> ids,Set<String> refs)throws IOException {
        JsonElement value=object.get(field);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IOException("Invalid scenario version reference");
        String before=value.getAsString();if(before.isEmpty()&&allowEmpty)return false;version(before);refs.add(before);if(ids==null)return false;
        String after=ids.get(before);if(after==null)throw new IOException("Unresolved scenario dependency "+before);if(after.equals(before))return false;
        object.addProperty(field,after);return true;
    }
    private static List<JsonObject> operations(Kind kind,JsonObject root)throws IOException {
        if(kind==Kind.PENDING)return List.of(root);
        if(integer(root.get("schema"))!=2)throw new IOException("Invalid operation history schema");
        List<JsonObject> result=new ArrayList<>();
        for(String name:List.of("undo","redo")) {
            if(!root.has(name)||!root.get(name).isJsonArray())throw new IOException("Invalid operation stack");
            for(JsonElement operation:root.getAsJsonArray(name)){result.add(operation.getAsJsonObject());if(result.size()>MAX_OPERATIONS)throw new IOException("Too many saved history operations");}
        }return result;
    }
    private static void verifyOperation(JsonObject operation)throws IOException {
        if(integer(operation.get("schema"))!=2||!operation.has("name")||!operation.get("name").isJsonPrimitive()||!operation.has("changes")||!operation.get("changes").isJsonArray()
                ||operation.getAsJsonArray("changes").size()>131072||operation.has("entities")&&(!operation.get("entities").isJsonArray()||operation.getAsJsonArray("entities").size()>32))throw new IOException("Invalid operation metadata");
        for(String field:List.of("documentsBefore","documentsAfter"))if(operation.has(field))reference(operation.get(field));
        if(operation.has("bodySha256")) {
            String expected=reference(operation.get("bodySha256"));if(!bodyHash(operation).equals(expected))throw new IOException("Operation body SHA-256 mismatch");
        }
    }
    private static String reference(JsonElement value)throws IOException {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||!value.getAsString().matches("[0-9a-f]{64}"))throw new IOException("Invalid immutable document reference");return value.getAsString();
    }
    private static int integer(JsonElement value)throws IOException {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IOException("Expected numeric metadata schema");
        double number=value.getAsDouble();if(!Double.isFinite(number)||number!=Math.rint(number)||number<0||number>Integer.MAX_VALUE)throw new IOException("Invalid metadata schema");return (int)number;
    }
    private static void version(String value)throws IOException {
        if(value==null||!value.matches("[0-9a-f-]{36}"))throw new IOException("Invalid scenario version ID");
        try{if(!UUID.fromString(value).toString().equals(value))throw new IOException("Noncanonical scenario version ID");}catch(IllegalArgumentException failure){throw new IOException("Invalid scenario version ID",failure);}
    }
    private static String bodyHash(JsonObject operation) {
        JsonObject selected=new JsonObject();for(String key:BODY)if(operation.has(key))selected.add(key,operation.get(key));
        return hash(COMPACT.toJson(canonical(selected)).getBytes(StandardCharsets.UTF_8));
    }
    private static JsonElement canonical(JsonElement value) {
        if(value.isJsonObject()){JsonObject sorted=new JsonObject();for(String key:new TreeSet<>(value.getAsJsonObject().keySet()))sorted.add(key,canonical(value.getAsJsonObject().get(key)));return sorted;}
        if(value.isJsonArray()){JsonArray array=new JsonArray();for(JsonElement item:value.getAsJsonArray())array.add(canonical(item));return array;}return value;
    }
    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
    }
}
