// Extends existing foliage only where it meets the inset side of a thin trunk.
package com.dark.winter.client;

import com.dark.winter.block.ThinTrunks;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Extends existing foliage only where it meets the inset side of a thin trunk. */
public final class ThinTreeModels {
    public static final int BRANCH_SNOW_TAG = 1;
    public static final int BRANCH_WOOD_TAG = 2;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float BOUNDARY_EPSILON = 0.00001F;

    private ThinTreeModels() {}

    public static void init() {
        ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register((model, context) -> {
            if (context.state().is(Blocks.SPRUCE_LEAVES)) return new SpruceBranchesModel(model,
                    context.baker().materials().get(new Material(Identifier.withDefaultNamespace("block/snow")),
                            () -> "DarkWinter snow on spruce branches"),
                    context.baker().materials().get(new Material(Identifier.withDefaultNamespace("block/spruce_log")),
                            () -> "DarkWinter spruce branch bark"));
            if (context.state().is(Blocks.SNOW)) return new SpruceSnowModel(model);
            if (context.state().is(Blocks.SPRUCE_LOG) && context.state().getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y)
                return new SpruceTopLogModel(model);
            return context.state().getBlock() instanceof LeavesBlock ? new ConnectingLeavesModel(model) : model;
        }));
    }

    /** Bits use Direction.ordinal(); exposed for the real client geometry checks. */
    public static int connectionMask(BlockAndTintGetter level, BlockPos pos) {
        int connections = 0;
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (Direction direction : DIRECTIONS) {
            BlockState neighbor = level.getBlockState(neighborPos.setWithOffset(pos, direction));
            if (ThinTrunks.isThin(neighbor)
                    && neighbor.getValue(RotatedPillarBlock.AXIS) != direction.getAxis()) {
                connections |= 1 << direction.ordinal();
            }
        }
        return connections;
    }

    private static float extend(float coordinate, int connections, Direction negative, Direction positive) {
        if (Math.abs(coordinate) <= BOUNDARY_EPSILON && (connections & (1 << negative.ordinal())) != 0) {
            return -ThinTrunks.INSET;
        }
        if (Math.abs(coordinate - 1.0F) <= BOUNDARY_EPSILON && (connections & (1 << positive.ordinal())) != 0) {
            return 1.0F + ThinTrunks.INSET;
        }
        return coordinate;
    }

    private static boolean connectQuad(MutableQuadView quad, int connections) {
        boolean changed = false;
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = quad.x(vertex);
            float y = quad.y(vertex);
            float z = quad.z(vertex);
            float nextX = extend(x, connections, Direction.WEST, Direction.EAST);
            float nextY = extend(y, connections, Direction.DOWN, Direction.UP);
            float nextZ = extend(z, connections, Direction.NORTH, Direction.SOUTH);
            if (nextX != x || nextY != y || nextZ != z) {
                quad.pos(vertex, nextX, nextY, nextZ);
                changed = true;
            }
        }
        if (changed) {
            // The extended strips lie outside the original block face and cannot use its occlusion test.
            quad.cullFace(null);
        }
        return true;
    }

    private static final class ConnectingLeavesModel extends WrapperBlockStateModel {
        private ConnectingLeavesModel(BlockStateModel wrapped) {
            super(wrapped);
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                              RandomSource random, Predicate<@Nullable Direction> cullTest) {
            int connections = connectionMask(level, pos);
            if (connections == 0) {
                wrapped.emitQuads(emitter, level, pos, state, random, cullTest);
                return;
            }
            emitter.pushTransform(quad -> connectQuad(quad, connections));
            try {
                // Keep all source quads until the transform decides which cull faces are still valid.
                // Delegating preserves the original materials, foliage tint and Fancy/Fast rendering.
                wrapped.emitQuads(emitter, level, pos, state, random, face -> false);
            } finally {
                emitter.popTransform();
            }
        }

        @Override
        public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state,
                                                   RandomSource random) {
            Object sourceKey = wrapped.createGeometryKey(level, pos, state, random);
            return sourceKey == null ? null : new GeometryKey(sourceKey, connectionMask(level, pos),
                    Minecraft.getInstance().options.cutoutLeaves().get());
        }
    }

    private record GeometryKey(Object sourceKey, int connections, boolean cutoutLeaves) {}

    /** The last natural log ends in a bark-covered point, never an exposed square sawn surface. */
    private static final class SpruceTopLogModel extends WrapperBlockStateModel {
        private SpruceTopLogModel(BlockStateModel wrapped) { super(wrapped); }

        private boolean naturalTop(BlockAndTintGetter level, BlockPos pos) {
            BlockState above = level.getBlockState(pos.above());
            if (above.is(Blocks.SPRUCE_LEAVES)) return !above.getValue(LeavesBlock.PERSISTENT);
            if (!above.isAir() && !above.is(Blocks.SNOW)) return false;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockState neighbor = level.getBlockState(pos.relative(side));
                if (neighbor.is(Blocks.SPRUCE_LEAVES) && !neighbor.getValue(LeavesBlock.PERSISTENT)) return true;
            }
            return false;
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                              RandomSource random, Predicate<@Nullable Direction> cullTest) {
            if (!naturalTop(level, pos)) {
                wrapped.emitQuads(emitter, level, pos, state, random, cullTest);
                return;
            }
            emitter.pushTransform(quad -> {
                boolean top = true;
                for (int vertex = 0; vertex < 4; vertex++) top &= quad.y(vertex) >= 1.0F - BOUNDARY_EPSILON;
                if (top) return false;
                for (int vertex = 0; vertex < 4; vertex++) if (quad.y(vertex) > 0.5F) {
                    // Degenerate top edge gives four genuine triangular bark faces with no top cut face.
                    quad.pos(vertex, 0.5F, 1.06F, 0.5F);
                    quad.cullFace(null);
                }
                return true;
            });
            try { wrapped.emitQuads(emitter, level, pos, state, random, face -> false); }
            finally { emitter.popTransform(); }
        }

        @Override
        public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
            Object original = wrapped.createGeometryKey(level, pos, state, random);
            return original == null ? null : new SpruceTopKey(original, naturalTop(level, pos));
        }
    }

    private static final class SpruceBranchesModel extends WrapperBlockStateModel {
        private final Material.Baked snowMaterial;
        private final Material.Baked barkMaterial;

        private SpruceBranchesModel(BlockStateModel wrapped, Material.Baked snowMaterial, Material.Baked barkMaterial) {
            super(wrapped);
            this.snowMaterial = snowMaterial;
            this.barkMaterial = barkMaterial;
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                              RandomSource random, Predicate<@Nullable Direction> cullTest) {
            int snowLayers = snowLayers(level.getBlockState(pos.above()));
            var shape = SpruceBranches.shape(level, pos, state);
            for (var branch : shape.branches()) {
                if (branch.woody()) emitBranchWood(emitter, barkMaterial, branch);
                emitBranch(emitter, particleMaterial(), branch, shape);
                if (snowLayers != 0) emitBranchSnow(emitter, snowMaterial, branch, snowLayers);
            }
        }

        @Override
        public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
            return new BranchKey(wrapped, SpruceBranches.shape(level, pos, state), snowLayers(level.getBlockState(pos.above())));
        }
    }

    private static final class SpruceSnowModel extends WrapperBlockStateModel {
        private SpruceSnowModel(BlockStateModel wrapped) { super(wrapped); }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                              RandomSource random, Predicate<@Nullable Direction> cullTest) {
            if (!level.getBlockState(pos.below()).is(Blocks.SPRUCE_LEAVES)) {
                wrapped.emitQuads(emitter, level, pos, state, random, cullTest);
            }
        }

        @Override
        public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
            return level.getBlockState(pos.below()).is(Blocks.SPRUCE_LEAVES)
                    ? new HiddenSnowKey(wrapped)
                    : wrapped.createGeometryKey(level, pos, state, random);
        }
    }

    private static int snowLayers(BlockState above) {
        return above.is(Blocks.SNOW) ? above.getValue(SnowLayerBlock.LAYERS) : 0;
    }

    private static void emitBranchSnow(QuadEmitter emitter, Material.Baked material, SpruceBranches.Branch branch, int layers) {
        float dx = branch.endX() - branch.x(), dz = branch.endZ() - branch.z();
        float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
        // Snow settles on the upper boughs, not on the near-vertical hanging sprays.
        if (horizontal < 0.1F || Math.abs(branch.endY() - branch.y()) >= horizontal * 0.7F) return;
        float width = branch.width() * (0.25F + layers * 0.025F);
        float sx = -dz / horizontal * width, sz = dx / horizontal * width;
        float lift = 0.018F + layers * 0.003F;
        for (int half = 0; half < 2; half++) {
            float start = half == 0 ? 0.18F : 0.50F, end = half == 0 ? 0.50F : 0.82F;
            float startWidth = half == 0 ? 0.20F : 0.70F, endWidth = half == 0 ? 0.70F : 0.10F;
            emitter.pos(0, branch.xAt(start) - sx * startWidth, branch.yAt(start) + lift, branch.zAt(start) - sz * startWidth);
            emitter.pos(1, branch.xAt(start) + sx * startWidth, branch.yAt(start) + lift, branch.zAt(start) + sz * startWidth);
            emitter.pos(2, branch.xAt(end) + sx * endWidth, branch.yAt(end) + lift, branch.zAt(end) + sz * endWidth);
            emitter.pos(3, branch.xAt(end) - sx * endWidth, branch.yAt(end) + lift, branch.zAt(end) - sz * endWidth);
            emitter.uvUnitSquare();
            emitter.materialBake(material, MutableQuadView.BAKE_NORMALIZED);
            emitter.tag(BRANCH_SNOW_TAG).cullFace(null).ambientOcclusion(TriState.FALSE).shadeDirectionOverride(Direction.UP).emit();
        }
    }

    private static void emitBranchWood(QuadEmitter emitter, Material.Baked material, SpruceBranches.Branch branch) {
        float dx = branch.endX() - branch.x(), dy = branch.endY() - branch.y(), dz = branch.endZ() - branch.z();
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= length; dy /= length; dz /= length;
        float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
        float tx = horizontal < 0.1F ? 1.0F : -dz / horizontal, tz = horizontal < 0.1F ? 0 : dx / horizontal;
        float bx = dy * tz, by = dz * tx - dx * tz, bz = -dy * tx;
        float radius = Math.clamp(branch.width() * 0.085F, 0.014F, 0.041F);
        for (int face = 0; face < 4; face++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                int corner = (face + (vertex == 0 || vertex == 3 ? 1 : 0)) & 3;
                float tangent = corner == 0 || corner == 1 ? -1 : 1;
                float bitangent = corner == 0 || corner == 3 ? -1 : 1;
                boolean tip = vertex >= 2;
                float taper = tip ? 0.22F : 1;
                emitter.pos(vertex, (tip ? branch.endX() : branch.x()) + (tangent * tx + bitangent * bx) * radius * taper,
                        (tip ? branch.endY() : branch.y()) + bitangent * by * radius * taper,
                        (tip ? branch.endZ() : branch.z()) + (tangent * tz + bitangent * bz) * radius * taper);
                emitter.uv(vertex, vertex == 1 || vertex == 2 ? 4 : 0, tip ? 16 : 0);
            }
            emitter.materialBake(material, 0);
            emitter.tag(BRANCH_WOOD_TAG).cullFace(null).ambientOcclusion(TriState.FALSE).emit();
        }
    }

    private static void emitBranch(QuadEmitter emitter, Material.Baked material, SpruceBranches.Branch branch,
                                   SpruceBranches.Shape shape) {
        float dx = branch.endX() - branch.x(), dy = branch.endY() - branch.y(), dz = branch.endZ() - branch.z();
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= length;
        dy /= length;
        dz /= length;
        float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
        float tangentX = horizontal < 0.1F ? 1.0F : -dz / horizontal;
        float tangentZ = horizontal < 0.1F ? 0.0F : dx / horizontal;
        for (int plane = 0; plane < (branch.crossed() ? 2 : 1); plane++) {
            float sx = (plane == 0 ? tangentX : dy * tangentZ) * branch.width();
            // Fill the vertical gaps between whorls while preserving the exact horizontal silhouette and roots.
            float sy = (plane == 0 ? 0 : dz * tangentX - dx * tangentZ) * branch.width() * 1.4F;
            float sz = (plane == 0 ? tangentZ : -dy * tangentX) * branch.width();
            // Terrain backface culling requires both windings; the transparent sprite supplies the needle silhouette.
            for (int back = 0; back < 2; back++) {
                for (int vertex = 0; vertex < 4; vertex++) {
                    int corner = back == 0 ? vertex : 3 - vertex;
                    boolean tip = corner == 1 || corner == 2;
                    float side = corner < 2 ? -1 : 1;
                    float taper = tip ? 0.68F : 1;
                    emitter.pos(vertex,
                            Math.clamp((tip ? branch.endX() : branch.x()) + side * sx * taper, shape.minimumX(), shape.maximumX()),
                            Math.clamp((tip ? branch.endY() : branch.y()) + side * sy * taper, shape.minimumY(), shape.maximumY()),
                            Math.clamp((tip ? branch.endZ() : branch.z()) + side * sz * taper, shape.minimumZ(), shape.maximumZ()));
                    emitter.uv(vertex, side < 0 ? 0 : 1, tip ? SpruceBranches.TIP_V : SpruceBranches.ROOT_V);
                }
                emitter.materialBake(material, MutableQuadView.BAKE_NORMALIZED);
                // Sprays need alpha holes even when the general graphics setting asks for solid cube leaves.
                emitter.chunkLayer(ChunkSectionLayer.CUTOUT).tintIndex(0).cullFace(null)
                        .ambientOcclusion(TriState.FALSE).emit();
            }
        }
    }

    private record BranchKey(Object sourceKey, SpruceBranches.Shape shape, int snowLayers) {}
    private record SpruceTopKey(Object sourceKey, boolean naturalTop) {}
    private record HiddenSnowKey(Object sourceKey) {}
}
