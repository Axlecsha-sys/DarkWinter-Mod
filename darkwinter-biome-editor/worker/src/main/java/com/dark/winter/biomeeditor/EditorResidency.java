// Checks client chunk residency and native-selected terrain uploads before a preview frame is published.
package com.dark.winter.biomeeditor;

import com.dark.winter.biomeeditor.mixin.EditorOcclusionAccess;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

public final class EditorResidency {
    private static final Gson JSON = new Gson();
    private static final String SCOPE = "Client FULL chunks intersecting the rendered camera frustum; "
            + "native-selected non-clipped section meshes and their draw allocations. "
            + "Outside-frustum and occluded/unselected sections are not certified as compiled.";
    private EditorResidency() {}

    public record Snapshot(boolean ready, String reason, int requestedChunks, int frustumChunks,
            int residentChunks, int missingVisibleChunks, int missingHeightmaps, int surfaceCandidates,
            int surfaceNotNativeVisible, int missingViewSections, int visibleSections, int compiledEmptySections,
            int uncompiledSections, int drawLayers, int missingSlices, int pendingUploads, int fadingSections,
            int queuedCompiles, int freeCompileBuffers, boolean graphPending, boolean graphFailed,
            int outsideFrustumChunks, int cutY, int minSurfaceY, int maxSurfaceY, List<String> examples, String scope) {
        public Snapshot { examples = List.copyOf(examples); }
        public JsonObject json() { return JSON.toJsonTree(this).getAsJsonObject(); }
    }

    /** Call after the native frame on its render owner; never requests chunks or starts compilation. */
    public static Snapshot inspect(Minecraft c, BiomeEditorProfile profile, int cutY) {
        Counts n = new Counts(profile.chunks() * profile.chunks(), cutY);
        if (!c.isSameThread() || !RenderSystem.isOnRenderThread()) return n.result("not_render_owner");
        if (c.level == null || c.levelRenderer == null || c.gameRenderer == null) return n.result("no_client_world");
        var renderer = c.levelRenderer;
        var state = c.gameRenderer.gameRenderState();
        var view = renderer.viewArea();
        var dispatcher = renderer.sectionRenderDispatcher();
        // 26.3 declares readyForLevelRendering but never assigns it; residency uses actual renderer owners.
        if (!state.shouldRenderLevel || view == null || dispatcher == null) {
            n.example("shouldRenderLevel=" + state.shouldRenderLevel + ", view=" + (view != null) + ", dispatcher=" + (dispatcher != null));
            return n.result("native_renderer_not_ready");
        }
        var frustum = state.levelRenderState.cameraRenderState.cullFrustum;
        int minimum = c.level.getMinY(), maximum = c.level.getMaxY(), clipped = Math.min(cutY, maximum);
        n.cutY = clipped;
        var graph = (EditorOcclusionAccess) renderer.sectionOcclusionGraph();
        Future<?> task = graph.editor$fullUpdateTask();
        n.graphFailed = task != null && (task.isCancelled()
                || task instanceof CompletableFuture<?> future && future.isCompletedExceptionally());
        n.graphPending = graph.editor$needsFullUpdate() || task != null && !task.isDone()
                || graph.editor$needsFrustumUpdate().get();
        n.queuedCompiles = dispatcher.getCompileQueueSize();
        n.freeCompileBuffers = dispatcher.getFreeBufferCount();
        var membership = new IdentityHashMap<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection, Boolean>();
        for (var section : renderer.visibleSections()) membership.put(section, Boolean.TRUE);

        for (int dz = 0; dz < profile.chunks(); dz++) for (int dx = 0; dx < profile.chunks(); dx++) {
            int cx = profile.chunkX() + dx, cz = profile.chunkZ() + dz, x = cx * 16, z = cz * 16;
            ChunkAccess chunk = c.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk != null) n.residentChunks++;
            boolean inFrustum = clipped >= minimum
                    && frustum.isVisible(new AABB(x, minimum, z, x + 16, clipped + 1, z + 16));
            if (!inFrustum) { n.outsideFrustumChunks++; continue; }
            n.frustumChunks++;
            if (chunk == null) { n.missingVisibleChunks++; n.example(cx + "," + cz + ":missing_client_chunk"); continue; }
            // getHeight() would prime a missing map. Reading only transmitted maps keeps this inspection read-only.
            if (!chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)
                    || !chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING)) {
                n.missingHeightmaps++; n.example(cx + "," + cz + ":missing_transmitted_heightmap"); continue;
            }
            Heightmap.Types groundMap = chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)
                    ? Heightmap.Types.MOTION_BLOCKING_NO_LEAVES : Heightmap.Types.MOTION_BLOCKING;
            int low = maximum, high = minimum - 1;
            for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                low = Math.min(low, chunk.getHeight(groundMap, lx, lz));
                high = Math.max(high, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz));
            }
            n.minSurfaceY = Math.min(n.minSurfaceY, low); n.maxSurfaceY = Math.max(n.maxSurfaceY, high);
            if (high < minimum || clipped < minimum) continue;
            // Include the first subsurface section and a cut plane; never demand the entire solid underground column.
            int first = SectionPos.blockToSectionCoord(Math.max(minimum, Math.min(low, clipped) - 16));
            int last = SectionPos.blockToSectionCoord(Math.min(high, clipped));
            for (int sy = first; sy <= last; sy++) {
                if (chunk.getSection(chunk.getSectionIndexFromSectionY(sy)).hasOnlyAir()) continue;
                int y = sy * 16;
                AABB box = new AABB(x, y, z, x + 16, Math.min(y + 16, clipped + 1), z + 16);
                if (!frustum.isVisible(box)) continue;
                n.surfaceCandidates++;
                var section = view.getRenderSectionAt(new BlockPos(x, y, z));
                if (section == null || section.getSectionNode() != SectionPos.asLong(cx, sy, cz)) {
                    n.missingViewSections++; n.example(cx + "," + sy + "," + cz + ":outside_native_view_area");
                } else if (!membership.containsKey(section)) n.surfaceNotNativeVisible++;
            }
        }

        long now = Util.getMillis();
        long fade = Util.toMillis(state.optionsRenderState.chunkSectionFadeInTime);
        // Match LevelRenderer.extractSectionDrawGroups ownership and its custom-index allocation contract.
        dispatcher.lock();
        try {
            for (var section : renderer.visibleSections()) {
                BlockPos origin = section.getRenderOrigin();
                int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
                if (cx < profile.chunkX() || cx >= profile.chunkX() + profile.chunks()
                        || cz < profile.chunkZ() || cz >= profile.chunkZ() + profile.chunks()
                        || origin.getY() > clipped || origin.getY() + 15 < minimum) continue;
                AABB bb = section.getBoundingBox();
                if (!frustum.isVisible(new AABB(bb.minX, bb.minY, bb.minZ, bb.maxX, Math.min(bb.maxY, clipped + 1), bb.maxZ))) continue;
                n.visibleSections++;
                var mesh = section.getSectionMesh();
                if (mesh == CompiledSectionMesh.UNCOMPILED) {
                    n.uncompiledSections++; n.example(origin.getX() + "," + origin.getY() + "," + origin.getZ() + ":uncompiled");
                    continue;
                }
                if (!mesh.hasRenderableLayers()) n.compiledEmptySections++;
                boolean drawable = false;
                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    var draw = mesh.getSectionDraw(layer);
                    if (draw == null) {
                        if (!mesh.isEmpty(layer)) { n.missingSlices++; n.example(origin + ":" + layer + ":missing_draw_metadata"); }
                        continue;
                    }
                    if (draw.indexCount() <= 0) continue;
                    drawable = true; n.drawLayers++;
                    var slice = dispatcher.getRenderSectionSlice(mesh, layer);
                    if (slice == null || slice.vertexBuffer() == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) {
                        n.missingSlices++; n.example(origin + ":" + layer + ":missing_gpu_allocation");
                    }
                    if (mesh instanceof CompiledSectionMesh compiled
                            && (!compiled.isVertexBufferUploaded(layer) || !compiled.isIndexBufferUploaded(layer))) {
                        n.pendingUploads++; n.example(origin + ":" + layer + ":upload_not_published");
                    }
                }
                if (drawable && section.getVisibility(now, fade) < .999f) n.fadingSections++;
            }
        } finally { dispatcher.unlock(); }
        if (n.graphFailed) return n.result("native_visibility_graph_failed");
        if (n.graphPending) return n.result("native_visibility_graph_pending");
        if (n.missingVisibleChunks != 0) return n.result("client_chunks_pending");
        if (n.missingHeightmaps != 0) return n.result("client_heightmaps_pending");
        if (n.missingViewSections != 0) return n.result("preview_exceeds_native_view_area");
        if (n.surfaceCandidates != 0 && (n.visibleSections == 0 || n.drawLayers == 0)) return n.result("native_preview_meshes_not_visible");
        if (n.uncompiledSections != 0) return n.result("native_visible_sections_uncompiled");
        if (n.missingSlices != 0 || n.pendingUploads != 0) return n.result("native_visible_uploads_pending");
        if (n.fadingSections != 0) return n.result("native_visible_fade_pending");
        // Pending work outside the selected preview is diagnostic, not a reason to stall its complete meshes.
        return n.result("ready");
    }

    private static final class Counts {
        int requestedChunks, frustumChunks, residentChunks, missingVisibleChunks, missingHeightmaps, surfaceCandidates;
        int surfaceNotNativeVisible, missingViewSections, visibleSections, compiledEmptySections, uncompiledSections;
        int drawLayers, missingSlices, pendingUploads, fadingSections, queuedCompiles, freeCompileBuffers, outsideFrustumChunks, cutY;
        int minSurfaceY = Integer.MAX_VALUE, maxSurfaceY = Integer.MIN_VALUE;
        boolean graphPending, graphFailed;
        final List<String> examples = new ArrayList<>();
        Counts(int requested, int cut) { requestedChunks = requested; cutY = cut; }
        void example(String value) { if (examples.size() < 12) examples.add(value); }
        Snapshot result(String reason) {
            return new Snapshot(reason.equals("ready"), reason, requestedChunks, frustumChunks, residentChunks,
                    missingVisibleChunks, missingHeightmaps, surfaceCandidates, surfaceNotNativeVisible, missingViewSections,
                    visibleSections, compiledEmptySections, uncompiledSections, drawLayers, missingSlices, pendingUploads,
                    fadingSections, queuedCompiles, freeCompileBuffers, graphPending, graphFailed, outsideFrustumChunks,
                    cutY, minSurfaceY == Integer.MAX_VALUE ? 0 : minSurfaceY,
                    maxSurfaceY == Integer.MIN_VALUE ? 0 : maxSurfaceY, examples, SCOPE);
        }
    }
}
