package com.dark.winter.interaction;

import com.dark.winter.block.DiningChairBlock;
import com.dark.winter.block.DiningChairEntity;
import com.dark.winter.block.FurnitureBlock;
import com.dark.winter.placement.AnchorRuntime;
import com.dark.winter.placement.DiningChairPlacement;
import com.dark.winter.placement.FurnitureTransform;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class DiningChairInteraction {
    public static final BlockEntityType<DiningChairEntity> TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("darkwinter", "dining_chair"),
            FabricBlockEntityTypeBuilder.create(DiningChairEntity::new, ModFurniture.get(DiningChairBlock.ID)).build());

    public record Target(BlockState state, boolean pull) {}

    public record Use(BlockPos pos, Vec3 aim, boolean tucked) implements CustomPacketPayload {
        public static final Type<Use> TYPE = new Type<>(Identifier.fromNamespaceAndPath("darkwinter", "dining_chair_use"));
        public static final StreamCodec<FriendlyByteBuf, Use> CODEC = StreamCodec.of((buffer, request) -> {
            buffer.writeBlockPos(request.pos);
            buffer.writeDouble(request.aim.x);
            buffer.writeDouble(request.aim.y);
            buffer.writeDouble(request.aim.z);
            buffer.writeBoolean(request.tucked);
        }, buffer -> new Use(buffer.readBlockPos(), new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private DiningChairInteraction() {}

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(Use.TYPE, Use.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Use.TYPE, (packet, context) ->
                context.server().execute(() -> use(context.player(), packet)));
    }

    public static @Nullable Target reachable(Player player, BlockPos pos, Vec3 aim) {
        if (!player.level().hasChunkAt(pos)) return null;
        var state = player.level().getBlockState(pos);
        if (!DiningChairBlock.isRoot(state) || !pos.equals(FurnitureInteraction.visiblePart(player, aim))) return null;
        if (player.level().getBlockEntity(pos) instanceof DiningChairEntity chair) {
            float progress = chair.progress(0);
            if (progress > 0 && progress < 1) return null;
        }
        if (!DiningChairBlock.hasTravel(state)) {
            Boolean tucked = DiningChairPlacement.tuckedAtPlacement(player.level(), pos, state);
            return tucked == null ? null : new Target(state, tucked);
        }
        boolean tucked = DiningChairBlock.isTucked(state);
        return tucked || DiningChairPlacement.hasTable(player.level(), pos, state) ? new Target(state, tucked) : null;
    }

    private static void use(ServerPlayer player, Use request) {
        if (!player.isAlive() || player.isSpectator() || !player.mayBuild()
                || player.isUsingItem() || player.containerMenu != player.inventoryMenu) return;
        var level = player.level();
        BlockPos pos = request.pos;
        if (!level.mayInteract(player, pos) || !level.getWorldBorder().isWithinBounds(pos)) return;
        Target target = reachable(player, pos, request.aim);
        if (target == null || target.state.getValue(DiningChairBlock.TUCKED) != request.tucked) return;
        BlockState chair = target.state;
        BlockState next = DiningChairPlacement.slideTarget(level, pos, chair, target.pull);
        if (next == null) return;
        if (FurnitureInteraction.setAssemblyState(level, (FurnitureBlock)chair.getBlock(), pos, List.of(pos), chair, next)) {
            double yaw = AnchorRuntime.rotation(level, pos);
            Vec3 local = new Vec3(.5, .2, .5).add(DiningChairBlock.slide(next, .5f));
            Vec3 at = Vec3.atLowerCornerOf(pos).add(AnchorRuntime.offset(level, pos))
                    .add(FurnitureTransform.point(local, Vec3.ZERO, yaw));
            level.playSound(null, at.x, at.y, at.z, SoundEvents.WOOD_STEP, SoundSource.BLOCKS, .65f, .72f);
        }
    }
}
