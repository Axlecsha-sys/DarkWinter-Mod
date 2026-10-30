// Exposes text-field editability and length limits to laboratory UI inspection.
package com.dark.winter.client.mixin.client;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(EditBox.class)
public interface LabEditAccess {
    @Accessor("isEditable") boolean darkwinter$editable();
    @Accessor("maxLength") int darkwinter$maxLength();
}
