// Exposes only the isolated camera's cached projection matrix for centered world orthographic framing.
package com.dark.winter.biomeeditor.mixin;

import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Projection.class)
public interface EditorProjectionAccess {
    @Accessor("matrix") Matrix4f editor$matrix();
}
