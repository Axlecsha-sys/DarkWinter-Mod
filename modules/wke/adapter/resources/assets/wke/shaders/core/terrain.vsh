#version 330
#extension GL_ARB_separate_shader_objects : require

// Minecraft host-format adapter for WKE's borrowed-device terrain renderer.
// The host supplies authoritative transforms, baked light and atlas resources.
#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
#include <minecraft:chunksection.glsl>
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float wkeSphereDistance;
layout(location = 1) out float wkeCylinderDistance;
layout(location = 2) out vec4 wkeLitTint;
layout(location = 3) out vec2 wkeAtlasUv;
layout(location = 4) out float wkeSectionFade;

vec3 wkeCameraRelativeVertex() {
    // Subtract integral section coordinates before converting to floats.
    // This preserves host precision in worlds far from the origin.
    return Position + vec3(ChunkPosition - CameraBlockPos) + CameraOffset;
}

void main() {
    vec3 relative = wkeCameraRelativeVertex();
    gl_Position = ProjMat * ModelViewMat * vec4(relative, 1.0);
    wkeSphereDistance = fog_spherical_distance(relative);
    wkeCylinderDistance = fog_cylindrical_distance(relative);
    wkeAtlasUv = UV0;
    wkeLitTint = Color;
#ifndef OIT_ALPHA_ONLY
    wkeLitTint *= sample_lightmap(Sampler2, UV2);
#endif
    // Keep nearby geometry opaque while newly published far sections fade in.
    float farWeight = clamp((length(relative) - 16.0) / 16.0, 0.0, 1.0);
    wkeSectionFade = mix(1.0, ChunkVisibility, farWeight);
}
