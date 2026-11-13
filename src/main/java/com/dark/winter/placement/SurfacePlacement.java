package com.dark.winter.placement;

import com.dark.winter.block.*;
import com.dark.winter.component.WallShape;
import com.dark.winter.mixin.placement.UseOnContextAccess;
import com.dark.winter.placement.item.PlacedItems;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;

/** Native placement transaction with one physical contact; no client-supplied displacement is trusted. */
public final class SurfacePlacement {
    private static final ThreadLocal<Plan> ACTIVE=new ThreadLocal<>();
    private static final ThreadLocal<Integer> MUTATING=ThreadLocal.withInitial(()->0);
    private SurfacePlacement(){}
    private static boolean eligibleItem(BlockItem item){
        var block=item.getBlock();
        if(block instanceof FurnitureBlock||block instanceof WallSegmentBlock||block instanceof FloorSegmentBlock||block instanceof FrozenWindowBlock)return true;
        var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        if(!id.getNamespace().equals("minecraft"))return false;String name=id.getPath();
        return name.endsWith("_button")||name.endsWith("_pressure_plate")||name.endsWith("_sign")
                ||name.endsWith("_bed")||name.endsWith("_door")||name.endsWith("_trapdoor")||name.endsWith("_shulker_box")
                ||Set.of("torch","soul_torch","redstone_torch","ladder","lever","lantern","soul_lantern",
                    "chest","trapped_chest","ender_chest","barrel","crafting_table","furnace","smoker","blast_furnace",
                    "anvil","chipped_anvil","damaged_anvil","grindstone","stonecutter","cartography_table","smithing_table",
                    "loom","lectern","enchanting_table","brewing_stand","cauldron","bell","flower_pot","decorated_pot",
                    "campfire","soul_campfire","candle","sea_pickle","repeater","comparator","daylight_detector",
                    "jukebox","note_block").contains(name)||name.endsWith("_candle");
    }
    private static final class Plan{
        final Level level;final BlockPos support,base;final Direction face;final Vec3 point;
        final BlockItem item;final boolean looseItem;final Map<BlockPos,BlockState> before=new LinkedHashMap<>();
        BlockPos root;BlockPlaceContext context;BlockState candidate;Vec3 offset;
        Set<BlockPos> positions=new HashSet<>();boolean calculating,validating;
        Direction wallFacing;
        FloorPlacement.Fit floor;
        RoofPlacement.Join roofJoin;
        Vec3 furnitureOrigin;
        Direction furnitureFacing;
        boolean usableSlot = true;
        boolean freeFurniture;
        boolean specialSnap;
        double yaw;
        Plan(Level level,BlockPos support,BlockPos base,BlockPos root,Direction face,Vec3 point,BlockItem item,boolean looseItem){
            this.level=level;this.support=support.immutable();this.base=base.immutable();this.root=root.immutable();
            this.face=face;this.point=point;this.item=item;this.looseItem=looseItem;positions.add(root);
        }
    }
    private static BlockHitResult hit(UseOnContext context){return ((UseOnContextAccess)context).darkwinter$surfaceHit();}
    static boolean nativeFurniture(BlockState state){
        var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if(!id.getNamespace().equals("minecraft"))return false;String name=id.getPath();
        return name.endsWith("_bed")||name.endsWith("_shulker_box")||name.endsWith("_shelf")
                ||Set.of("chest","trapped_chest","ender_chest","barrel","crafting_table","furnace","smoker","blast_furnace",
                    "anvil","chipped_anvil","damaged_anvil","grindstone","stonecutter","cartography_table","smithing_table",
                    "loom","lectern","enchanting_table","brewing_stand","bookshelf","chiseled_bookshelf").contains(name);
    }
    public static boolean eligible(Level level,BlockPos pos){
        if(!level.hasChunkAt(pos))return false;
        BlockState state=level.getBlockState(pos);
        if(state.is(PlacedItems.BLOCK))return false;
        if(nativeFurniture(state))return true;
        if(state.getBlock() instanceof FurnitureBlock)return !AnchorRuntime.rawShape(level,pos,state,true).isEmpty();
        return state.getBlock() instanceof WallSegmentBlock||state.getBlock() instanceof FloorSegmentBlock
                ||state.getBlock() instanceof FrozenWindowBlock||AnchorRuntime.anchor(level,pos)!=null;
    }
    private static SurfaceGeometry.Hit contact(UseOnContext context){
        return contact(context, false);
    }
    private static SurfaceGeometry.Hit contact(UseOnContext context, boolean roofJoint){
        Level level=context.getLevel();BlockHitResult hit=hit(context);BlockPos owner=hit.getBlockPos();
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        Direction face=hit.getDirection();Vec3 n=Vec3.atLowerCornerOf(new BlockPos(face.getStepX(),face.getStepY(),face.getStepZ()));
        if (!level.hasChunkAt(owner)) return null;
        BlockState support = level.getBlockState(owner);
        Block held = context.getItemInHand().getItem() instanceof BlockItem item ? item.getBlock() : null;
        boolean rug = InteriorPlacement.rug(held);
        boolean wallMounted = InteriorPlacement.wallMounted(held);
        boolean ceilingMounted = InteriorPlacement.ceilingMounted(held);
        boolean fittedFloor = (InteriorPlacement.kitchenCabinet(held) || DiningChairPlacement.isChair(held)) && face == Direction.UP
                && InteriorPlacement.floor(level, owner, support);
        boolean coveredFloor = held != null && InteriorPlacement.rug(support.getBlock());
        if (rug && face != Direction.UP) return null;
        if (wallMounted && (!face.getAxis().isHorizontal()
                || !(support.getBlock() instanceof WallSegmentBlock) && !support.isFaceSturdy(level, owner, face))) return null;
        if (ceilingMounted && (face != Direction.DOWN
                || !(support.getBlock() instanceof FloorSegmentBlock || support.getBlock() instanceof SlabBlock
                    || support.isFaceSturdy(level, owner, face)))) return null;
        if (!eligible(level,owner) && !coveredFloor
                && !(rug && InteriorPlacement.floor(level, owner, support))
                && !fittedFloor && !ceilingMounted
                && !(wallMounted && support.isFaceSturdy(level, owner, face))) return null;
        SurfaceGeometry.Hit contact=SurfaceGeometry.hit(level,owner,hit.getLocation().add(n.scale(.25)),hit.getLocation().subtract(n.scale(.25)));
        if (roofJoint && (contact == null || contact.face() != face) && context.getPlayer() != null) {
            // The stepped collision shape can report a different face from the sloping roof mesh.
            Vec3 ray = hit.getLocation().subtract(context.getPlayer().getEyePosition()).normalize().scale(.25);
            SurfaceGeometry.Hit actual = SurfaceGeometry.hit(level, owner, hit.getLocation().subtract(ray), hit.getLocation().add(ray));
            if (actual != null) contact = actual;
        }
        if (contact == null || (!roofJoint && contact.face() != face)) return null;
        if(context.getPlayer()!=null&&contact.point().distanceToSqr(context.getPlayer().getEyePosition())>
                Math.pow(context.getPlayer().blockInteractionRange()+.25,2))return null;
        if (coveredFloor && contact.face() == Direction.UP && !wallMounted)
            return InteriorPlacement.floorBelow(level, contact.point());
        if (rug && !InteriorPlacement.floor(level, owner, support)) return null;
        return contact;
    }
    private static BlockPos freeSlot(Level level,BlockPos base,BlockPlaceContext context){
        if(level.hasChunkAt(base)&&level.getBlockState(base).canBeReplaced(context))return base;
        for(int radius=1;radius<=1;radius++)for(int y=-radius;y<=radius;y++)for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++){
            BlockPos p=base.offset(x,y,z);
            if(level.hasChunkAt(p)&&level.isInWorldBounds(p)&&level.getBlockState(p).isAir())return p;
        }return null;
    }
    private static BlockPlaceContext context(BlockPlaceContext original,Plan plan){
        return new BlockPlaceContext(original){
            @Override public BlockPos getClickedPos(){return plan.root;}
            @Override public Direction getClickedFace(){return plan.face;}
            @Override public boolean canPlace(){return plan.level.getBlockState(plan.root).canBeReplaced(this);}
            @Override public boolean replacingClickedOnBlock(){return false;}
            @Override public Direction getHorizontalDirection(){
                if (plan.roofJoin != null) return plan.roofJoin.facing().getOpposite();
                if (plan.furnitureFacing != null) return plan.furnitureFacing.getOpposite();
                return plan.face.getAxis().isHorizontal()?plan.face.getOpposite():super.getHorizontalDirection();
            }
        };
    }
    private static boolean roofJoint(BlockPlaceContext context, BlockItem item) {
        return RoofPlacement.applies(item.getBlock(), context.getLevel(), hit(context).getBlockPos());
    }

    private static boolean floorMerge(BlockPlaceContext context, BlockItem item) {
        return context.replacingClickedOnBlock() && item.getBlock() instanceof FloorSegmentBlock
                && context.getLevel().getBlockState(context.getClickedPos()).is(item.getBlock());
    }

    private static Plan planBlock(BlockPlaceContext context, BlockItem item, SurfaceGeometry.Hit contact, boolean roofJoint,
                                  boolean floorMerge) {
        Level level = context.getLevel();
        boolean coveredFloor = InteriorPlacement.rug(level.getBlockState(hit(context).getBlockPos()).getBlock())
                && contact.face() == Direction.UP;
        BlockPos base = coveredFloor ? contact.owner().above() : context.getClickedPos();
        Plan plan = new Plan(level, contact.owner(), base, base, contact.face(), contact.point(), item, false);
        plan.context = context;
        BlockState support = level.getBlockState(contact.owner());
        if (!RoofPlacement.validFlueContact(level, item.getBlock(), support, contact)) {
            plan.usableSlot = false;
            return plan;
        }
        if (RoofPlacement.appliesToFlue(item.getBlock(), support, contact)) {
            return planRoof(plan, context, RoofPlacement.fromFlue(level, item.getBlock(), contact,
                    context.getHorizontalDirection().getOpposite()));
        }
        if (roofJoint) {
            return planRoof(plan, context, RoofPlacement.fromRoof(level, item.getBlock(), support, contact));
        }
        if (RoofPlacement.isRoof(item.getBlock()) && contact.face() != Direction.DOWN && constructionSupport(support)) {
            Vec3 aim = contact.point().add(contact.face().getStepX() * .002, 0, contact.face().getStepZ() * .002);
            List<RoofPlacement.Join> joins = RoofPlacement.aboveSupport(level, item.getBlock(), aim);
            if (!joins.isEmpty()) return planRoof(plan, context, joins);
        }
        if (item.getBlock() instanceof FurnitureBlock furniture && (ShelfPlacement.isWoodenShelf(furniture)
                || furniture.definition().id().equals("interior_long_kitchen_shelf"))) {
            return planWallShelf(plan, context, furniture);
        }
        if (item.getBlock() instanceof FurnitureBlock chair && DiningChairPlacement.isChair(chair)
                && contact.face() == Direction.UP && InteriorPlacement.floor(level, contact.owner(), support)
                && furnitureSnap(context)) {
            for (var snap : DiningChairPlacement.candidates(level, contact, chair)) {
                var floor = snap.floor();
                var baseAtFloor = floor.owner().above();
                Plan fitted = new Plan(level, floor.owner(), baseAtFloor, baseAtFloor, Direction.UP, floor.point(), item, false);
                fitted.furnitureFacing = snap.facing();
                if (tryFurnitureOrigin(fitted, context, chair, snap.origin())) {
                    fitted.specialSnap = true;
                    return fitted;
                }
            }
        }
        if (item.getBlock() instanceof FurnitureBlock cabinet && InteriorPlacement.kitchenPair(cabinet, support.getBlock())) {
            Direction facing = support.getValue(FurnitureBlock.FACING);
            if (contact.face().getAxis() == facing.getClockWise().getAxis()) {
                FurnitureBlock neighbor = (FurnitureBlock) support.getBlock();
                BlockPos root = neighbor.origin(contact.owner(), support);
                AABB bounds = SurfaceGeometry.assemblyBounds(level, root, support.setValue(FurnitureBlock.PART, 0))
                        .move(Vec3.atLowerCornerOf(root).add(AnchorRuntime.offset(level, root)));
                Direction.Axis side = contact.face().getAxis();
                boolean positive = contact.face().getAxisDirection() == Direction.AxisDirection.POSITIVE;
                double edge = positive ? bounds.max(side) + .18 : bounds.min(side) - .18;
                Vec3 sample = bounds.getCenter();
                sample = new Vec3(side == Direction.Axis.X ? edge : sample.x, bounds.minY,
                        side == Direction.Axis.Z ? edge : sample.z);
                SurfaceGeometry.Hit floor = InteriorPlacement.floorBelow(level, sample);
                if (floor != null) {
                    BlockPos floorBase = floor.owner().above();
                    Plan beside = new Plan(level, floor.owner(), floorBase, floorBase, Direction.UP, floor.point(), item, false);
                    beside.furnitureFacing = facing;
                    Plan fitted = planFloorFurniture(beside, context, floor, cabinet);
                    fitted.specialSnap = true;
                    return fitted;
                }
            }
            plan.usableSlot = false;
            return plan;
        }
        BlockPos slot;
        if (floorMerge) {
            Vec3 origin = Vec3.atLowerCornerOf(base).add(AnchorRuntime.offset(level, base));
            plan.floor = new FloorPlacement.Fit(origin, support.getValue(FloorSegmentBlock.SPAN_X),
                    support.getValue(FloorSegmentBlock.SPAN_Z), net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
            slot = base;
        } else if (item.getBlock() instanceof WallSegmentBlock && contact.face() == Direction.UP
                && (support.getBlock() instanceof FloorSegmentBlock || support.getBlock() instanceof WallSegmentBlock)) {
            WallShape shape = wallShape(plan);
            Direction preferred = context.getHorizontalDirection().getOpposite();
            if (support.getBlock() instanceof WallSegmentBlock) {
                Direction below = support.getValue(WallSegmentBlock.FACING);
                // Keep a stacked course on the same edge when the builder faces it from either side.
                boolean snap = context.getPlayer() == null || !context.getPlayer().isShiftKeyDown();
                plan.wallFacing = snap && below.getAxis() == preferred.getAxis() ? below : preferred;
            } else {
                plan.wallFacing = BuildingAlignment.resolve(context, shape, preferred);
            }
            slot = WallCornerPlacement.storage(context, shape, plan.wallFacing, base, contact);
        } else if (item.getBlock() instanceof FloorSegmentBlock && constructionSupport(support)) {
            plan.floor = FloorPlacement.resolve(level, plan.support, support, plan.face, plan.point, floorOrigin(plan));
            slot = AnchorStorage.find(plan.floor.origin(), root -> floorSlot(plan, root, context));
        } else if (item.getBlock() instanceof FurnitureBlock furniture
                && (InteriorPlacement.rug(furniture) || FurnitureFloorPlacement.applies(furniture, support, plan.face)
                || InteriorPlacement.kitchenCabinet(furniture) && plan.face == Direction.UP
                    && InteriorPlacement.floor(level, contact.owner(), support)
                || coveredFloor && FurnitureFloorPlacement.isFloorFurniture(furniture))) {
            return planFloorFurniture(plan, context, contact, furniture);
        } else {
            slot = freeSlot(level, base, context);
        }
        plan.usableSlot = slot != null;
        if (slot != null) plan.root = slot.immutable();
        plan.context = context(context, plan);
        return plan;
    }

    private static Plan planWallShelf(Plan plan, BlockPlaceContext original, FurnitureBlock shelf) {
        plan.furnitureFacing = plan.face;
        BlockState state = shelf.defaultBlockState().setValue(FurnitureBlock.FACING, plan.face);
        AABB bounds = SurfaceGeometry.assemblyBounds(plan.level, plan.base, state);
        Vec3 origin = Vec3.atLowerCornerOf(plan.base).add(calculate(plan, state));
        Direction.Axis width = plan.face.getClockWise().getAxis();
        double shift = .5 - bounds.getCenter().get(width);
        origin = origin.add(width == Direction.Axis.X ? shift : 0, 0, width == Direction.Axis.Z ? shift : 0);
        if (ShelfPlacement.isWoodenShelf(shelf)
                && furnitureSnap(original)) {
            for (var join : ShelfPlacement.joins(plan.level, plan.face, origin, bounds, plan.support)) {
                Plan fitted = new Plan(plan.level, join.wall().owner(), plan.base, plan.base,
                        plan.face, join.wall().point(), plan.item, false);
                fitted.furnitureFacing = plan.face;
                if (tryFurnitureOrigin(fitted, original, shelf, join.origin())) {
                    fitted.specialSnap = true;
                    return fitted;
                }
            }
        }
        if (!tryFurnitureOrigin(plan, original, shelf, origin)) plan.usableSlot = false;
        return plan;
    }

    private static Plan planFloorFurniture(Plan plan, BlockPlaceContext original, SurfaceGeometry.Hit contact,
                                          FurnitureBlock furniture) {
        if (plan.furnitureFacing == null) plan.furnitureFacing = original.getHorizontalDirection().getOpposite();
        var fit = FurnitureFloorPlacement.fit(plan.level, plan.base, contact, furniture, plan.furnitureFacing);
        if (fit != null) {
            if (furnitureSnap(original)) {
                for (Vec3 origin : FurnitureFloorPlacement.cabinetSnaps(plan.level, fit, furniture, plan.furnitureFacing)) {
                    if (tryFurnitureOrigin(plan, original, furniture, origin)) {
                        plan.specialSnap = true;
                        return plan;
                    }
                }
            }
            if (tryFurnitureOrigin(plan, original, furniture, fit.origin())) return plan;
            for (Vec3 origin : FurnitureFloorPlacement.alternatives(plan.level, fit)) {
                if (tryFurnitureOrigin(plan, original, furniture, origin)) return plan;
            }
            plan.furnitureOrigin = fit.origin();
        }
        plan.usableSlot = false;
        plan.root = plan.base;
        plan.offset = null;
        plan.context = context(original, plan);
        return plan;
    }

    private static boolean tryFurnitureOrigin(Plan plan, BlockPlaceContext original, FurnitureBlock furniture, Vec3 origin) {
        BlockPos slot = FurnitureFloorPlacement.storage(original, furniture, plan.furnitureFacing, origin, plan.face);
        if (slot == null) return false;
        plan.root = slot;
        plan.furnitureOrigin = origin;
        plan.offset = null;
        plan.context = context(original, plan);
        return validFurniturePlan(plan, furniture);
    }

    private static boolean constructionSupport(BlockState state) {
        return state.getBlock() instanceof FloorSegmentBlock || state.getBlock() instanceof WallSegmentBlock
                || state.getBlock() instanceof FurnitureBlock furniture && furniture.definition().group().equals("construction");
    }

    private static Plan planRoof(Plan plan, BlockPlaceContext original, List<RoofPlacement.Join> joins) {
        FurnitureBlock roof = (FurnitureBlock) plan.item.getBlock();
        for (RoofPlacement.Join join : joins) {
            BlockPos slot = RoofPlacement.storage(original, roof, join);
            if (slot == null) continue;
            plan.root = slot;
            plan.roofJoin = join;
            plan.offset = null;
            plan.context = context(original, plan);
            if (joins.size() == 1 || validFurniturePlan(plan, roof)) return plan;
        }
        plan.usableSlot = false;
        plan.root = plan.base;
        plan.roofJoin = joins.isEmpty() ? null : joins.getFirst();
        plan.offset = null;
        plan.context = context(original, plan);
        return plan;
    }

    private static boolean validFurniturePlan(Plan plan, FurnitureBlock furniture) {
        Plan previous = ACTIVE.get();
        ACTIVE.set(plan);
        try {
            updatedContext(plan.context);
            BlockState state = furniture.getStateForPlacement(plan.context);
            return state != null && state.canSurvive(plan.level, plan.root) && validateCandidate(plan.context, state);
        } finally {
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }

    private static Vec3 floorOrigin(Plan plan) {
        Vec3 adjacent = Vec3.atLowerCornerOf(plan.base).add(AnchorRuntime.offset(plan.level, plan.support));
        // The lower half touches an upper face, the upper half touches a lower face.
        double plane = plan.point.get(plan.face.getAxis())
                - (plan.face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0 : 1);
        return switch (plan.face.getAxis()) {
            case X -> new Vec3(plane, adjacent.y, adjacent.z);
            case Y -> new Vec3(adjacent.x, plane, adjacent.z);
            case Z -> new Vec3(adjacent.x, adjacent.y, plane);
        };
    }

    private static boolean floorSlot(Plan plan, BlockPos root, BlockPlaceContext context) {
        Level level = plan.level;
        if (!level.hasChunkAt(root) || !level.isInWorldBounds(root) || !level.getWorldBorder().isWithinBounds(root)) return false;
        var player = context.getPlayer();
        if (player != null && (!level.mayInteract(player, root)
                || !player.mayUseItemAt(root, plan.face, context.getItemInHand()))) return false;
        BlockState state = level.getBlockState(root);
        if (state.isAir()) return true;
        if (!root.equals(plan.base) || !state.canBeReplaced(context)) return false;
        if (state.is(plan.item.getBlock())) {
            // A slab in the native target can merge only if it is physically at the target as well.
            return plan.floor.sameSpan(state) && Vec3.atLowerCornerOf(root).add(AnchorRuntime.offset(level, root))
                    .distanceToSqr(plan.floor.origin()) < 1.0e-12;
        }
        return !AnchorRuntime.anchored(level, root);
    }

    /** Construction walls retain the same physical facing after logical storage is selected. */
    public static Direction plannedWallFacing(BlockPlaceContext context) {
        Plan plan = ACTIVE.get();
        return plan != null && plan.context == context ? plan.wallFacing : null;
    }

    /** Apply the same fitted footprint to native placement and the client's outline. */
    public static BlockState plannedFloorState(BlockPlaceContext context, BlockState state) {
        Plan plan = ACTIVE.get();
        return plan != null && plan.context == context && plan.floor != null ? plan.floor.apply(state) : state;
    }

    public static BlockState plannedRoofState(BlockPlaceContext context, BlockState state) {
        Plan plan = ACTIVE.get();
        return plan != null && plan.context == context && plan.roofJoin != null
                ? state.setValue(RoofSegmentBlock.SPAN, plan.roofJoin.span()) : state;
    }

    /** Read-only placement planning shared by the existing wall/floor/window preview. */
    public static void preview(BlockPlaceContext context,java.util.function.Consumer<BlockPlaceContext> draw){
        if(ACTIVE.get()!=null||!(context.getItemInHand().getItem() instanceof BlockItem item)||!eligibleItem(item)){
            draw.accept(context);return;
        }
        boolean floorMerge = floorMerge(context, item);
        if (context.replacingClickedOnBlock() && !floorMerge) { draw.accept(context); return; }
        boolean roofJoint = roofJoint(context, item);
        SurfaceGeometry.Hit contact=contact(context, roofJoint);
        if(contact==null){draw.accept(context);return;}
        Plan plan = planBlock(context, item, contact, roofJoint, floorMerge);
        Plan previous=ACTIVE.get();ACTIVE.set(plan);
        try{updatedContext(plan.context);draw.accept(plan.context);}
        finally{if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
    }
    /** Inspect inside preview(callback); no predictive anchor is installed or sent. */
    public static Vec3 plannedOffset(BlockPlaceContext context,BlockState state){
        Plan p=ACTIVE.get();if(p==null||p.level!=context.getLevel())return Vec3.ZERO;
        return p.offset==null?calculate(p,state):p.offset;
    }

    public record FurniturePreview(FurnitureBlock block, BlockState state, Vec3 origin, boolean valid, double rotation) {}

    private static boolean freeFurniture(BlockPlaceContext context) {
        return context.getHand() == InteractionHand.MAIN_HAND && FreeFurniturePlacement.applies(context.getItemInHand());
    }

    private static boolean furnitureSnap(BlockPlaceContext context) {
        return context.getPlayer() == null || (freeFurniture(context)
                ? context.getPlayer().isShiftKeyDown() || FreeFurniturePlacement.snapEnabled(context.getPlayer())
                : !context.getPlayer().isShiftKeyDown());
    }

    public static FurniturePreview furniturePreview(BlockPlaceContext context) {
        if (!freeFurniture(context)) return null;
        BlockItem item = (BlockItem) context.getItemInHand().getItem();
        FurnitureBlock furniture = (FurnitureBlock) item.getBlock();
        Plan plan = planFurniture(context, item, furniture);
        if (plan.usableSlot) {
            return new FurniturePreview(furniture, plan.candidate, plan.furnitureOrigin != null
                    ? plan.furnitureOrigin : Vec3.atLowerCornerOf(plan.root).add(calculate(plan, plan.candidate)), true, plan.yaw);
        }
        return new FurniturePreview(furniture, furnitureState(context, furniture, plan.face), plan.furnitureOrigin, false, plan.yaw);
    }

    private static BlockState furnitureState(BlockPlaceContext context, FurnitureBlock furniture, Direction face) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        float angle = FreeFurniturePlacement.currentRotation(context.getPlayer());
        if (Float.isFinite(angle)) facing = switch ((int) ((angle + 45) / 90) & 3) {
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            case 3 -> Direction.WEST;
            default -> Direction.NORTH;
        };
        if (InteriorPlacement.wallMounted(furniture) && face.getAxis().isHorizontal()) facing = face;
        return furniture.defaultBlockState().setValue(FurnitureBlock.FACING, facing);
    }

    private static Plan planFurniture(BlockPlaceContext context, BlockItem item, FurnitureBlock furniture) {
        Level level = context.getLevel();
        BlockHitResult picked = hit(context);
        Direction face = picked.getDirection();
        BlockPos owner = picked.getBlockPos();
        boolean wall = InteriorPlacement.wallMounted(furniture);
        boolean ceiling = InteriorPlacement.ceilingMounted(furniture);
        boolean rug = InteriorPlacement.rug(furniture);
        boolean snap = !rug && furnitureSnap(context);
        if (snap && context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
            SurfaceGeometry.Hit previous = contact(context);
            if (previous != null) {
                Plan fitted = planBlock(context, item, previous, false, false);
                if (fitted.usableSlot && validFurniturePlan(fitted, furniture)) return fitted;
            }
        }
        Vec3 point = picked.getLocation();
        SurfaceGeometry.Hit contact = freeFurnitureContact(context, picked, wall, ceiling, rug);
        if (contact != null) {
            owner = contact.owner();
            point = contact.point();
            face = contact.face();
        }
        BlockState state = furnitureState(context, furniture, face);
        AABB local = SurfaceGeometry.assemblyBounds(level, owner, state);
        float angle = FreeFurniturePlacement.currentRotation(context.getPlayer());
        double yaw = wall || !Float.isFinite(angle) ? 0 : Math.toRadians(Math.IEEEremainder(
                angle - FreeFurniturePlacement.cardinalDegrees(state.getValue(FurnitureBlock.FACING)), 360));
        Vec3 origin = point.subtract(FurnitureTransform.point(local.getCenter(), Vec3.ZERO, yaw));
        if (wall && face.getAxis().isHorizontal()) {
            double edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? local.min(face.getAxis()) : local.max(face.getAxis());
            origin = face.getAxis() == Direction.Axis.X ? new Vec3(point.x - edge, origin.y, origin.z)
                    : new Vec3(origin.x, origin.y, point.z - edge);
        } else {
            origin = new Vec3(origin.x, point.y - (ceiling ? local.maxY : local.minY), origin.z);
        }
        BlockPos base = owner.relative(face);
        Plan plan = new Plan(level, owner, base, base, face, point, item, false);
        plan.furnitureFacing = state.getValue(FurnitureBlock.FACING);
        plan.furnitureOrigin = origin;
        plan.freeFurniture = true;
        plan.yaw = yaw;
        if (snap) {
            SurfaceGeometry.Hit previous = contact(context);
            if (previous != null && (wall || InteriorPlacement.kitchenCabinet(furniture)
                    || DiningChairPlacement.isChair(furniture))) {
                Plan fitted = planBlock(context, item, previous, false, false);
                if (fitted.specialSnap && fitted.usableSlot && validFurniturePlan(fitted, furniture)) return fitted;
            }
            if (contact != null && !wall && !ceiling) {
                for (var candidate : FurnitureSnap.candidates(level, contact, furniture, state, origin, yaw)) {
                    var floor = candidate.floor();
                    var baseAtFloor = floor.owner().above();
                    Plan fitted = new Plan(level, floor.owner(), baseAtFloor, baseAtFloor, Direction.UP, floor.point(), item, false);
                    fitted.furnitureFacing = plan.furnitureFacing;
                    fitted.yaw = candidate.yaw();
                    fitted.freeFurniture = true;
                    if (furnitureSupported(level, furniture, state, floor.owner(), candidate.origin(), local, false, false, fitted.yaw)
                            && tryFurnitureOrigin(fitted, context, furniture, candidate.origin())) return fitted;
                }
            }
            if (wall && previous != null) {
                Plan fitted = planBlock(context, item, previous, false, false);
                if (fitted.usableSlot && validFurniturePlan(fitted, furniture)) return fitted;
            }
        }
        plan.usableSlot = contact != null
                && furnitureSupported(level, furniture, state, owner, origin, local, wall, ceiling, yaw)
                && tryFurnitureOrigin(plan, context, furniture, origin);
        return plan;
    }

    private static SurfaceGeometry.Hit freeFurnitureContact(BlockPlaceContext context, BlockHitResult picked,
                                                             boolean wall, boolean ceiling, boolean rug) {
        Direction face = picked.getDirection();
        Level level = context.getLevel();
        BlockPos owner = picked.getBlockPos();
        if (picked.getType() != HitResult.Type.BLOCK || !level.hasChunkAt(owner)) return null;
        Vec3 raw = picked.getLocation().subtract(Vec3.atLowerCornerOf(owner));
        Vec3 point = Vec3.atLowerCornerOf(owner).add((float) raw.x, (float) raw.y, (float) raw.z);
        if (rug) return reachable(context, InteriorPlacement.rugContact(level, picked, point));
        if (wall ? !face.getAxis().isHorizontal() : face != (ceiling ? Direction.DOWN : Direction.UP)) return null;
        BlockState support = level.getBlockState(owner);
        if (wall && !(support.getBlock() instanceof WallSegmentBlock) && !support.isFaceSturdy(level, owner, face)) return null;
        if (ceiling && !(support.getBlock() instanceof FloorSegmentBlock || support.getBlock() instanceof SlabBlock
                || support.isFaceSturdy(level, owner, face))) return null;
        if (!wall && !ceiling && !InteriorPlacement.rug(support.getBlock())
                && AnchorRuntime.rawShape(level, owner, support, true).isEmpty()) return null;
        Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
        var contact = SurfaceGeometry.hit(level, owner, point.add(normal.scale(.04)), point.subtract(normal.scale(.04)));
        if (contact == null || contact.face() != face) return null;
        return reachable(context, InteriorPlacement.rug(support.getBlock())
                ? InteriorPlacement.floorBelow(level, contact.point()) : contact);
    }

    private static SurfaceGeometry.Hit reachable(BlockPlaceContext context, SurfaceGeometry.Hit contact) {
        if (contact != null && context.getPlayer() != null && (context.getPlayer().isSpectator()
                || contact.point().distanceToSqr(context.getPlayer().getEyePosition())
                    > Math.pow(context.getPlayer().blockInteractionRange() + .25, 2))) return null;
        return contact;
    }

    private static boolean furnitureSupported(Level level, FurnitureBlock furniture, BlockState state, BlockPos owner,
                                              Vec3 origin, AABB local, boolean wall, boolean ceiling, double yaw) {
        if (wall || ceiling) return true;
        if (InteriorPlacement.rug(furniture)) return InteriorPlacement.supportedRug(level, owner, furniture, state, origin, yaw);
        Direction facing = state.getValue(FurnitureBlock.FACING);
        boolean foundBase = false;
        for (int part = 0; part < furniture.definition().parts().size(); part++) {
            BlockPos offset = furniture.partOffset(part, facing);
            VoxelShape shape = AnchorRuntime.rawShape(level, owner.offset(offset),
                    state.setValue(FurnitureBlock.PART, part), true);
            for (AABB box : shape.toAabbs()) {
                if (box.minY + offset.getY() > local.minY + .035) continue;
                foundBase = true;
                if (!supportedFootprint(level, box.move(offset), origin, origin.y + local.minY, yaw)) return false;
            }
        }
        return foundBase || supportedFootprint(level, local, origin, origin.y + local.minY, yaw);
    }

    private static boolean supportedFootprint(Level level, AABB box, Vec3 origin, double y, double yaw) {
        double dx = Math.min(.028, box.getXsize() * .3);
        double dz = Math.min(.028, box.getZsize() * .3);
        for (double x : new double[]{box.minX + dx, box.maxX - dx}) {
            for (double z : new double[]{box.minZ + dz, box.maxZ - dz}) {
                Vec3 sample = FurnitureTransform.point(new Vec3(x, y - origin.y, z), Vec3.ZERO, yaw).add(origin);
                if (!InteriorPlacement.furnitureSupport(level, sample)) return false;
            }
        }
        return true;
    }

    public static InteractionResult place(BlockItem item,BlockPlaceContext context,Function<BlockPlaceContext,InteractionResult> original){
        if(ACTIVE.get()!=null||!eligibleItem(item))return original.apply(context);
        if (freeFurniture(context)) return install(planFurniture(context, item, (FurnitureBlock) item.getBlock()), context, original);
        boolean floorMerge = floorMerge(context, item);
        boolean requiresSupport = InteriorPlacement.rug(item.getBlock()) || InteriorPlacement.wallMounted(item.getBlock())
                || InteriorPlacement.ceilingMounted(item.getBlock());
        if (context.replacingClickedOnBlock() && !floorMerge && !requiresSupport) return original.apply(context);
        boolean roofJoint = roofJoint(context, item);
        SurfaceGeometry.Hit contact=contact(context, roofJoint);
        if (contact == null) {
            BlockState support = context.getLevel().getBlockState(hit(context).getBlockPos());
            return roofJoint || floorMerge || requiresSupport || !RoofPlacement.validFlueContact(context.getLevel(), item.getBlock(), support, null)
                    ? InteractionResult.FAIL : original.apply(context);
        }
        return install(planBlock(context, item, contact, roofJoint, floorMerge), context, original);
    }

    private static InteractionResult install(Plan plan, BlockPlaceContext context,
                                             Function<BlockPlaceContext, InteractionResult> original) {
        if (!plan.usableSlot) return InteractionResult.FAIL;
        Level level = plan.level;
        BlockItem item = plan.item;
        Plan previous=ACTIVE.get();ACTIVE.set(plan);
        try{
            InteractionResult result = mutate(() -> {
                if (item.getBlock() instanceof FurnitureBlock furniture && !plan.root.equals(context.getClickedPos())) {
                    // FurnitureBlockItem guarded the original cell before the surface plan chose storage.
                    return furniture.mutate(level, plan.root, () -> original.apply(plan.context));
                }
                return original.apply(plan.context);
            });
            if(result.consumesAction()&&!level.getBlockState(plan.root).isAir())commit(plan);
            return result;
        }finally{
            if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);
            if(level instanceof ServerLevel server&&context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)
                AnchorRuntime.resend(server,player);
        }
    }
    public static void updatedContext(BlockPlaceContext context){
        Plan p=ACTIVE.get();if(p==null||p.level!=context.getLevel())return;
        p.root=context.getClickedPos().immutable();p.context=context;p.positions.clear();p.positions.add(p.root);
        if(p.item!=null&&p.item.getBlock() instanceof FurnitureBlock furniture)
            p.positions.addAll(furniture.positions(p.root,context.getHorizontalDirection().getOpposite()));
        if(p.item!=null&&p.item.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)p.positions.add(p.root.above());
        if(p.item!=null&&p.item.getBlock() instanceof net.minecraft.world.level.block.BedBlock)p.positions.add(p.root.relative(context.getHorizontalDirection()));
        if(p.item!=null&&p.item.getBlock() instanceof WallSegmentBlock) {
            WallShape shape = wallShape(p);
            if (p.wallFacing != null) p.positions.addAll(shape.getPositions(p.root, p.wallFacing));
            else for (Direction facing : Direction.Plane.HORIZONTAL) p.positions.addAll(shape.getPositions(p.root, facing));
        }
    }
    private static com.dark.winter.component.WallShape wallShape(Plan p){
        var player=p.context.getPlayer();
        return player==null?p.context.getItemInHand().getOrDefault(com.dark.winter.registry.ModDataComponents.WALL_SHAPE,com.dark.winter.component.WallShape.WALL_3X3)
                :p.level.isClientSide()?com.dark.winter.component.WallBuildManager.getClientShape()
                :com.dark.winter.component.WallBuildManager.getPlayerShape(player.getUUID());
    }
    public static boolean pendingAt(BlockGetter level,BlockPos pos){
        Plan p=ACTIVE.get();return p!=null&&p.level==level&&p.positions.contains(pos)&&!p.calculating;
    }
    static double pendingRotation(BlockGetter level, BlockPos pos) {
        Plan p = ACTIVE.get();
        return p != null && p.level == level && p.positions.contains(pos) ? p.yaw : 0;
    }
    public static Vec3 pendingOffset(BlockGetter level,BlockPos pos,BlockState state,VoxelShape raw){
        Plan p=ACTIVE.get();if(p==null||p.level!=level||!p.positions.contains(pos)||p.calculating)return null;
        if(state.isAir())return null;
        if(p.offset==null){p.calculating=true;try{p.offset=calculate(p,state);}finally{p.calculating=false;}}
        return p.offset;
    }
    private static Vec3 calculate(Plan p,BlockState state){
        if (p.roofJoin != null) return p.roofJoin.origin().subtract(Vec3.atLowerCornerOf(p.root));
        if (p.floor != null) return p.floor.origin().subtract(Vec3.atLowerCornerOf(p.root));
        if (p.furnitureOrigin != null) return p.furnitureOrigin.subtract(Vec3.atLowerCornerOf(p.root));
        AABB bounds=SurfaceGeometry.assemblyBounds(p.level,p.root,state);
        Vec3 delta=Vec3.atLowerCornerOf(p.base.subtract(p.root)).add(AnchorRuntime.offset(p.level,p.support));
        if(p.looseItem)delta=new Vec3(p.point.x-(p.root.getX()+(bounds.minX+bounds.maxX)*.5),delta.y,
                p.point.z-(p.root.getZ()+(bounds.minZ+bounds.maxZ)*.5));
        double plane=p.face.getAxisDirection()==Direction.AxisDirection.POSITIVE?bounds.min(p.face.getAxis()):bounds.max(p.face.getAxis());
        double adjustment=p.point.get(p.face.getAxis())-Vec3.atLowerCornerOf(p.root).get(p.face.getAxis())-plane;
        return switch(p.face.getAxis()){case X->new Vec3(adjustment,delta.y,delta.z);case Y->new Vec3(delta.x,adjustment,delta.z);case Z->new Vec3(delta.x,delta.y,adjustment);};
    }
    public static boolean pendingSupport(BlockGetter level,BlockPos pos,Direction face){
        Plan p=ACTIVE.get();return p!=null&&p.level==level&&p.face==face&&p.root.relative(face.getOpposite()).equals(pos);
    }

    public static boolean isUnobstructed(Level level, BlockState state, BlockPos pos, CollisionContext context) {
        Plan plan = ACTIVE.get();
        if (plan == null || plan.yaw == 0 || !pendingAt(level, pos) || !(state.getBlock() instanceof FurnitureBlock)) {
            return level.isUnobstructed(state, pos, context);
        }

        VoxelShape shape = AnchorRuntime.raw(() -> state.getCollisionShape(level, pos, context));
        if (shape.isEmpty()) return true;
        Vec3 offset = pendingOffset(level, pos, state, shape);
        Vec3 origin = Vec3.atLowerCornerOf(pos).add(offset);
        Vec3 pivot = Vec3.atLowerCornerOf(plan.root).add(offset);
        AABB bounds = FurnitureTransform.bounds(shape.bounds().move(origin), pivot, plan.yaw);
        // A placement test only needs overlap with entity boxes, not a new voxel shape at each angle.
        for (Entity entity : level.getEntities((Entity) null, bounds)) {
            if (entity.isRemoved() || !entity.blocksBuilding) continue;
            for (AABB box : shape.toAabbs()) {
                if (FurnitureTransform.intersects(box.move(origin), pivot, plan.yaw, entity.getBoundingBox())) return false;
            }
        }
        return true;
    }

    public static boolean validateCandidate(BlockPlaceContext context,BlockState state){
        Plan p=ACTIVE.get();if(p==null||p.level!=context.getLevel())return true;
        if (!p.usableSlot) return false;
        if (p.wallFacing != null && state.getValue(WallSegmentBlock.FACING) != p.wallFacing) return false;
        if (p.roofJoin != null && !RoofPlacement.matches(context, state, p.roofJoin)) return false;
        if (p.furnitureFacing != null) {
            var overrides = context.getItemInHand().get(net.minecraft.core.component.DataComponents.BLOCK_STATE);
            BlockState placed = overrides == null ? state : overrides.apply(state);
            if (placed.getValue(FurnitureBlock.FACING) != p.furnitureFacing || placed.getValue(FurnitureBlock.PART) != 0) return false;
        }
        if (p.floor != null) {
            var overrides = context.getItemInHand().get(net.minecraft.core.component.DataComponents.BLOCK_STATE);
            if (overrides != null && overrides.apply(state) != state) return false;
        }
        p.candidate=state;p.offset=null;p.positions.clear();p.positions.add(p.root);
        if(state.getBlock() instanceof FurnitureBlock furniture)p.positions.addAll(furniture.positions(p.root,state.getValue(FurnitureBlock.FACING)));
        if(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)p.positions.add(p.root.above());
        if(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)p.positions.add(p.root.relative(state.getValue(net.minecraft.world.level.block.BedBlock.FACING)));
        if(state.getBlock() instanceof WallSegmentBlock)p.positions.addAll(wallShape(p).getPositions(p.root,state.getValue(WallSegmentBlock.FACING)));
        p.offset=calculate(p,state);
        if(!p.offset.equals(Vec3.ZERO)&&!state.getFluidState().isEmpty())return false;
        if(!Double.isFinite(p.offset.x+p.offset.y+p.offset.z)||Math.abs(p.offset.x)>2||Math.abs(p.offset.y)>2||Math.abs(p.offset.z)>2)return false;
        var supportOwners = new HashSet<BlockPos>();
        boolean rug = InteriorPlacement.rug(state.getBlock());
        // The roof footprints touch at their edges, so the clicked roof passes the normal collision check.
        if (p.roofJoin == null) {
            supportOwners.add(p.support);
            BlockState supporting = p.level.getBlockState(p.support);
            if (supporting.getBlock() instanceof FurnitureBlock furniture) {
                supportOwners.addAll(furniture.positions(furniture.origin(p.support, supporting),
                        supporting.getValue(FurnitureBlock.FACING)));
            }
        }
        for(BlockPos member:p.positions){
            if(!p.level.hasChunkAt(member)||!p.level.isInWorldBounds(member)||!p.level.getWorldBorder().isWithinBounds(member)
                    ||!p.level.getBlockState(member).canBeReplaced(context))return false;
            if(context.getPlayer()!=null&&(!p.level.mayInteract(context.getPlayer(),member)
                    ||!context.getPlayer().mayUseItemAt(member,p.face,context.getItemInHand())))return false;
            BlockState partState=state;
            if(state.getBlock() instanceof FurnitureBlock furniture){
                for(int index=0;index<furniture.definition().parts().size();index++)
                    if(p.root.offset(furniture.partOffset(index,state.getValue(FurnitureBlock.FACING))).equals(member))partState=state.setValue(FurnitureBlock.PART,index);
            }
            if(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock&&!member.equals(p.root))
                partState=state.setValue(net.minecraft.world.level.block.DoorBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
            if(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock&&!member.equals(p.root))
                partState=state.setValue(net.minecraft.world.level.block.BedBlock.PART,net.minecraft.world.level.block.state.properties.BedPart.HEAD);
            if(!isUnobstructed(p.level,partState,member,CollisionContext.placementContext(context.getPlayer())))return false;
            VoxelShape local=AnchorRuntime.rawShape(p.level,member,partState,true);
            boolean selectionOnly=p.looseItem&&local.isEmpty();
            if(local.isEmpty())local=AnchorRuntime.rawShape(p.level,member,partState,false);
            if(local.isEmpty())continue;
            Vec3 partOrigin = Vec3.atLowerCornerOf(member).add(p.offset);
            Vec3 pivot = Vec3.atLowerCornerOf(p.root).add(p.offset);
            VoxelShape physical=local.move(partOrigin);
            AABB box=FurnitureTransform.bounds(physical.bounds(), pivot, p.yaw);
            if (rug && !p.freeFurniture && !InteriorPlacement.supported(p.level, box)) return false;
            // A collision-free laid item's outline selects it; it is not its physical body.
            // Keep that conservative reservation against other obstacles, without rejecting its exact support contact.
            if(!selectionOnly)for(BlockPos support:supportOwners)if(SurfaceGeometry.obstructs(p.level,support,box,p.face,p.point))return false;
            var others=new HashSet<BlockPos>();
            for(BlockPos cell:BlockPos.betweenClosed(BlockPos.containing(box.minX-1e-6,box.minY-1e-6,box.minZ-1e-6),
                    BlockPos.containing(box.maxX+1e-6,box.maxY+1e-6,box.maxZ+1e-6))){
                if (!p.level.hasChunkAt(cell)) return false;
                if ((p.wallFacing != null || p.floor != null || p.roofJoin != null || p.furnitureOrigin != null)
                        && box.intersects(new AABB(cell))
                        && (!p.level.isInWorldBounds(cell) || !p.level.getWorldBorder().isWithinBounds(cell)
                        || context.getPlayer() != null && !p.level.mayInteract(context.getPlayer(), cell))) return false;
                others.add(cell.immutable());
                others.addAll(AnchorRuntime.logicalOwners(p.level,cell));
            }
            for(BlockPos other:others){
                if(p.positions.contains(other)||supportOwners.contains(other))continue;
                BlockState neighbor = p.level.getBlockState(other);
                boolean otherRug = rug && InteriorPlacement.rug(neighbor.getBlock());
                if (rug && !otherRug && InteriorPlacement.rugCanPassUnder(p.level, other, neighbor)) continue;
                double neighborYaw = AnchorRuntime.rotation(p.level, other);
                if ((p.yaw != 0 || neighborYaw != 0) && neighbor.getBlock() instanceof FurnitureBlock) {
                    boolean outline = otherRug || (p.freeFurniture && (InteriorPlacement.wallMounted(neighbor.getBlock())
                            || InteriorPlacement.ceilingMounted(neighbor.getBlock())) && AnchorRuntime.rawShape(p.level, other, neighbor, true).isEmpty());
                    VoxelShape raw = AnchorRuntime.rawShape(p.level, other, neighbor, !outline);
                    if (raw.isEmpty()) continue;
                    Anchor anchor = AnchorRuntime.anchor(p.level, other);
                    Vec3 delta = AnchorRuntime.offset(p.level, other);
                    Vec3 otherPivot = Vec3.atLowerCornerOf(anchor == null ? other : anchor.owner()).add(delta);
                    if (FurnitureTransform.intersects(physical, pivot, p.yaw,
                            raw.move(Vec3.atLowerCornerOf(other).add(delta)), otherPivot, neighborYaw)) return false;
                    continue;
                }
                VoxelShape collision = otherRug ? neighbor.getShape(p.level, other, CollisionContext.empty())
                        : neighbor.getCollisionShape(p.level, other, CollisionContext.empty());
                if (collision.isEmpty() && p.freeFurniture && (InteriorPlacement.wallMounted(neighbor.getBlock())
                        || InteriorPlacement.ceilingMounted(neighbor.getBlock()))) {
                    collision = neighbor.getShape(p.level, other, CollisionContext.empty());
                }
                collision = collision.move(other);
                if (collision.isEmpty() || !(p.yaw == 0 ? Shapes.joinIsNotEmpty(physical, collision, BooleanOp.AND)
                        : FurnitureTransform.intersects(physical, pivot, p.yaw, collision, Vec3.ZERO, 0))) continue;
                if (p.wallFacing == null || !(neighbor.getBlock() instanceof WallSegmentBlock)
                        || !WallCornerPlacement.joins(p.level, p.positions, member, p.wallFacing, box,
                        other, neighbor.getValue(WallSegmentBlock.FACING), collision.bounds())) return false;
            }
        }
        return true;
    }
    public static void beforeChange(Level level,BlockPos pos,BlockState before){
        Plan p=ACTIVE.get();if(p!=null&&p.level==level&&p.before.size()<64)p.before.putIfAbsent(pos.immutable(),before);
    }
    private static void commit(Plan p){
        BlockState root=p.level.getBlockState(p.root);var members=new LinkedHashSet<BlockPos>();members.add(p.root);
        BlockState old = p.before.get(p.root);
        if (p.floor != null && old != null && old.is(root.getBlock())) return;
        p.before.forEach((pos,before)->{if(p.level.getBlockState(pos).getBlock()==root.getBlock()&&before.getBlock()!=root.getBlock())members.add(pos);});
        if(root.getBlock() instanceof FurnitureBlock furniture)members.addAll(furniture.positions(p.root,root.getValue(FurnitureBlock.FACING)));
        p.offset=calculate(p,root);
        Anchor anchor=new Anchor(p.root,p.support,p.face,p.offset,p.point,List.copyOf(members),p.yaw);
        if(p.level instanceof ServerLevel server)AnchorRuntime.put(server,anchor);else AnchorRuntime.predict(p.level,anchor);
    }
    public static boolean canPlaceItem(UseOnContext context){
        return context.getPlayer()!=null&&context.getPlayer().isShiftKeyDown()&&context.getClickedFace()==Direction.UP
                &&eligible(context.getLevel(),context.getClickedPos())&&contact(context)!=null;
    }
    public static InteractionResult placeItem(UseOnContext context){
        if(!canPlaceItem(context))return InteractionResult.PASS;
        if(context.getLevel().isClientSide())return InteractionResult.SUCCESS;
        var contact=contact(context);BlockPlaceContext nativeContext=new BlockPlaceContext(context);
        BlockPos base=nativeContext.getClickedPos(),root=freeSlot(context.getLevel(),base,nativeContext);
        if(root==null||contact==null)return InteractionResult.FAIL;
        Plan p=new Plan(context.getLevel(),contact.owner(),base,root,Direction.UP,contact.point(),null,true);
        p.context=context(nativeContext,p);p.candidate=PlacedItems.BLOCK.defaultBlockState().setValue(PlacedItems.BLOCK.FACING,context.getHorizontalDirection().getOpposite());
        p.positions.add(root);Plan previous=ACTIVE.get();ACTIVE.set(p);
        try{
            if(!validateCandidate(p.context,p.candidate))return InteractionResult.FAIL;
            var single=context.getItemInHand().copyWithCount(1);
            BlockState previousState=p.level.getBlockState(root);
            boolean installed=mutate(()->p.level.setBlock(root,p.candidate,3)&&PlacedItems.setStack(p.level,root,single));
            if(!installed){mutate(()->p.level.setBlock(root,previousState,3));return InteractionResult.FAIL;}
            commit(p);if(!context.getPlayer().hasInfiniteMaterials())context.getItemInHand().shrink(1);
            return InteractionResult.SUCCESS;
        }finally{if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
    }
    /** Native hanging boxes keep their logical tile; only their physical contact changes. */
    public static Vec3 hangingOffset(UseOnContext context,AABB original){
        var contact=contact(context);if(contact==null)return null;Direction face=contact.face();
        Vec3 parent=AnchorRuntime.offset(context.getLevel(),contact.owner());double near=face.getAxisDirection()==Direction.AxisDirection.POSITIVE?original.min(face.getAxis()):original.max(face.getAxis());
        double shift=contact.point().get(face.getAxis())-near;
        return switch(face.getAxis()){case X->new Vec3(shift,parent.y,parent.z);case Y->new Vec3(parent.x,shift,parent.z);case Z->new Vec3(parent.x,parent.y,shift);};
    }
    public static boolean mutating(){return MUTATING.get()!=0;}
    public static <T>T mutate(Supplier<T> action){int old=MUTATING.get();MUTATING.set(old+1);try{return action.get();}finally{if(old==0)MUTATING.remove();else MUTATING.set(old);}}
    public static BlockHitResult logicalHit(Level level,BlockHitResult hit){
        Vec3 point=AnchorRuntime.logicalPoint(level,hit.getBlockPos(),hit.getLocation());
        return point.equals(hit.getLocation())?hit:new BlockHitResult(point,hit.getDirection(),hit.getBlockPos(),hit.isInside());
    }
}
