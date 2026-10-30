// Local branch geometry shared by chunk models, their snow caps and the wind detail pass.
package com.dark.winter.client;

import com.dark.winter.block.ThinTrunks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Local branch geometry shared by chunk models, their snow caps and the wind detail pass. */
public final class SpruceBranches {
    // The source image has a transparent outer row; pin an opaque stem texel to both geometric endpoints.
    public static final float ROOT_V = 62.5F / 64.0F;
    public static final float TIP_V = 1.5F / 64.0F;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Direction[] ROOT_ORDER = {Direction.NORTH, Direction.EAST, Direction.SOUTH,
            Direction.WEST, Direction.DOWN, Direction.UP};

    private SpruceBranches() {}

    public record Branch(float x, float y, float z, float endX, float endY, float endZ, float width,
                         boolean crossed, boolean woody) {
        private Branch(float x, float y, float z, float endX, float endY, float endZ, float width) {
            this(x, y, z, endX, endY, endZ, width, false, true);
        }
        public float xAt(float t) { return x + (endX - x) * t; }
        public float yAt(float t) { return y + (endY - y) * t; }
        public float zAt(float t) { return z + (endZ - z) * t; }
    }

    public record Shape(int key) {
        private Direction root() { return DIRECTIONS[key & 7]; }
        private int leaves() { return (key >>> 3) & 63; }
        private int trunks() { return (key >>> 9) & 63; }
        private int insetTrunks() { return (key >>> 15) & 63; }
        private int variant() { return (key >>> 21) & 7; }
        private int distance() { return (key >>> 24) & 7; }
        private int parentDistance() { return (key >>> 27) & 7; }
        public boolean upperTip() {
            return root() == Direction.DOWN && (leaves() & (1 << Direction.UP.ordinal())) == 0
                    && ((leaves() | trunks()) & (1 << Direction.DOWN.ordinal())) != 0;
        }

        // Terrain sections have fixed 16-cube culling bounds. Keep the longer sprays inside their own section;
        // the small existing log-contact overlap is still allowed at its lower/upper edge.
        public float minimumY() {
            return (key & (1 << 30)) != 0 ? -ThinTrunks.INSET - 0.025F : upperTip() ? -0.90F : -0.65F;
        }
        public float maximumY() { return (key & (1 << 31)) != 0 ? 1.0F + ThinTrunks.INSET + 0.025F : 1.60F; }
        public float minimumX() { return lowerBound(Direction.WEST); }
        public float maximumX() { return 1.0F - lowerBound(Direction.EAST); }
        public float minimumZ() { return lowerBound(Direction.NORTH); }
        public float maximumZ() { return 1.0F - lowerBound(Direction.SOUTH); }
        private float lowerBound(Direction side) {
            if (((leaves() | trunks()) & (1 << side.ordinal())) != 0) return -ThinTrunks.INSET - 0.025F;
            return upperTip() ? -0.08F : 0.12F;
        }

        public Branch mainBranch() {
            Direction root = root();
            Direction outward = root.getOpposite();
            boolean rooted = ((leaves() | trunks()) & (1 << root.ordinal())) != 0;
            float reach = rooted ? 0.53F : 0.27F;
            if ((insetTrunks() & (1 << root.ordinal())) != 0) reach = 0.52F + ThinTrunks.INSET;
            float tipReach = (leaves() & (1 << outward.ordinal())) != 0 ? 0.53F : 0.16F;
            float jitter = (variant() - 3.5F) * 0.018F;
            if (root.getAxis().isVertical()) {
                float bottom = 0.5F + root.getStepY() * reach;
                boolean continuing = (leaves() & (1 << outward.ordinal())) != 0;
                float top = 0.5F + outward.getStepY() * (continuing ? 0.53F : root == Direction.DOWN ? 0.74F : tipReach);
                top = Math.min(top, maximumY() - 0.01F);
                return new Branch(0.5F, bottom, 0.5F, 0.5F + (continuing ? 0 : jitter), top,
                        0.5F - (continuing ? 0 : jitter), upperTip() ? 0.39F : 0.46F, true, true);
            }
            float rootY = (trunks() & (1 << root.ordinal())) != 0 ? height(0) : height(parentDistance());
            float tipY = height(distance()) - ((leaves() & (1 << outward.ordinal())) == 0 ? 0.08F : 0);
            return new Branch(0.5F + root.getStepX() * reach, rootY,
                    0.5F + root.getStepZ() * reach,
                    0.5F + outward.getStepX() * tipReach, tipY,
                    0.5F + outward.getStepZ() * tipReach, 0.45F, true, true);
        }

        private static float height(int distance) {
            // Persistent player-built clusters may keep DISTANCE=7; give those a common joining level too.
            return distance == 7 ? 0.50F : 0.74F - distance * 0.08F;
        }

        public List<Branch> branches() {
            Branch main = mainBranch();
            // Needles thrive at the surface. A completely enclosed cell keeps its woody support and one
            // crossed spray, instead of spending nine overlapping branch fans on the invisible tree interior.
            if (leaves() == 63) return List.of(main);
            ArrayList<Branch> result = new ArrayList<>(12);
            result.add(main);
            Direction root = root();
            if (root.getAxis().isVertical()) {
                double angle = variant() * Math.PI / 8.0;
                for (int side = 0; side < 4; side++) {
                    float x = (float) Math.cos(angle + side * Math.PI / 2);
                    float z = (float) Math.sin(angle + side * Math.PI / 2);
                    float variation = forkVariation(side);
                    float at = upperTip() ? 0.30F : 0.55F + variation * 0.035F;
                    float radius = upperTip() ? 0.40F : 0.38F + variation * 0.02F;
                    result.add(new Branch(main.xAt(at), main.yAt(at), main.zAt(at),
                            0.5F + x * radius, upperTip() ? 0.14F : main.yAt(at) - 0.20F + variation * 0.025F,
                            0.5F + z * radius, 0.34F, true, true));
                }
            } else {
                float dx = -root.getStepX(), dz = -root.getStepZ();
                for (int fork = 0; fork < 4; fork++) {
                    int sign = (fork & 1) == 0 ? -1 : 1;
                    float variation = forkVariation(fork);
                    float at = (fork < 2 ? 0.32F : 0.66F) + variation * 0.045F;
                    float sideX = -dz * sign, sideZ = dx * sign;
                    Direction side = sideX > 0 ? Direction.EAST : sideX < 0 ? Direction.WEST
                            : sideZ > 0 ? Direction.SOUTH : Direction.NORTH;
                    boolean continuing = (leaves() & (1 << side.ordinal())) != 0;
                    result.add(new Branch(main.xAt(at), main.yAt(at), main.zAt(at),
                            continuing ? 0.5F + sideX * 0.53F : 0.5F + dx * ((fork < 2 ? -0.14F : 0.13F) + variation * 0.04F) + sideX * (0.265F + variation * 0.015F),
                            continuing ? height(distance()) - 0.05F : main.yAt(at) - (fork < 2 ? 0.14F : 0.32F) + variation * 0.045F,
                            continuing ? 0.5F + sideZ * 0.53F : 0.5F + dz * ((fork < 2 ? -0.14F : 0.13F) + variation * 0.04F) + sideZ * (0.265F + variation * 0.015F),
                            fork < 2 ? 0.34F : 0.30F, true, true));
                }
            }
            if (upperTip()) {
                // The pointed leader and two offset skirts overlap, hiding the log end from every azimuth.
                for (int spray = 0; spray < 6; spray++) {
                    double angle = spray * Math.PI / 3.0;
                    float at = 0.22F + (spray & 1) * 0.08F;
                    result.add(new Branch(main.xAt(at), main.yAt(at), main.zAt(at),
                            0.5F + (float) Math.cos(angle) * 0.51F, minimumY() + 0.06F,
                            0.5F + (float) Math.sin(angle) * 0.51F, 0.36F, true, true));
                }
            } else {
                // Long, staggered lower sprays join adjacent whorls into the sloping outline of a spruce.
                // They remain attached to the woody scaffold instead of floating as repeated leaf blocks.
                for (int fork = 0; fork < 4; fork++) {
                    int side = (fork & 1) == 0 ? -1 : 1;
                    float variation = forkVariation(fork + 2);
                    float at = (fork < 2 ? 0.24F : 0.61F) + variation * 0.05F;
                    float spread = 0.92F + variation * 0.08F;
                    float dx = (root.getAxis().isVertical() ? side * 0.20F : -root.getStepZ() * side * 0.22F) * spread;
                    float dz = (root.getAxis().isVertical() ? -side * 0.20F : root.getStepX() * side * 0.22F) * spread;
                    float tipAt = (fork < 2 ? 0.35F : 0.77F) + variation * 0.05F;
                    result.add(new Branch(main.xAt(at), main.yAt(at), main.zAt(at),
                            main.xAt(tipAt) + dx,
                            fork < 2 ? minimumY() + 0.08F + variation * 0.03F
                                    : Math.max(minimumY() + 0.04F, main.yAt(at) - 0.95F + variation * 0.05F),
                            main.zAt(tipAt) + dz, 0.34F, true, true));
                }
            }
            // A placed leaf may touch several logs, including horizontal logs. Keep every such root attached.
            for (Direction direction : DIRECTIONS) {
                if (direction == root || (trunks() & (1 << direction.ordinal())) == 0) continue;
                float reach = (insetTrunks() & (1 << direction.ordinal())) != 0
                        ? 0.52F + ThinTrunks.INSET : 0.52F;
                result.add(new Branch(0.5F + direction.getStepX() * reach,
                        0.5F + direction.getStepY() * reach, 0.5F + direction.getStepZ() * reach,
                        main.xAt(0.55F), main.yAt(0.55F), main.zAt(0.55F), 0.12F));
            }
            return result;
        }

        private float forkVariation(int fork) { return (((variant() + fork * 3) & 7) - 3.5F) / 3.5F; }

        /** Distinct flexible forks: the distant static canopy remains complete without these near-camera details. */
        public List<Branch> windBranches() {
            Branch main = mainBranch();
            float dx = main.endX() - main.x(), dz = main.endZ() - main.z();
            float horizontal = (float) Math.sqrt(dx * dx + dz * dz);
            if (horizontal < 0.1F) {
                double angle = variant() * Math.PI / 4;
                dx = (float) Math.cos(angle); dz = (float) Math.sin(angle);
            } else { dx /= horizontal; dz /= horizontal; }
            ArrayList<Branch> result = new ArrayList<>(2);
            for (int sign = -1; sign <= 1; sign += 2) {
                float at = sign < 0 ? 0.44F : 0.72F;
                result.add(new Branch(main.xAt(at), main.yAt(at), main.zAt(at),
                        main.xAt(at) + dx * 0.19F - dz * sign * 0.31F,
                        Math.max(minimumY() + 0.15F, main.yAt(at) - 0.43F),
                        main.zAt(at) + dz * 0.19F + dx * sign * 0.31F, 0.29F, true, true));
            }
            return result;
        }
    }

    public static Shape shape(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        BlockState[] neighbors = new BlockState[DIRECTIONS.length];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : DIRECTIONS) neighbors[direction.ordinal()] = level.getBlockState(cursor.setWithOffset(pos, direction));
        return shape(pos, state, neighbors);
    }

    /** The caller supplies exactly the six immediate neighbors, so wind scanning keeps its existing read budget. */
    public static Shape shape(BlockPos pos, BlockState state, BlockState[] neighbors) {
        int leaves = 0, trunks = 0, insetTrunks = 0;
        for (Direction direction : DIRECTIONS) {
            BlockState neighbor = neighbors[direction.ordinal()];
            int bit = 1 << direction.ordinal();
            if (neighbor.is(Blocks.SPRUCE_LEAVES)) leaves |= bit;
            if (ThinTrunks.isThin(neighbor)) {
                trunks |= bit;
                if (neighbor.getValue(RotatedPillarBlock.AXIS) != direction.getAxis()) insetTrunks |= bit;
            }
        }
        int variant = (int) (mix(pos.asLong()) & 7);
        Direction root = Direction.DOWN;
        int best = 100;
        for (int i = 0; i < ROOT_ORDER.length; i++) {
            Direction direction = ROOT_ORDER[i < 4 ? (i + variant) & 3 : i];
            int bit = 1 << direction.ordinal();
            int score;
            if ((trunks & bit) != 0) {
                score = direction.getAxis().isVertical() ? -9 : -10;
            } else if ((leaves & bit) != 0) {
                score = neighbors[direction.ordinal()].getValue(LeavesBlock.DISTANCE) * 2
                        + (direction.getAxis().isVertical() ? 1 : 0);
            } else continue;
            if (score < best) {
                best = score;
                root = direction;
            }
        }
        int distance = state.getValue(LeavesBlock.DISTANCE);
        int parentDistance = (leaves & (1 << root.ordinal())) != 0
                ? neighbors[root.ordinal()].getValue(LeavesBlock.DISTANCE) : 0;
        int sectionLimits = (pos.getY() & 15) == 0 ? 1 << 30 : (pos.getY() & 15) == 15 ? 1 << 31 : 0;
        return new Shape(root.ordinal() | (leaves << 3) | (trunks << 9) | (insetTrunks << 15)
                | (variant << 21) | (distance << 24) | (parentDistance << 27) | sectionLimits);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
