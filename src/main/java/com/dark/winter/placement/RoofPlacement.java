package com.dark.winter.placement;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.RoofSegmentBlock;
import com.dark.winter.placement.SurfaceGeometry.Hit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Fits the authored R3 shake profiles together in world space. */
final class RoofPlacement {
    private static final double EDGE_EPSILON = .001;
    // Roofs originally placed on floors can differ by the 0.1/16 underside offset.
    private static final double COURSE_EPSILON = .02;
    private static final double MAX_SUPPORT_DISTANCE = 2.5;
    private static final double FLUE_RADIUS = .94 / 16;

    record Join(Vec3 origin, Direction facing, int span) {
        static Join atFootprint(Vec3 min, Direction facing, int span) {
            Direction across = facing.getClockWise();
            double inset = across.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? 1 - span * .25 : 0;
            return new Join(min.add(across.getStepX() * inset, 0, across.getStepZ() * inset), facing, span);
        }

        double min(Direction.Axis axis) {
            Direction across = facing.getClockWise();
            return origin.get(axis) + (across.getAxis() == axis
                    && across.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? 1 - span * .25 : 0);
        }

        double max(Direction.Axis axis) {
            return min(axis) + (facing.getAxis() == axis ? 1 : span * .25);
        }
    }

    private record Section(Join geometry, Profile profile) {}

    private enum Profile {
        SLOPE(false), RIGHT_GABLE(false), CHIMNEY(false), RIGHT_CHIMNEY(false), RIDGE(true), GABLE_PEAK(true);

        final boolean cap;

        Profile(boolean cap) {
            this.cap = cap;
        }

        boolean chimney() {
            return this == CHIMNEY || this == RIGHT_CHIMNEY;
        }
    }

    private enum Flue { NONE, STOVE, PIPE, CHIMNEY }

    private RoofPlacement() {}

    static boolean applies(Block candidate, Level level, BlockPos support) {
        return isRoof(candidate) && level.hasChunkAt(support)
                && profile(level.getBlockState(support).getBlock()) != null;
    }

    static boolean isRoof(Block block) {
        return profile(block) != null;
    }

    static boolean validFlueContact(Level level, Block candidate, BlockState support, @Nullable Hit contact) {
        Flue placing = flue(candidate);
        Flue below = flue(support.getBlock());
        if (placing == Flue.CHIMNEY && (below == Flue.STOVE || below == Flue.PIPE)) {
            return onFlueEnd(level, support, contact, Direction.UP);
        }
        if (placing != Flue.PIPE) return true;
        return switch (below) {
            case STOVE -> onFlueEnd(level, support, contact, Direction.UP);
            case PIPE -> onFlueEnd(level, support, contact, Direction.UP)
                    || onFlueEnd(level, support, contact, Direction.DOWN);
            case CHIMNEY -> support.getValue(RoofSegmentBlock.SPAN) == RoofSegmentBlock.FULL_SPAN
                    && onFlueEnd(level, support, contact, Direction.DOWN);
            case NONE -> true;
        };
    }

    static boolean appliesToFlue(Block candidate, BlockState support, Hit contact) {
        Flue below = flue(support.getBlock());
        return flue(candidate) == Flue.CHIMNEY && contact.face() == Direction.UP
                && (below == Flue.STOVE || below == Flue.PIPE);
    }

    static List<Join> fromFlue(Level level, Block candidate, Hit contact, Direction preferred) {
        BlockState support = level.getBlockState(contact.owner());
        Vec3 origin = flueOrigin(level, support, contact.owner()).add(0, 1, 0);
        var aligned = aboveSupport(level, candidate, origin.add(.5, 0, .5)).stream()
                .filter(join -> join.span == RoofSegmentBlock.FULL_SPAN
                        && Math.abs(join.origin.x - origin.x) <= EDGE_EPSILON
                        && Math.abs(join.origin.z - origin.z) <= EDGE_EPSILON
                        && Math.abs(join.origin.y - origin.y) <= COURSE_EPSILON)
                .map(join -> new Join(origin, join.facing, RoofSegmentBlock.FULL_SPAN))
                .distinct().toList();
        return aligned.isEmpty() ? List.of(new Join(origin, preferred, RoofSegmentBlock.FULL_SPAN)) : aligned;
    }

    private static boolean onFlueEnd(Level level, BlockState state, @Nullable Hit contact, Direction face) {
        if (contact == null || contact.face() != face) return false;
        Vec3 origin = flueOrigin(level, state, contact.owner());
        double endY = origin.y + (face == Direction.UP ? 1 : 0);
        double dx = contact.point().x - (origin.x + .5);
        double dz = contact.point().z - (origin.z + .5);
        double radius = FLUE_RADIUS + EDGE_EPSILON;
        return Math.abs(contact.point().y - endY) <= EDGE_EPSILON && dx * dx + dz * dz <= radius * radius;
    }

    private static Vec3 flueOrigin(Level level, BlockState state, BlockPos member) {
        FurnitureBlock block = (FurnitureBlock) state.getBlock();
        return Vec3.atLowerCornerOf(block.origin(member, state)).add(AnchorRuntime.offset(level, member));
    }

    private static Flue flue(Block block) {
        if (!(block instanceof FurnitureBlock furniture)) return Flue.NONE;
        return switch (furniture.definition().id()) {
            case "cabin_wood_stove" -> Flue.STOVE;
            case "cabin_stovepipe" -> Flue.PIPE;
            case "cabin_roof_chimney", "cabin_roof_chimney_left", "cabin_roof_chimney_right" -> Flue.CHIMNEY;
            default -> Flue.NONE;
        };
    }

    static List<Join> fromRoof(Level level, Block candidate, BlockState support, Hit contact) {
        Section clicked = section(level, contact.owner(), support);
        Profile profile = profile(candidate);
        Collection<Section> sections = nearbySections(level, contact.point());
        List<Direction> edges = new ArrayList<>(List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST));
        edges.sort(Comparator.<Direction>comparingDouble(edge -> distanceToEdge(clicked.geometry, contact.point(), edge))
                .thenComparing(edge -> edge != contact.face()));
        // An explicit end face names one slot. A click on a sloping face can see more than one edge.
        boolean endFace = contact.face().getAxis().isHorizontal()
                && distanceToEdge(clicked.geometry, contact.point(), contact.face()) <= EDGE_EPSILON;
        if (endFace) edges = List.of(contact.face());
        var candidates = new ArrayList<Join>(edges.size());
        for (Direction edge : edges) {
            Join join = neighbor(clicked, profile, edge, sections);
            if (join != null) candidates.add(join);
        }
        return candidates;
    }

    /** Locate the roof cell directly above the surface seen through a gap. */
    static List<Join> aboveSupport(Level level, Block candidate, Vec3 point) {
        Profile profile = profile(candidate);
        Collection<Section> sections = nearbySections(level, point);
        var candidates = new HashMap<Join, Integer>();
        for (Section section : sections) {
            for (Direction edge : Direction.Plane.HORIZONTAL) {
                Join join = neighbor(section, profile, edge, sections);
                if (join == null || point.x < join.min(Direction.Axis.X) || point.x >= join.max(Direction.Axis.X)
                        || point.z < join.min(Direction.Axis.Z) || point.z >= join.max(Direction.Axis.Z)) continue;
                double height = surfaceHeight(join, profile, point) - point.y;
                if (height >= -EDGE_EPSILON && height <= MAX_SUPPORT_DISTANCE) candidates.merge(join, 1, Integer::sum);
            }
        }
        return candidates.keySet().stream().sorted(Comparator.<Join>comparingInt(candidates::get).reversed()
                .thenComparingDouble(join -> surfaceHeight(join, profile, point) - point.y)
                .thenComparingInt(join -> join.facing.ordinal()).thenComparingDouble(join -> join.origin.x)
                .thenComparingDouble(join -> join.origin.z).thenComparingInt(Join::span)).toList();
    }

    private static Collection<Section> nearbySections(Level level, Vec3 point) {
        var sections = new HashMap<BlockPos, Section>();
        var visited = new HashSet<BlockPos>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(point.x - 2.01, point.y - 1.1, point.z - 2.01),
                BlockPos.containing(point.x + 2.01, point.y + MAX_SUPPORT_DISTANCE + 1, point.z + 2.01))) {
            collectSection(level, cell.immutable(), visited, sections);
            for (BlockPos owner : AnchorRuntime.logicalOwners(level, cell)) collectSection(level, owner, visited, sections);
        }
        return sections.values();
    }

    private static void collectSection(Level level, BlockPos member, HashSet<BlockPos> visited, Map<BlockPos, Section> sections) {
        if (!visited.add(member) || !level.hasChunkAt(member)) return;
        BlockState state = level.getBlockState(member);
        if (!isRoof(state.getBlock())) return;
        FurnitureBlock block = (FurnitureBlock) state.getBlock();
        BlockPos root = block.origin(member, state);
        if (!level.hasChunkAt(root) || !level.getBlockState(root).is(block)) return;
        sections.computeIfAbsent(root, ignored -> section(level, member, state));
    }

    private static Section section(Level level, BlockPos member, BlockState state) {
        FurnitureBlock block = (FurnitureBlock) state.getBlock();
        var geometry = new Join(Vec3.atLowerCornerOf(block.origin(member, state)).add(AnchorRuntime.offset(level, member)),
                state.getValue(FurnitureBlock.FACING), state.getValue(RoofSegmentBlock.SPAN));
        return new Section(geometry, profile(block));
    }

    private static double edgePosition(Join join, Direction edge) {
        return edge.getAxisDirection() == Direction.AxisDirection.POSITIVE ? join.max(edge.getAxis()) : join.min(edge.getAxis());
    }

    private static double distanceToEdge(Join join, Vec3 point, Direction edge) {
        return Math.abs(point.get(edge.getAxis()) - edgePosition(join, edge));
    }

    private static double surfaceHeight(Join join, Profile profile, Vec3 point) {
        Direction rise = join.facing.getOpposite();
        double distance = distanceToEdge(join, point, rise.getOpposite());
        return join.origin.y + (profile.cap ? Math.min(distance, 1 - distance) : distance);
    }

    private static Join neighbor(Section existing, Profile candidateProfile, Direction edge, Collection<Section> sections) {
        Join source = existing.geometry;
        Direction facing = source.facing;
        Direction across = facing.getClockWise();
        Direction rise = facing.getOpposite();
        Profile existingProfile = existing.profile;

        // A slope rises one block over its footprint. The cap has two equally low ends.
        int height = 0;
        if (edge.getAxis() != facing.getAxis()) {
            if (existingProfile.cap != candidateProfile.cap) return null;
            if (candidateProfile == Profile.GABLE_PEAK && edge == across) facing = facing.getOpposite();
            int span = spaceAcross(existing, edge, sections);
            if (candidateProfile.chimney() && span != RoofSegmentBlock.FULL_SPAN) return null;
            double boundary = edgePosition(source, edge);
            double start = edge.getAxisDirection() == Direction.AxisDirection.POSITIVE ? boundary : boundary - span * .25;
            Vec3 min = edge.getAxis() == Direction.Axis.X ? new Vec3(start, source.origin.y, source.origin.z)
                    : new Vec3(source.origin.x, source.origin.y, start);
            return Join.atFootprint(min, facing, span);
        } else if (existingProfile.cap) {
            if (candidateProfile.cap) return null;
            height = -1;
            if (edge == rise) facing = facing.getOpposite();
        } else if (edge == rise) {
            height = 1;
            // The peak's gable is authored on the left. Its roof fits in either orientation.
            if (candidateProfile == Profile.GABLE_PEAK
                    && (existingProfile == Profile.RIGHT_GABLE || existingProfile == Profile.RIGHT_CHIMNEY)) {
                facing = facing.getOpposite();
            }
        } else {
            if (candidateProfile.cap) return null;
            height = -1;
        }
        Vec3 min = new Vec3(source.min(Direction.Axis.X) + edge.getStepX(), source.origin.y + height,
                source.min(Direction.Axis.Z) + edge.getStepZ());
        if (candidateProfile.chimney() && source.span != RoofSegmentBlock.FULL_SPAN) return null;
        return Join.atFootprint(min, facing, source.span);
    }

    private static int spaceAcross(Section existing, Direction edge, Collection<Section> sections) {
        Join source = existing.geometry;
        Direction.Axis along = source.facing.getAxis();
        double boundary = edgePosition(source, edge);
        double length = 1;
        for (Section other : sections) {
            Join neighbor = other.geometry;
            boolean samePitch = existing.profile.cap ? neighbor.facing.getAxis() == along : neighbor.facing == source.facing;
            if (!samePitch || existing.profile.cap != other.profile.cap
                    || Math.abs(neighbor.origin.y - source.origin.y) > COURSE_EPSILON
                    || Math.abs(neighbor.min(along) - source.min(along)) > EDGE_EPSILON) continue;
            double gap = (edgePosition(neighbor, edge.getOpposite()) - boundary) * edge.getAxisDirection().getStep();
            if (gap >= -EDGE_EPSILON) length = Math.min(length, Math.max(0, gap));
        }
        int span = (int) Math.round(length * RoofSegmentBlock.FULL_SPAN);
        return span > 0 && Math.abs(span * .25 - length) <= EDGE_EPSILON ? span : RoofSegmentBlock.FULL_SPAN;
    }

    static BlockPos storage(BlockPlaceContext context, FurnitureBlock block, Join join) {
        Level level = context.getLevel();
        var player = context.getPlayer();
        return AnchorStorage.find(join.origin(), root -> {
            for (int part = 0; part < block.definition().parts().size(); part++) {
                BlockPos member = root.offset(block.partOffset(part, join.facing()));
                if (!level.hasChunkAt(member) || !level.isInWorldBounds(member)
                        || !level.getWorldBorder().isWithinBounds(member)
                        || !level.getBlockState(member).isAir()) return false;
                if (player != null && (!level.mayInteract(player, member)
                        || !player.mayUseItemAt(member, context.getClickedFace(), context.getItemInHand()))) return false;
            }
            return true;
        });
    }

    static boolean matches(BlockPlaceContext context, BlockState state, Join join) {
        if (state.getValue(FurnitureBlock.FACING) != join.facing() || state.getValue(FurnitureBlock.PART) != 0
                || state.getValue(RoofSegmentBlock.SPAN) != join.span()) return false;
        var properties = context.getItemInHand().get(DataComponents.BLOCK_STATE);
        if (properties == null) return true;
        BlockState placed = properties.apply(state);
        return placed.getValue(FurnitureBlock.FACING) == join.facing()
                && placed.getValue(FurnitureBlock.PART) == 0 && placed.getValue(RoofSegmentBlock.SPAN) == join.span();
    }

    private static Profile profile(Block block) {
        if (!(block instanceof FurnitureBlock furniture)) return null;
        return switch (furniture.definition().id()) {
            case "r3_shake_roof_slope", "r3_shake_roof_eave", "r3_shake_roof_gable_left",
                    "cabin_roof_eave", "cabin_roof_eave_left", "cabin_roof_gable_left" -> Profile.SLOPE;
            case "r3_shake_roof_gable_right", "cabin_roof_eave_right", "cabin_roof_gable_right" -> Profile.RIGHT_GABLE;
            case "cabin_roof_chimney", "cabin_roof_chimney_left" -> Profile.CHIMNEY;
            case "cabin_roof_chimney_right" -> Profile.RIGHT_CHIMNEY;
            case "r3_shake_roof_ridge" -> Profile.RIDGE;
            case "r3_shake_roof_gable_peak", "cabin_roof_gable_peak" -> Profile.GABLE_PEAK;
            default -> null;
        };
    }
}
