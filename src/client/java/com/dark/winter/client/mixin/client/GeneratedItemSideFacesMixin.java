// Optimizes generated-item side quads while retaining the vanilla front and back faces.
package com.dark.winter.client.mixin.client;

import com.dark.winter.client.fixes.GeneratedItemSideFaces;
import com.mojang.math.Quadrant;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.FaceBakery;
import net.minecraft.client.resources.model.cuboid.ItemModelGenerator;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemModelGenerator.class)
public abstract class GeneratedItemSideFacesMixin {
    @Inject(method = "bakeSideFaces", at = @At("HEAD"), cancellable = true, require = 1)
    private static void darkwinter$mergeSideFaces(QuadCollection.Builder builder, ModelBaker.Interner interner,
            ModelState modelState, BakedQuad.MaterialInfo materialInfo, CallbackInfo ci) {
        var sprite = materialInfo.sprite().contents();
        var faces = GeneratedItemSideFaces.collect(sprite.width(), sprite.height(),
                sprite.getUniqueFrames().toIntArray(), (frame, x, y) -> !sprite.isTransparent(frame, x, y));
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        for (var face : faces) {
            var quad = face.quad(sprite.width(), sprite.height());
            from.set(quad.x0(), quad.y0(), 7.5F);
            to.set(quad.x1(), quad.y1(), 8.5F);
            Direction direction = switch (quad.side()) {
                case UP -> Direction.UP;
                case DOWN -> Direction.DOWN;
                case LEFT -> Direction.EAST;
                case RIGHT -> Direction.WEST;
            };
            var uvs = new CuboidFace.UVs(quad.u0(), quad.v0(), quad.u1(), quad.v1());
            builder.addUnculledFace(FaceBakery.bakeQuad(interner, from, to, uvs, Quadrant.R0,
                    materialInfo, direction, modelState, null));
        }
        ci.cancel();
    }
}
