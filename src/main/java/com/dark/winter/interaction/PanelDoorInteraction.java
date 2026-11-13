package com.dark.winter.interaction;

import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.block.PanelDoorBlock;
import com.dark.winter.block.PanelDoorEntity;
import com.dark.winter.placement.AnchorRuntime;
import com.dark.winter.registry.ModFurniture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class PanelDoorInteraction {
    public static final BlockEntityType<PanelDoorEntity> TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("darkwinter", "panel_door"), FabricBlockEntityTypeBuilder.create(
                    PanelDoorEntity::new, ModFurniture.get("northern_panel_door"), ModFurniture.get("cabin_panel_door")).build());

    public record Use(BlockPos part, Vec3 aim, boolean expectedOpen, boolean animateCreative) implements CustomPacketPayload {
        public Use(BlockPos part, Vec3 aim, boolean expectedOpen) { this(part, aim, expectedOpen, true); }
        public static final Type<Use> TYPE = new Type<>(Identifier.fromNamespaceAndPath("darkwinter", "panel_door_use"));
        public static final StreamCodec<FriendlyByteBuf, Use> CODEC = StreamCodec.of(
                (buffer, request) -> {
                    buffer.writeBlockPos(request.part);
                    buffer.writeDouble(request.aim.x);
                    buffer.writeDouble(request.aim.y);
                    buffer.writeDouble(request.aim.z);
                    buffer.writeBoolean(request.expectedOpen);
                    if (!request.animateCreative) buffer.writeBoolean(false);
                }, buffer -> new Use(buffer.readBlockPos(), new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                        buffer.readBoolean(), !buffer.isReadable() || buffer.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Target(BlockPos origin, PanelDoorBlock block, BlockState lower, BlockState upper) {
        public boolean open() { return lower.getValue(PanelDoorBlock.OPEN); }
    }

    private static final int PASSAGE_TIMEOUT = 90;
    private static final int CLOSE_DELAY = 3;
    private static final int OBSTRUCTION_TIMEOUT = 200;
    private static final Map<ServerLevel, Map<BlockPos, PendingClose>> PENDING = new ConcurrentHashMap<>();

    private record Walk(HousePassage passage, long expiresAt) {}

    private static final class PendingClose {
        final PanelDoorEntity entity;
        final BlockState openState;
        final Vec3 center;
        final Map<ServerPlayer, Walk> walkers = new HashMap<>();
        long closeAt = Long.MAX_VALUE;
        long retryUntil;
        @Nullable ServerPlayer actor;

        PendingClose(PanelDoorEntity entity, Target door, Vec3 center) {
            this.entity = entity;
            this.openState = door.lower;
            this.center = center;
        }
    }

    private PanelDoorInteraction() {}

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(Use.TYPE, Use.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HousePassage.TYPE, HousePassage.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Use.TYPE, (packet, context) ->
                context.server().execute(() -> use(context.player(), packet)));
        ServerTickEvents.END_LEVEL_TICK.register(PanelDoorInteraction::closeAfterPassages);
        ServerLevelEvents.UNLOAD.register((server, level) -> PENDING.remove(level));
    }

    public static @Nullable Target target(Level level, BlockPos part) {
        if (!level.hasChunkAt(part)) return null;
        BlockState state = level.getBlockState(part);
        if (!(state.getBlock() instanceof PanelDoorBlock door) || state.getValue(FurnitureBlock.PART) >= 2) return null;
        BlockPos root = door.origin(part, state).immutable();
        if (!level.hasChunkAt(root) || !level.hasChunkAt(root.above())) return null;
        BlockState lower = level.getBlockState(root), upper = level.getBlockState(root.above());
        if (!lower.is(door) || lower.getValue(FurnitureBlock.PART) != 0 || upper != lower.setValue(FurnitureBlock.PART, 1)) return null;
        return new Target(root, door, lower, upper);
    }

    public static @Nullable Target reachable(Player player, BlockPos part, Vec3 aim) {
        Target target = target(player.level(), part);
        if (target == null) return null;
        BlockPos visiblePart = FurnitureInteraction.visiblePart(player, aim);
        Target visible = visiblePart == null ? null : target(player.level(), visiblePart);
        return visible != null && visible.origin.equals(target.origin) ? target : null;
    }

    private static void use(ServerPlayer player, Use request) {
        if (!player.isAlive() || player.isRemoved() || player.isSpectator() || !player.mayBuild()
                || player.isUsingItem() || player.containerMenu != player.inventoryMenu) return;
        var level = player.level();
        Target door = reachable(player, request.part, request.aim);
        boolean aperture = door == null && request.expectedOpen;
        if (aperture) {
            door = target(level, request.part);
            if (door == null || !HousePassage.visibleAperture(player, door, request.aim)) return;
        }
        if (door == null || door.open() != request.expectedOpen) return;
        BlockPos root = door.origin, upper = root.above();
        if (!level.mayInteract(player, root) || !level.mayInteract(player, upper)
                || !level.getWorldBorder().isWithinBounds(root) || !level.getWorldBorder().isWithinBounds(upper)) return;
        boolean animate = (!player.isCreative() || request.animateCreative)
                && !player.isShiftKeyDown() && ServerPlayNetworking.canSend(player, HousePassage.TYPE);
        HousePassage passage = player.isShiftKeyDown() ? null : HousePassage.plan(player, door);
        if (door.open() && passage != null && animate) {
            beginPassage(player, door, passage, true);
            return;
        }
        if (aperture || !door.block.swingClear(level, root, door.lower.getValue(FurnitureBlock.FACING))) return;
        if (setOpen(level, door, !door.open(), player)) {
            var pending = PENDING.get(level);
            if (pending != null) pending.remove(root);
            if (passage != null) beginPassage(player, target(level, root), passage, animate);
        }
    }

    private static void beginPassage(ServerPlayer player, @Nullable Target door, HousePassage passage, boolean animate) {
        var level = player.level();
        if (door != null && level.getBlockEntity(door.origin) instanceof PanelDoorEntity entity) {
            var doors = PENDING.computeIfAbsent(level, ignored -> new HashMap<>());
            var center = HousePassage.center(level, door);
            var pending = doors.get(door.origin);
            if (pending == null || pending.entity != entity || pending.openState != door.lower || !pending.center.equals(center)) {
                pending = new PendingClose(entity, door, center);
                doors.put(door.origin, pending);
            }
            pending.walkers.putIfAbsent(player, new Walk(passage, level.getGameTime() + PASSAGE_TIMEOUT));
        }
        if (animate) ServerPlayNetworking.send(player, passage);
    }

    private static void closeAfterPassages(ServerLevel level) {
        var doors = PENDING.get(level);
        if (doors == null) return;
        long now = level.getGameTime();
        var iterator = doors.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var pending = entry.getValue();
            var door = target(level, entry.getKey());
            if (door == null || door.lower != pending.openState || level.getBlockEntity(door.origin) != pending.entity
                    || !HousePassage.center(level, door).equals(pending.center)) {
                iterator.remove();
                continue;
            }
            pending.walkers.entrySet().removeIf(walking -> finished(level, pending, walking.getKey(), walking.getValue(), now));
            if (!pending.walkers.isEmpty()) continue;
            if (pending.actor == null || now > pending.retryUntil) {
                iterator.remove();
            } else if (now >= pending.closeAt && pending.entity.progress(0) == 1
                    && door.block.swingClear(level, door.origin, door.lower.getValue(FurnitureBlock.FACING))
                    && setOpen(level, door, false, pending.actor)) {
                iterator.remove();
            }
        }
        if (doors.isEmpty()) PENDING.remove(level);
    }

    private static boolean finished(ServerLevel level, PendingClose pending, ServerPlayer player, Walk walk, long now) {
        if (now > walk.expiresAt || player.level() != level || player.isRemoved() || !player.isAlive()
                || player.isSpectator() || player.isPassenger() || player.getAbilities().flying) return true;
        // Use the server's movement position, including the original doorway's anchor offset.
        if (!walk.passage.clearedThreshold(pending.center, player.position())) return false;
        pending.closeAt = now + CLOSE_DELAY;
        pending.retryUntil = now + OBSTRUCTION_TIMEOUT;
        pending.actor = player;
        return true;
    }

    private static boolean setOpen(ServerLevel level, Target door, boolean open, @Nullable Player actor) {
        BlockPos root = door.origin;
        if (!FurnitureInteraction.setAssemblyState(level, door.block, root, List.of(root, root.above()),
                door.lower, door.lower.setValue(PanelDoorBlock.OPEN, open))) return false;
        Vec3 hinge = Vec3.atLowerCornerOf(root).add(AnchorRuntime.offset(level, root))
                .add(PanelDoorBlock.hinge(door.lower.getValue(FurnitureBlock.FACING))).add(0, 1, 0);
        level.playSound(null, hinge.x, hinge.y, hinge.z, open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE,
                SoundSource.BLOCKS, .8f, .95f + level.getRandom().nextFloat() * .1f);
        level.gameEvent(actor, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, root);
        return true;
    }
}
