package com.dark.winter.core;
import java.util.*;

public final class CoreJson {
    private CoreJson(){}
    public static String text(Object value) {
        if(value==null)return "null";
        if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map) {
            var items=new ArrayList<String>();
            map.forEach((key,item)->items.add(text(String.valueOf(key))+":"+text(item)));
            return "{"+String.join(",",items)+"}";
        }
        if(value instanceof Iterable<?> list) {
            var items=new ArrayList<String>();for(Object item:list)items.add(text(item));
            return "["+String.join(",",items)+"]";
        }
        String input=value.toString();StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<input.length();i++) {
            char c=input.charAt(i);
            if(c=='"'||c=='\\')out.append('\\').append(c);
            else if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
