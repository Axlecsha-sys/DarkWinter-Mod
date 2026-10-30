// Exposes inventory-slot coordinates for native inventory layout integration.
package com.dark.winter.client.mixin.client;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Slot.class)
public interface DarkWinterSlotAccessor {
    @Mutable
    @Accessor("x")
    void darkwinter$setX(int x);

    @Mutable
    @Accessor("y")
    void darkwinter$setY(int y);
}
