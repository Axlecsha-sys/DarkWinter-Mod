#ifndef WKE_MATERIAL_SAMPLING_GLSL
#define WKE_MATERIAL_SAMPLING_GLSL
#include <minecraft:texture_sampling.glsl>

#ifdef WKE_FOLIAGE_TEXTURES
uniform sampler2D WkeNeedlesSampler;
uniform sampler2D WkeBranchSampler;
layout(std140) uniform WkeFoliage {
    vec4 WkeFoliageRects[2];
    vec4 WkeFoliageParams; // enabled, requested atlas mips, layer count, full-chain maximum LOD
};
#endif

vec4 wkeSampleAtlas(sampler2D source, vec2 uv, vec2 texelSize, vec2 du, vec2 dv, int rgss) {
    vec2 footprint = sqrt(du * du + dv * dv);
    float texelsPerPixel = max(length(du / texelSize), length(dv / texelSize));
    if (rgss == 1 && texelsPerPixel > 1.0) {
        // Rotated sample positions are in SCREEN pixels, not source texels.
        // Explicit gradients keep the true footprint and hardware anisotropy.
        vec4 sum = textureGrad(source, uv + du * .125 + dv * .375, du, dv);
        sum += textureGrad(source, uv - du * .125 - dv * .375, du, dv);
        sum += textureGrad(source, uv + du * .375 - dv * .125, du, dv);
        sum += textureGrad(source, uv - du * .375 + dv * .125, du, dv);
        return sum * .25;
    }
    if (texelsPerPixel >= 1.0) return textureGrad(source, uv, du, dv);
    // Retain the host's sharp magnification choice; never remap minified UVs.
    vec2 safeFootprint = max(footprint, texelSize * 1e-6);
    vec4 sharp = sampleNearest(source, uv, texelSize, du, dv, safeFootprint);
    if (texelsPerPixel <= .75) return sharp;
    return mix(sharp, textureGrad(source, uv, du, dv), smoothstep(.75, 1.0, texelsPerPixel));
}

vec4 wkeSampleMaterial(sampler2D source, vec2 uv, vec2 texelSize, int rgss) {
    // Evaluate derivatives before material selection or alpha discard.
    vec2 du = dFdx(uv), dv = dFdy(uv);
#ifdef WKE_FOLIAGE_TEXTURES
    if (WkeFoliageParams.x > .5) {
        for (int layer = 0; layer < 2; ++layer) {
            vec4 bounds = WkeFoliageRects[layer];
            vec2 extent = bounds.zw - bounds.xy;
            if (all(greaterThan(extent, vec2(0.0))) &&
                all(greaterThanEqual(uv, bounds.xy)) && all(lessThanEqual(uv, bounds.zw))) {
                vec2 localUv = (uv - bounds.xy) / extent;
                vec2 localDu = du / extent, localDv = dv / extent;
                if (layer == 0) return wkeSampleAtlas(WkeNeedlesSampler, localUv,
                    1.0 / vec2(textureSize(WkeNeedlesSampler, 0)), localDu, localDv, rgss);
                return wkeSampleAtlas(WkeBranchSampler, localUv,
                    1.0 / vec2(textureSize(WkeBranchSampler, 0)), localDu, localDv, rgss);
            }
        }
    }
#endif
    return wkeSampleAtlas(source, uv, texelSize, du, dv, rgss);
}
#endif
