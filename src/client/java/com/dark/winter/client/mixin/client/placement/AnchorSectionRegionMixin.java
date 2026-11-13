package com.dark.winter.client.mixin.client.placement;

import com.dark.winter.placement.AnchorLevelView;
import com.dark.winter.placement.AnchorRuntime;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pins one published immutable anchor snapshot for an entire worker-side section compilation. */
@Mixin(RenderSectionRegion.class)
abstract class AnchorSectionRegionMixin implements AnchorLevelView {
    @Unique private Map<Long, Vec3> darkwinter$anchorSnapshot = Map.of();
    @Unique private Map<Long, Double> darkwinter$anchorRotations = Map.of();

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void darkwinter$captureAnchors(ClientLevel level, int minSectionX, int minSectionY,
            int minSectionZ, SectionCopy[] sections, CallbackInfo callback) {
        darkwinter$anchorSnapshot = AnchorRuntime.offsets(level);
        darkwinter$anchorRotations = AnchorRuntime.rotations(level);
    }

    @Override
    public Map<Long, Vec3> darkwinter$anchorOffsets() {
        return darkwinter$anchorSnapshot;
    }

    @Override
    public Map<Long, Double> darkwinter$anchorRotations() {
        return darkwinter$anchorRotations;
    }
}
