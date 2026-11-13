// Replaces only primitive cache storage while retaining native miss computation and disabled branches.
package com.dark.winter.client.mixin.client;
import com.dark.winter.client.fixes.render.SectionLightCache;
import it.unimi.dsi.fastutil.longs.Long2FloatLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.client.renderer.block.BlockModelLighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockModelLighter.Cache.class, remap = false)
abstract class SectionLightCacheMixin {
    @Unique private SectionLightCache dw$sectionLightCache;
    @Inject(method = "<init>()V", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private void dw$allocateDirectTables(CallbackInfo ci) {
        if (SectionLightCache.enabled())
            dw$sectionLightCache = new SectionLightCache();
    }

    @Redirect(method = "lambda$new$2(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;)I",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2IntLinkedOpenHashMap;get(J)I"),
        require = 1, expect = 1, allow = 1)
    private int dw$rawGet(Long2IntLinkedOpenHashMap map, long key) {
        return dw$sectionLightCache == null ? map.get(key) : dw$sectionLightCache.rawGet(key);
    }
    @Redirect(method = "lambda$new$2(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;)I",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2IntLinkedOpenHashMap;put(JI)I"),
        require = 1, expect = 1, allow = 1)
    private int dw$rawPut(Long2IntLinkedOpenHashMap map, long key, int value) {
        return dw$sectionLightCache == null ? map.put(key, value) : dw$sectionLightCache.rawPut(key, value);
    }
    @Redirect(method = "getShadeBrightness(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)F",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2FloatLinkedOpenHashMap;get(J)F"),
        require = 1, expect = 1, allow = 1)
    private float dw$shadeGet(Long2FloatLinkedOpenHashMap map, long key) {
        return dw$sectionLightCache == null ? map.get(key) : dw$sectionLightCache.shadeGet(key);
    }
    @Redirect(method = "getShadeBrightness(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)F",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2FloatLinkedOpenHashMap;put(JF)F"),
        require = 1, expect = 1, allow = 1)
    private float dw$shadePut(Long2FloatLinkedOpenHashMap map, long key, float value) {
        return dw$sectionLightCache == null ? map.put(key, value) : dw$sectionLightCache.shadePut(key, value);
    }
    @Inject(method = "enable()V", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private void dw$resetBeforeEnabledScope(CallbackInfo ci) {
        if (dw$sectionLightCache != null) dw$sectionLightCache.clear();
    }
    @Inject(method = "disable()V", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private void dw$resetAfterDisabledScope(CallbackInfo ci) {
        if (dw$sectionLightCache != null) dw$sectionLightCache.clear();
    }
}
