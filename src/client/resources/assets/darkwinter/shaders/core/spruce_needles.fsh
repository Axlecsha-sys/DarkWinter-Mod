// Shades textured spruce needles with alpha clipping and native distance fog.
#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <wke:material_sampling.glsl>

uniform sampler2D Sampler0;
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float detailVisibility;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = wkeSampleMaterial(Sampler0, texCoord0, 1.0 / vec2(textureSize(Sampler0, 0)), UseRgss) * vertexColor;
    if (color.a < 0.5 || detailVisibility < 0.01) discard;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
