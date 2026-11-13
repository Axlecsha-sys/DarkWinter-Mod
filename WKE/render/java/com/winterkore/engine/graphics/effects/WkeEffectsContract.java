// Executable graph validation and quality contract; no Minecraft, Fabric, GPU or JSON-library dependency.
package com.winterkore.engine.graphics.effects;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WkeEffectsContract {
    public record Quality(String id, int shadowResolution, float shadowRadius, int volumeSteps) {
        public Quality {
            if (id == null || id.isBlank() || shadowResolution < 16 || shadowResolution > 8192
                    || !Float.isFinite(shadowRadius) || shadowRadius <= 0 || volumeSteps < 1 || volumeSteps > 128)
                throw new IllegalArgumentException("Invalid WKE effects quality");
        }
    }
    public record Input(String sampler, String target, boolean depth) { }
    public record Pass(String shader, String output, List<Input> inputs, Map<String,List<String>> uniforms) {
        public Pass { inputs=List.copyOf(inputs); uniforms=Map.copyOf(uniforms); }
    }
    public record Graph(String id, Set<String> internalTargets, List<Pass> passes) {
        public Graph { internalTargets=Set.copyOf(internalTargets); passes=List.copyOf(passes); }
    }
    public record Plan(String id, List<String> shaders, Set<String> requiredExternalTargets,
                       boolean fused, boolean antialiasing) {
        public Plan { shaders=List.copyOf(shaders); requiredExternalTargets=Set.copyOf(requiredExternalTargets); }
    }
    private final String namespace;
    private final List<String> standard, fused;
    private final Map<String,String> shaderOverrides;
    private final Map<String,Quality> qualities;
    private final Map<String,List<String>> implementedEffects;

    public WkeEffectsContract(String namespace, String block, int bytes, List<String> vectors,
            List<String> standard, List<String> fused, Map<String,String> shaderOverrides, List<Quality> qualities,
            Map<String,List<String>> implementedEffects) {
        if (namespace == null || namespace.isBlank() || !WkeSceneUniforms.BLOCK.equals(block)
                || bytes != WkeSceneUniforms.BYTES || !WkeSceneUniforms.VECTORS.equals(vectors))
            throw new IllegalArgumentException("WKE scene ABI disagrees with the authoritative effect contract");
        this.namespace=namespace; this.standard=List.copyOf(standard); this.fused=List.copyOf(fused);
        this.shaderOverrides=Map.copyOf(shaderOverrides);
        Map<String,Quality> profiles=new LinkedHashMap<>();
        for (Quality quality:qualities)
            if (profiles.put(quality.id(),quality)!=null) throw new IllegalArgumentException("Duplicate WKE quality "+quality.id());
        if (!profiles.keySet().equals(Set.of("low","balanced","high"))) throw new IllegalArgumentException("WKE requires three implemented quality profiles");
        this.qualities=Map.copyOf(profiles);
        Map<String,List<String>> implementations=new LinkedHashMap<>();
        implementedEffects.forEach((id,passes)-> {
            if(passes.isEmpty() || passes.stream().noneMatch(pass->this.standard.contains(pass)||this.fused.contains(pass)))
                throw new IllegalArgumentException("WKE capability has no implemented graph pass: "+id);
            implementations.put(id,List.copyOf(passes));
        });
        this.implementedEffects=Map.copyOf(implementations);
        for (List<String> order:List.of(this.standard,this.fused)) {
            if (order.isEmpty() || !"film".equals(order.getLast()) || order.indexOf("antialias")!=order.size()-2)
                throw new IllegalArgumentException("WKE AA must write ungraded history immediately before film");
        }
    }
    public Quality quality(String id) {
        Quality value=qualities.get(id);
        if (value==null) throw new IllegalArgumentException("Unknown WKE effects quality "+id);
        return value;
    }
    public List<String> implementedEffects() { return implementedEffects.keySet().stream().sorted().toList(); }
    public String graphId(String quality, boolean fused) {
        quality(quality);
        return namespace+":"+quality+(fused?"_fused":"");
    }
    private String shader(String pass) { return shaderOverrides.getOrDefault(pass,namespace+":post/"+pass); }

    /** Validates exactly the graph that the host is about to execute, including resource-pack overrides. */
    public Plan validate(Graph graph, boolean useFused, Set<String> importedTargets) {
        List<String> order=useFused?fused:standard;
        List<String> expected=order.stream().map(this::shader).toList();
        List<String> actual=graph.passes().stream().map(Pass::shader).toList();
        if (!expected.equals(actual)) throw new IllegalArgumentException("WKE graph pass order mismatch: "+graph.id()+" "+actual);
        Set<String> known=new HashSet<>(importedTargets); known.addAll(graph.internalTargets());
        Set<String> available=new HashSet<>(importedTargets);
        Set<String> external=new HashSet<>();
        for (Pass pass:graph.passes()) {
            if (!known.contains(pass.output())) throw new IllegalArgumentException("Unknown WKE graph output "+pass.output());
            for (Input input:pass.inputs()) {
                if (!known.contains(input.target()) || !available.contains(input.target()))
                    throw new IllegalArgumentException("WKE graph reads unavailable target "+input.target()+" in "+pass.shader());
                if (importedTargets.contains(input.target())) external.add(input.target());
            }
            if (pass.shader().equals(shader("antialias"))) {
                if (!List.of("Controls").equals(pass.uniforms().get("WkeAntialias")))
                    throw new IllegalArgumentException("WKE AA requires its independent 16-byte Controls block");
                if (!pass.output().equals(namespace+":sky_final")
                        || pass.inputs().isEmpty() || !pass.inputs().getFirst().target().equals("wke:temporal_current"))
                    throw new IllegalArgumentException("WKE history must receive AA output before grade/film");
            } else if (pass.shader().equals(shader("temporal_resolve"))) {
                if (!List.of("CurrentInv0","CurrentInv1","CurrentInv2","CurrentInv3","Previous0","Previous1","Previous2","Previous3",
                        "CameraDeltaHistory","ResolveControl","JitterControl").equals(pass.uniforms().get("WkeTemporalResolve"))
                        || !pass.output().equals("wke:temporal_current"))
                    throw new IllegalArgumentException("WKE temporal resolve requires its independent 176-byte block and owned current target");
                if (!pass.inputs().stream().map(Input::sampler).toList().equals(
                        List.of("Main","Depth","History","HistoryDepth","Reactive","HistoryReactive"))
                        || !pass.inputs().get(2).target().equals("wke:temporal_history")
                        || !pass.inputs().get(3).target().equals("wke:temporal_history")
                        || !pass.inputs().get(3).depth()
                        || !pass.inputs().get(4).target().equals("wke:temporal_reactive")
                        || pass.inputs().get(4).depth()
                        || !pass.inputs().get(5).target().equals("wke:temporal_reactive_history")
                        || pass.inputs().get(5).depth())
                    throw new IllegalArgumentException("WKE temporal history requires its same-epoch current/previous reactive masks");
            } else if (!WkeSceneUniforms.VECTORS.equals(pass.uniforms().get(WkeSceneUniforms.BLOCK))) {
                throw new IllegalArgumentException("WKE scene UBO order differs in "+pass.shader());
            }
            available.add(pass.output());
        }
        return new Plan(graph.id(),actual,external,useFused,true);
    }
}
