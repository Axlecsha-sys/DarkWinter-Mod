#version 330
#extension GL_ARB_separate_shader_objects : require

// WKE terrain stage for the Minecraft host material and transparency contract.
#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <wke:material_sampling.glsl>
#include <minecraft:oit.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
#include <minecraft:chunksection.glsl>
#endif

uniform sampler2D Sampler0;

layout(location = 0) in float wkeSphereDistance;
layout(location = 1) in float wkeCylinderDistance;
layout(location = 2) in vec4 wkeLitTint;
layout(location = 3) in vec2 wkeAtlasUv;
layout(location = 4) in float wkeSectionFade;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 wkeReadMaterial() {
    return wkeSampleMaterial(Sampler0, wkeAtlasUv, 1.0 / vec2(TextureSize), UseRgss);
}

#ifndef OIT_ALPHA_ONLY
vec4 wkeApplyAtmosphere(vec4 surface) {
    vec4 atmosphericColor = FogColor;
#ifdef OIT_ACCUMULATE
    surface = sampleColorForAccumulation(surface);
    atmosphericColor.rgb *= surface.a;
#endif
    return apply_fog(surface, wkeSphereDistance, wkeCylinderDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd,
        FogRenderDistanceStart, FogRenderDistanceEnd, atmosphericColor);
}
#endif

void main() {
    vec4 surface = wkeReadMaterial() * wkeLitTint;
#ifndef OIT_ALPHA_ONLY
    vec4 sectionHorizon = vec4(FogColor.rgb, FogColor.a * surface.a);
    surface = mix(sectionHorizon, surface, wkeSectionFade);
#endif
#ifdef ALPHA_CUTOUT
    if (surface.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, surface.a);
#else
    fragColor = wkeApplyAtmosphere(surface);
#endif
}
