// Loads bounded model-aligned outline data during resource reload, never in the frame loop.
package com.dark.winter.client.highlight;

import com.google.gson.*;
import java.io.Reader;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Outline coordinates are client assets; selection, collision and reach remain native. */
final class HighlightResources {
    private static final Logger LOG=LoggerFactory.getLogger("DarkWinter/Highlight");
    static final String ROOT="darkwinter/highlights";
    record Prepared(Map<BlockState,float[]> states,int definitions) {}
    private HighlightResources(){}
    static Prepared load(ResourceManager manager) {
        var jsons=new TreeMap<Identifier,JsonObject>();
        manager.listResources(ROOT,path->path.getPath().endsWith(".json")).forEach((path,resource)->{
            try(Reader reader=resource.openAsReader()){
                String name=path.getPath().substring(ROOT.length()+1,path.getPath().length()-5);
                jsons.put(Identifier.fromNamespaceAndPath(path.getNamespace(),name),JsonParser.parseReader(reader).getAsJsonObject());
            }catch(Exception invalid){LOG.warn("Ignoring invalid model highlight resource {}",path,invalid);}
        });
        var shapes=new HashMap<Identifier,float[]>();
        jsons.forEach((id,json)->{if(json.has("lines"))try{shapes.put(id,readLines(json.getAsJsonArray("lines")));}catch(RuntimeException invalid){LOG.warn("Ignoring invalid highlight shape {}",id,invalid);}});
        Map<BlockState,float[]> states=new IdentityHashMap<>();int definitions=0;
        for(var entry:jsons.entrySet()){
            var variants=entry.getValue().getAsJsonObject("variants");if(variants==null)continue;
            var block=BuiltInRegistries.BLOCK.getOptional(entry.getKey()).orElse(null);if(block==null)continue;
            try{
                var accepted=new IdentityHashMap<BlockState,float[]>();
                if(variants.size()>512)throw new IllegalArgumentException("Too many outline variants");
                for(var variant:variants.entrySet()){
                    var spec=variant.getValue();Identifier shapeId;JsonObject transform=null;
                    if(spec.isJsonPrimitive())shapeId=Identifier.parse(spec.getAsString());
                    else{transform=spec.getAsJsonObject();shapeId=Identifier.parse(transform.get("highlight").getAsString());}
                    float[] shape=shapes.get(shapeId);if(shape==null)throw new IllegalArgumentException("Missing outline "+shapeId);
                    float[] lines=transformed(shape,transform);
                    var required=new HashMap<Property<?>,Comparable<?>>();
                    if(!variant.getKey().isEmpty())for(String clause:variant.getKey().split(",")){
                        String[] pair=clause.split("=",2);if(pair.length!=2)throw new IllegalArgumentException("Invalid state variant");
                        Property<?> property=block.getStateDefinition().getProperty(pair[0]);
                        if(property==null)throw new IllegalArgumentException("Unknown property "+pair[0]);
                        Comparable<?> value=property.getValue(pair[1]).orElseThrow(()->new IllegalArgumentException("Unknown value "+pair[1]));
                        required.put(property,value);
                    }
                    for(BlockState state:block.getStateDefinition().getPossibleStates()){
                        boolean matches=true;for(var condition:required.entrySet())if(!Objects.equals(state.getValue(condition.getKey()),condition.getValue())){matches=false;break;}
                        if(matches)accepted.put(state,lines);
                    }
                }
                states.putAll(accepted);definitions++;
            }catch(RuntimeException invalid){LOG.warn("Ignoring invalid highlight definition {}",entry.getKey(),invalid);}
        }
        LOG.info("Model highlights: {} definitions, {} block states",definitions,states.size());
        return new Prepared(Collections.unmodifiableMap(states),definitions);
    }
    private static float[] readLines(JsonArray json){
        if(json.isEmpty()||json.size()>2048)throw new IllegalArgumentException("Outline line count");
        float[] lines=new float[json.size()*6];int at=0;
        for(JsonElement element:json){JsonArray line=element.getAsJsonArray();if(line.size()!=6)throw new IllegalArgumentException("Six coordinates required");
            for(var coordinate:line){float value=coordinate.getAsFloat();if(!Float.isFinite(value)||Math.abs(value)>16)throw new IllegalArgumentException("Invalid outline coordinate");lines[at++]=value;}}
        return lines;
    }
    private static float[] transformed(float[] source,JsonObject spec){
        if(spec==null)return source;
        int turns=0;JsonObject rotation=spec.getAsJsonObject("rotation");
        if(rotation!=null){for(String axis:rotation.keySet())if(!axis.equals("y"))throw new IllegalArgumentException("Only vertical outline rotations supported");
            if(rotation.has("y")){int angle=rotation.get("y").getAsInt();if(angle%90!=0)throw new IllegalArgumentException("Quarter rotation required");turns=Math.floorMod(angle/90,4);}}
        float tx=0,ty=0,tz=0;JsonArray translation=spec.getAsJsonArray("translation");
        if(translation!=null){if(translation.size()!=3)throw new IllegalArgumentException("Translation requires three coordinates");tx=translation.get(0).getAsFloat();ty=translation.get(1).getAsFloat();tz=translation.get(2).getAsFloat();}
        if(!Float.isFinite(tx+ty+tz)||Math.max(Math.abs(tx),Math.max(Math.abs(ty),Math.abs(tz)))>16)throw new IllegalArgumentException("Invalid outline translation");
        if(turns==0&&tx==0&&ty==0&&tz==0)return source;
        float[] output=source.clone();
        for(int i=0;i<output.length;i+=3){float x=source[i]-.5f,z=source[i+2]-.5f;
            for(int t=0;t<turns;t++){float old=x;x=-z;z=old;}
            output[i]=x+.5f+tx;output[i+1]=source[i+1]+ty;output[i+2]=z+.5f+tz;
        }return output;
    }
}
