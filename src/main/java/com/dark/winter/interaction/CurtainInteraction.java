package com.dark.winter.interaction;

import com.dark.winter.block.CurtainEntity;
import com.dark.winter.block.CurtainedWindowBlock;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class CurtainInteraction {
    public static final BlockEntityType<CurtainEntity> TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("darkwinter", "curtain"), FabricBlockEntityTypeBuilder.create(
                    CurtainEntity::new, ModFurniture.get(CurtainedWindowBlock.TRANSOM_ID),
                    ModFurniture.get(CurtainedWindowBlock.ATTIC_ID)).build());

    public record Use(BlockPos part, Vec3 aim, boolean expectedClosed) implements CustomPacketPayload {
        public static final Type<Use> TYPE = new Type<>(Identifier.fromNamespaceAndPath("darkwinter", "curtain_use"));
        public static final StreamCodec<FriendlyByteBuf, Use> CODEC = StreamCodec.of(
                (buffer, request) -> {
                    buffer.writeBlockPos(request.part);
                    buffer.writeDouble(request.aim.x);
                    buffer.writeDouble(request.aim.y);
                    buffer.writeDouble(request.aim.z);
                    buffer.writeBoolean(request.expectedClosed);
                }, buffer -> new Use(buffer.readBlockPos(), new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Target(BlockPos origin, CurtainedWindowBlock block, BlockState state, List<BlockPos> parts) {
        public boolean closed() { return state.getValue(CurtainedWindowBlock.CURTAINS_CLOSED); }
    }

    private CurtainInteraction() {}

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(Use.TYPE, Use.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Use.TYPE, (packet, context) -> use(context.player(), packet));
    }

    public static @Nullable Target target(Level level, BlockPos part) {
        if (!level.hasChunkAt(part)) return null;
        BlockState state = level.getBlockState(part);
        if (!(state.getBlock() instanceof CurtainedWindowBlock window)
                || state.getValue(FurnitureBlock.PART) >= window.definition().parts().size()) return null;
        BlockPos root = window.origin(part, state).immutable();
        if (!level.hasChunkAt(root)) return null;
        BlockState base = level.getBlockState(root);
        if (!CurtainedWindowBlock.isRoot(base) || !base.is(window)) return null;
        List<BlockPos> parts = window.positions(root, base.getValue(FurnitureBlock.FACING));
        for (int i = 0; i < parts.size(); i++) {
            BlockPos pos = parts.get(i);
            if (!level.hasChunkAt(pos) || level.getBlockState(pos) != base.setValue(FurnitureBlock.PART, i)) return null;
        }
        return new Target(root, window, base, parts);
    }

    public static @Nullable Target reachable(Player player, BlockPos part, Vec3 aim) {
        Target target = target(player.level(), part);
        if (target == null) return null;
        BlockPos visiblePart = FurnitureInteraction.visiblePart(player, aim);
        return visiblePart != null && target.parts.contains(visiblePart) ? target : null;
    }

    private static void use(ServerPlayer player, Use request) {
        if (!player.isAlive() || player.isRemoved() || player.isSpectator() || !player.mayBuild()
                || player.isUsingItem() || player.containerMenu != player.inventoryMenu) return;
        var level = player.level();
        Target target = reachable(player, request.part, request.aim);
        if (target == null || target.closed() != request.expectedClosed) return;
        for (BlockPos part : target.parts) {
            if (!level.mayInteract(player, part) || !level.getWorldBorder().isWithinBounds(part)) return;
        }
        BlockState next = target.state.setValue(CurtainedWindowBlock.CURTAINS_CLOSED, !target.closed());
        boolean changed = FurnitureInteraction.setAssemblyState(level, target.block, target.origin,
                target.parts, target.state, next);
        if (changed) {
            Vec3 at = Vec3.atCenterOf(target.origin).add(AnchorRuntime.offset(level, target.origin));
            level.playSound(null, at.x, at.y, at.z, SoundEvents.WOOL_HIT, SoundSource.BLOCKS, .28f, .8f);
        }
    }
}
