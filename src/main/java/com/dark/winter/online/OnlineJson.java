// Bounded, duplicate-key rejecting wire JSON.
package com.dark.winter.online;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Bounded, duplicate-key rejecting wire JSON. No polymorphic deserialization or network/file side effects. */
public final class OnlineJson {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private OnlineJson() {}
    public static JsonObject object(byte[] bytes, int limit) throws IOException {
        if(bytes==null||bytes.length>limit)throw new IOException("Сообщение превышает допустимый размер");
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw new IOException("Некорректный UTF-8",invalid);}
        return object(text,limit);
    }
    public static JsonObject object(String text,int limit)throws IOException {return parse(text,limit,16384,2048);}
    /** Larger only for authenticated, byte-bounded world state; directory requests retain their smaller limits. */
    public static JsonObject state(String text,int limit)throws IOException {return parse(text,limit,196608,8192);}
    private static JsonObject parse(String text,int limit,int nodeLimit,int arrayLimit)throws IOException {
        if(text==null||text.length()>limit||text.getBytes(StandardCharsets.UTF_8).length>limit)
            throw new IOException("Сообщение превышает допустимый размер");
        try(JsonReader reader=new JsonReader(new StringReader(text))){
            reader.setStrictness(Strictness.STRICT);int[] nodes={0};
            JsonElement value=read(reader,0,nodes,nodeLimit,arrayLimit);
            if(reader.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject())throw new IOException("Ожидается один JSON-объект");
            return value.getAsJsonObject();
        }catch(IllegalStateException|NumberFormatException invalid){throw new IOException("Некорректный JSON",invalid);}
    }
    private static JsonElement read(JsonReader reader,int depth,int[] nodes,int nodeLimit,int arrayLimit)throws IOException {
        if(depth>24||++nodes[0]>nodeLimit)throw new IOException("Слишком сложное сообщение");
        return switch(reader.peek()){
            case BEGIN_OBJECT -> {
                JsonObject object=new JsonObject();reader.beginObject();int count=0;
                while(reader.hasNext()){
                    String key=reader.nextName();unicode(key);
                    if(key.length()>128||++count>256||object.has(key))throw new IOException("Повтор или слишком много полей");
                    object.add(key,read(reader,depth+1,nodes,nodeLimit,arrayLimit));
                }reader.endObject();yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array=new JsonArray();reader.beginArray();
                while(reader.hasNext()){if(array.size()>=arrayLimit)throw new IOException("Слишком длинный список");array.add(read(reader,depth+1,nodes,nodeLimit,arrayLimit));}
                reader.endArray();yield array;
            }
            case STRING -> {
                String value=reader.nextString();unicode(value);
                if(value.length()>262144)throw new IOException("Слишком длинная строка");yield new JsonPrimitive(value);
            }
            case NUMBER -> {
                String value=reader.nextString();
                if(value.length()>80)throw new IOException("Слишком длинное число");
                BigDecimal number=new BigDecimal(value);
                if(Math.abs((long)number.scale())>4096||number.precision()>80)throw new IOException("Число вне допустимого диапазона");
                yield new JsonPrimitive(number);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {reader.nextNull();yield JsonNull.INSTANCE;}
            default -> throw new IOException("Некорректное значение JSON");
        };
    }
    public static void unicode(String text)throws IOException {
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))throw new IOException("Некорректная Unicode-строка");}
            else if(Character.isLowSurrogate(c))throw new IOException("Некорректная Unicode-строка");
        }
    }
    public static String string(JsonObject object,String key,int max)throws IOException {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IOException("Отсутствует строка: "+key);
        String text=value.getAsString();unicode(text);if(text.length()>max)throw new IOException("Слишком длинное поле: "+key);return text;
    }
    public static String optional(JsonObject object,String key,String fallback,int max)throws IOException {
        return object.has(key)?string(object,key,max):fallback;
    }
    public static long integer(JsonObject object,String key,long min,long max)throws IOException {
        JsonElement value=object.get(key);
        try{
            if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IOException("Отсутствует число: "+key);
            long number=value.getAsBigDecimal().longValueExact();
            if(number<min||number>max)throw new IOException("Число вне диапазона: "+key);return number;
        }catch(ArithmeticException|NumberFormatException invalid){throw new IOException("Ожидается целое число: "+key,invalid);}
    }
    public static boolean bool(JsonObject object,String key,boolean fallback)throws IOException {
        if(!object.has(key))return fallback;JsonElement value=object.get(key);
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IOException("Ожидается переключатель: "+key);
        return value.getAsBoolean();
    }
    public static JsonObject child(JsonObject object,String key)throws IOException {
        JsonElement value=object.get(key);if(value==null||!value.isJsonObject())throw new IOException("Отсутствует объект: "+key);return value.getAsJsonObject();
    }
    public static void keys(JsonObject object,String... allowed)throws IOException {
        Set<String> keys=Set.of(allowed);for(String key:object.keySet())if(!keys.contains(key))throw new IOException("Неизвестное поле: "+key);
    }
    public static byte[] bytes(JsonElement value){return GSON.toJson(value).getBytes(StandardCharsets.UTF_8);}
    public static JsonObject error(String message){JsonObject result=new JsonObject();result.addProperty("ok",false);result.addProperty("message",message);return result;}
    public static JsonObject ok(){JsonObject result=new JsonObject();result.addProperty("ok",true);return result;}
}
