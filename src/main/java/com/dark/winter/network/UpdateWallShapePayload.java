// Carries validated wall-shape updates from clients to the authoritative server.
package com.dark.winter.network;

import com.dark.winter.block.WallSegmentBlock;
import com.dark.winter.component.WallBuildManager;
import com.dark.winter.component.WallShape;
import com.dark.winter.registry.ModBlocks;
import com.dark.winter.registry.ModDataComponents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

public record UpdateWallShapePayload(WallShape shape) implements CustomPacketPayload {
    public static final Type<UpdateWallShapePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ModBlocks.MOD_ID, "update_wall_shape")
    );

    public static final StreamCodec<FriendlyByteBuf, UpdateWallShapePayload> CODEC = StreamCodec.composite(
            WallShape.STREAM_CODEC, UpdateWallShapePayload::shape,
            UpdateWallShapePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void registerServer() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);

        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                WallShape chosen = payload.shape();

                // 1. Запоминаем выбор игрока глобально на сервере
                WallBuildManager.setPlayerShape(player.getUUID(), chosen);

                // 2. Обновляем стак в руке (или выдаем блок в креативе, если рука пуста)
                ItemStack stack = player.getMainHandItem();
                if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof WallSegmentBlock) {
                    stack.set(ModDataComponents.WALL_SHAPE, chosen);
                } else if (player.isCreative() && stack.isEmpty()) {
                    ItemStack newStack = new ItemStack(ModBlocks.WALL_SEGMENT.asItem());
                    newStack.set(ModDataComponents.WALL_SHAPE, chosen);
                    player.setItemInHand(InteractionHand.MAIN_HAND, newStack);
                }

                // 3. Выводим уведомление над хотбаром через пакет ActionBar
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("Выбран модуль: ")
                                .append(Component.translatable(ModBlocks.wallMaterialKey(player.getMainHandItem())))
                                .append(" — " + chosen.displayName())
                ));
            });
        });
    }
}