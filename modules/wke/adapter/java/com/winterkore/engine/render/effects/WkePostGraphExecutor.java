// Minecraft execution adapter for the shared WKE effect graph and authoritative resource contract.
package com.winterkore.engine.render.effects;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.winterkore.engine.graphics.effects.WkeEffectsContract;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;

public final class WkePostGraphExecutor {
    private static final String CONTRACT_RESOURCE = "/assets/wke/effects/pipeline_contract.json";
    private static final class Catalog {
        static final WkeEffectsContract CONTRACT = readContract();
    }
    private final Map<String,WkeEffectsContract.Plan> plans=new HashMap<>();
    private int epoch=Integer.MIN_VALUE;
    private long submittedGraphs, submittedPasses, rejectedGraphs;
    private WkeEffectsContract.Plan lastPlan;
    private String failure="";

    /** Quality dimensions and ray budgets are shared data, not Minecraft menu constants. */
    public static WkeEffectsContract.Quality quality(String id) { return Catalog.CONTRACT.quality(id); }

    public void execute(ShaderManager shaders, RenderTarget main, Map<Identifier,RenderTarget> external,
            String quality, boolean fused, GraphicsResourceAllocator allocator, int resourceEpoch) {
        String graphName=Catalog.CONTRACT.graphId(quality,fused);
        if (epoch!=resourceEpoch) { plans.clear(); epoch=resourceEpoch; }
        Identifier id=Identifier.parse(graphName);
        Set<Identifier> externalIds=Set.copyOf(external.keySet());
        WkeEffectsContract.Plan plan=plans.get(graphName);
        if (plan==null) {
            try {
                var resource=Identifier.fromNamespaceAndPath(id.getNamespace(),"post_effect/"+id.getPath()+".json");
                try (Reader reader=Minecraft.getInstance().getResourceManager().getResourceOrThrow(resource).openAsReader()) {
                    var declaration=readGraph(graphName,JsonParser.parseReader(reader).getAsJsonObject());
                    Set<String> names=new HashSet<>();
                    externalIds.forEach(target->names.add(target.toString()));
                    plan=Catalog.CONTRACT.validate(declaration,fused,names);
                    plans.put(graphName,plan);
                }
            } catch (Exception problem) {
                rejectedGraphs++; failure=problem.toString();
                throw new IllegalStateException("Invalid WKE effect graph "+graphName,problem);
            }
        }
        PostChain chain=shaders.getPostChain(id,externalIds);
        if (chain==null) {
            rejectedGraphs++; failure="Shader manager could not compile "+graphName;
            throw new IllegalStateException(failure);
        }
        FrameGraphBuilder graph=new FrameGraphBuilder();
        Map<Identifier,ResourceHandle<RenderTarget>> targets=new HashMap<>();
        external.forEach((name,target)->targets.put(name,graph.importExternal("WKE effect "+name,target)));
        PostChain.TargetBundle bundle=new PostChain.TargetBundle() {
            @Override public void replace(Identifier name,ResourceHandle<RenderTarget> target) { targets.put(name,target); }
            @Override public ResourceHandle<RenderTarget> get(Identifier name) { return targets.get(name); }
        };
        chain.addToFrame(graph,main.width,main.height,bundle);
        graph.execute(allocator);
        // These describe actual host graph submissions, not completed GPU executions or timings.
        lastPlan=plan; submittedGraphs++; submittedPasses+=plan.shaders().size(); failure="";
    }

    public void invalidate() { plans.clear(); epoch=Integer.MIN_VALUE; lastPlan=null; }
    public Map<String,Object> statistics() {
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("contractResource",CONTRACT_RESOURCE);
        values.put("sceneBytes",com.winterkore.engine.graphics.effects.WkeSceneUniforms.BYTES);
        values.put("implementedEffects",Catalog.CONTRACT.implementedEffects());
        values.put("validatedGraphs",plans.size());
        values.put("submittedGraphs",submittedGraphs);
        values.put("hostSubmittedPasses",submittedPasses);
        values.put("rejectedGraphs",rejectedGraphs);
        values.put("resourceEpoch",epoch);
        values.put("lastGraph",lastPlan==null?"":lastPlan.id());
        values.put("lastSubmittedPassShaderIds",lastPlan==null?List.of():lastPlan.shaders());
        values.put("lastSubmissionAntialias",lastPlan!=null&&lastPlan.antialiasing());
        values.put("lastFailure",failure);
        return Map.copyOf(values);
    }

    private static WkeEffectsContract readContract() {
        try (var stream=WkePostGraphExecutor.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (stream==null) throw new IllegalStateException("Missing authoritative WKE effects contract");
            JsonObject root=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt()!=1) throw new IllegalArgumentException("Unsupported WKE effects contract version");
            Map<String,String> overrides=new HashMap<>();
            root.getAsJsonObject("shader_overrides").entrySet().forEach(e->overrides.put(e.getKey(),e.getValue().getAsString()));
            List<WkeEffectsContract.Quality> qualities=new ArrayList<>();
            root.getAsJsonObject("qualities").entrySet().forEach(entry-> {
                JsonObject q=entry.getValue().getAsJsonObject();
                qualities.add(new WkeEffectsContract.Quality(entry.getKey(),q.get("shadow_resolution").getAsInt(),
                    q.get("shadow_radius").getAsFloat(),q.get("volume_steps").getAsInt()));
            });
            // Real packaged implementations must exist before a capability can be advertised.
            for (JsonElement item:root.getAsJsonArray("required_resources")) {
                String path="/assets/"+item.getAsString().replace(':','/');
                try(var implementation=WkePostGraphExecutor.class.getResourceAsStream(path)) {
                    if(implementation==null) throw new IllegalArgumentException("Missing WKE effect implementation "+path);
                }
            }
            Map<String,List<String>> effects=new LinkedHashMap<>();
            root.getAsJsonObject("features").entrySet().forEach(entry->effects.put(entry.getKey(),strings(entry.getValue().getAsJsonArray())));
            return new WkeEffectsContract(root.get("namespace").getAsString(),root.get("block").getAsString(),
                root.get("bytes").getAsInt(),strings(root.getAsJsonArray("vec4_order")),
                strings(root.getAsJsonArray("pass_order")),strings(root.getAsJsonArray("fused_pass_order")),overrides,qualities,effects);
        } catch (Exception problem) { throw new IllegalStateException("Cannot load WKE effects contract",problem); }
    }
    private static WkeEffectsContract.Graph readGraph(String id,JsonObject root) {
        Set<String> targets=new HashSet<>(root.getAsJsonObject("targets").keySet());
        List<WkeEffectsContract.Pass> passes=new ArrayList<>();
        for (JsonElement item:root.getAsJsonArray("passes")) {
            JsonObject p=item.getAsJsonObject();
            List<WkeEffectsContract.Input> inputs=new ArrayList<>();
            for(JsonElement entry:p.getAsJsonArray("inputs")) {
                JsonObject input=entry.getAsJsonObject();
                if(input.has("target")) inputs.add(new WkeEffectsContract.Input(input.get("sampler_name").getAsString(),
                    input.get("target").getAsString(),input.has("use_depth_buffer")&&input.get("use_depth_buffer").getAsBoolean()));
            }
            Map<String,List<String>> uniforms=new LinkedHashMap<>();
            p.getAsJsonObject("uniforms").entrySet().forEach(entry-> {
                List<String> names=new ArrayList<>();
                entry.getValue().getAsJsonArray().forEach(field->names.add(field.getAsJsonObject().get("name").getAsString()));
                uniforms.put(entry.getKey(),List.copyOf(names));
            });
            passes.add(new WkeEffectsContract.Pass(p.get("fragment_shader").getAsString(),p.get("output").getAsString(),inputs,uniforms));
        }
        return new WkeEffectsContract.Graph(id,targets,passes);
    }
    private static List<String> strings(JsonArray values) {
        List<String> result=new ArrayList<>();
        values.forEach(value->result.add(value.getAsString()));
        return List.copyOf(result);
    }
}
