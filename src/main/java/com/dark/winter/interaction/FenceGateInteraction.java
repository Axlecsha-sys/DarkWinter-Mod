package com.dark.winter.interaction;

import com.dark.winter.block.FenceGateEntity;
import com.dark.winter.block.FenceGateFurnitureBlock;
import com.dark.winter.block.FenceGateGeometry;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.placement.AnchorRuntime;
import com.dark.winter.registry.ModFurniture;
import java.util.List;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class FenceGateInteraction {
    public static final BlockEntityType<FenceGateEntity> TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("darkwinter", "furniture_gate"), FabricBlockEntityTypeBuilder.create(
                    FenceGateEntity::new, ModFurniture.all().stream().filter(FenceGateFurnitureBlock.class::isInstance)
                            .toArray(Block[]::new)).build());

    public record Use(BlockPos part, Vec3 aim, boolean expectedOpen) implements CustomPacketPayload {
        public static final Type<Use> TYPE = new Type<>(Identifier.fromNamespaceAndPath("darkwinter", "furniture_gate_use"));
        public static final StreamCodec<FriendlyByteBuf, Use> CODEC = StreamCodec.of(
                (buffer, request) -> {
                    buffer.writeBlockPos(request.part);
                    buffer.writeDouble(request.aim.x);
                    buffer.writeDouble(request.aim.y);
                    buffer.writeDouble(request.aim.z);
                    buffer.writeBoolean(request.expectedOpen);
                }, buffer -> new Use(buffer.readBlockPos(), new Vec3(buffer.readDouble(), buffer.readDouble(),
                        buffer.readDouble()), buffer.readBoolean()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Target(BlockPos origin, FenceGateFurnitureBlock block, BlockState state, List<BlockPos> parts) {
        public boolean open() { return state.getValue(FenceGateFurnitureBlock.OPEN); }
    }

    private FenceGateInteraction() {}

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(Use.TYPE, Use.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Use.TYPE, (packet, context) -> use(context.player(), packet));
    }

    public static @Nullable Target target(Level level, BlockPos part) {
        if (!level.hasChunkAt(part)) return null;
        BlockState state = level.getBlockState(part);
        if (!(state.getBlock() instanceof FenceGateFurnitureBlock block)
                || state.getValue(FurnitureBlock.PART) >= block.definition().parts().size()) return null;
        BlockPos root = block.origin(part, state).immutable();
        if (!level.hasChunkAt(root)) return null;
        BlockState base = level.getBlockState(root);
        if (!base.is(block) || !FenceGateFurnitureBlock.isRoot(base)) return null;
        List<BlockPos> parts = block.positions(root, base.getValue(FurnitureBlock.FACING));
        for (int i = 0; i < parts.size(); i++) {
            BlockPos pos = parts.get(i);
            if (!level.hasChunkAt(pos) || level.getBlockState(pos) != base.setValue(FurnitureBlock.PART, i)) return null;
        }
        return new Target(root, block, base, parts);
    }

    public static @Nullable Target reachable(Player player, BlockPos part, Vec3 aim) {
        Target target = target(player.level(), part);
        if (target == null) return null;
        BlockPos visible = FurnitureInteraction.visiblePart(player, aim);
        return visible != null && target.parts.contains(visible) ? target : null;
    }

    private static void use(ServerPlayer player, Use request) {
        if (!player.isAlive() || player.isRemoved() || player.isSpectator() || !player.mayBuild()
                || player.isUsingItem() || player.containerMenu != player.inventoryMenu) return;
        var level = player.level();
        Target target = reachable(player, request.part, request.aim);
        if (target == null || target.open() != request.expectedOpen) return;
        for (BlockPos part : target.parts) {
            if (!level.mayInteract(player, part) || !level.getWorldBorder().isWithinBounds(part)) return;
        }
        var facing = target.state.getValue(FurnitureBlock.FACING);
        if (!FenceGateGeometry.swingClear(level, target.origin, target.block, facing, target.parts)) return;
        boolean open = !target.open();
        if (!FurnitureInteraction.setAssemblyState(level, target.block, target.origin, target.parts, target.state,
                target.state.setValue(FenceGateFurnitureBlock.OPEN, open))) return;
        Vec3 hinge = Vec3.atLowerCornerOf(target.origin).add(AnchorRuntime.offset(level, target.origin))
                .add(FenceGateFurnitureBlock.hinge(facing)).add(0, .55, 0);
        boolean metal = target.block.definition().material().equals("metal");
        var sound = metal ? (open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE)
                : (open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE);
        level.playSound(null, hinge.x, hinge.y, hinge.z, sound, SoundSource.BLOCKS, .65f, 1);
        level.gameEvent(player, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, target.origin);
    }
}
