// Exposes registered key mappings to laboratory control discovery.
package com.dark.winter.client.mixin.client;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;
@Mixin(KeyMapping.class)
public interface LabKeyAccess {
    @Accessor("ALL") static Map<String, KeyMapping> darkwinter$all() { throw new AssertionError(); }
}
