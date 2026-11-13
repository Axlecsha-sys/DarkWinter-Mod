package com.dark.winter.placement;

import com.dark.winter.block.FurnitureBlock;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.WeakHashMap;

/** Per-player orientation for the held furniture, with the server deciding the actual placement. */
public final class FreeFurniturePlacement {
    private record Facing(Block block, float degrees) {}
    private static final Map<Player, Facing> CLIENT_ROTATIONS = new WeakHashMap<>();
    private static final Map<Player, Facing> SERVER_ROTATIONS = new WeakHashMap<>();
    private static final Map<Player, Boolean> CLIENT_SNAPS = new WeakHashMap<>();
    private static final Map<Player, Boolean> SERVER_SNAPS = new WeakHashMap<>();

    public record SetRotation(float angle) implements CustomPacketPayload {
        public static final Type<SetRotation> TYPE = new Type<>(Identifier.fromNamespaceAndPath("darkwinter", "furniture_angle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetRotation> CODEC =
                StreamCodec.composite(ByteBufCodecs.FLOAT, SetRotation::angle, SetRotation::new);

        @Override public Type<SetRotation> type() { return TYPE; }
    }

    public record SetSnap(boolean enabled) implements CustomPacketPayload {
        public static final Type<SetSnap> TYPE = new Type<>(Identifier.fromNamespaceAndPath("darkwinter", "furniture_snap"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetSnap> CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, SetSnap::enabled, SetSnap::new);

        @Override public Type<SetSnap> type() { return TYPE; }
    }

    private FreeFurniturePlacement() {}

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(SetRotation.TYPE, SetRotation.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SetSnap.TYPE, SetSnap.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SetRotation.TYPE, (payload, context) -> setRotation(context.player(), payload.angle()));
        ServerPlayNetworking.registerGlobalReceiver(SetSnap.TYPE, (payload, context) -> setSnap(context.player(), payload.enabled()));
    }

    public static boolean applies(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof FurnitureBlock furniture)) return false;
        if (RoofPlacement.isRoof(furniture)) return false;
        return switch (furniture.definition().id()) {
            case "cabin_stovepipe", "cabin_wall_lantern", "cabin_mug", "kerosene_lantern",
                    "canned_meat", "canned_fish", "canned_beans", "oat_carton", "cracker_box",
                    "ration_bundle", "water_bottle", "canvas_canteen", "vacuum_flask", "matchbox",
                    "folded_newspaper", "tied_newspapers", "rolled_newspaper" -> false;
            default -> furniture.definition().group().equals("residential")
                    || furniture.definition().id().startsWith("interior_");
        };
    }

    public static boolean canRotate(ItemStack stack) {
        return applies(stack) && !InteriorPlacement.wallMounted(((BlockItem) stack.getItem()).getBlock());
    }

    public static float currentRotation(Player player) {
        if (player == null || !canRotate(player.getMainHandItem())) return Float.NaN;
        Block block = ((BlockItem) player.getMainHandItem().getItem()).getBlock();
        Map<Player, Facing> rotations = rotations(player);
        synchronized (rotations) {
            Facing saved = rotations.get(player);
            return saved != null && saved.block() == block ? saved.degrees() : Float.NaN;
        }
    }

    public static float adjustRotation(Player player, float deltaDegrees) {
        if (!canRotate(player.getMainHandItem()) || player.isSpectator()) return Float.NaN;
        float current = currentRotation(player);
        if (!Float.isFinite(current)) current = cardinalDegrees(player.getDirection().getOpposite());
        return setRotation(player, current + deltaDegrees);
    }

    private static float setRotation(Player player, float degrees) {
        ItemStack held = player.getMainHandItem();
        if (!canRotate(held) || player.isSpectator() || Float.isInfinite(degrees)) return Float.NaN;
        Block block = ((BlockItem) held.getItem()).getBlock();
        Map<Player, Facing> rotations = rotations(player);
        synchronized (rotations) {
            if (Float.isNaN(degrees)) {
                rotations.remove(player);
                return Float.NaN;
            }
            float angle = (degrees % 360 + 360) % 360;
            rotations.put(player, new Facing(block, angle));
            return angle;
        }
    }

    static int cardinalDegrees(Direction direction) {
        return switch (direction) { case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0; };
    }

    public static boolean snapEnabled(Player player) {
        if (player == null) return false;
        var snaps = player.level().isClientSide() ? CLIENT_SNAPS : SERVER_SNAPS;
        synchronized (snaps) { return snaps.containsKey(player); }
    }

    public static boolean toggleSnap(Player player) {
        boolean enabled = !snapEnabled(player);
        setSnap(player, enabled);
        return enabled;
    }

    public static void setSnap(Player player, boolean enabled) {
        var snaps = player.level().isClientSide() ? CLIENT_SNAPS : SERVER_SNAPS;
        synchronized (snaps) {
            if (enabled) snaps.put(player, true);
            else snaps.remove(player);
        }
    }

    private static Map<Player, Facing> rotations(Player player) {
        // A local player and its integrated-server counterpart have the same entity ID.
        return player.level().isClientSide() ? CLIENT_ROTATIONS : SERVER_ROTATIONS;
    }
}
