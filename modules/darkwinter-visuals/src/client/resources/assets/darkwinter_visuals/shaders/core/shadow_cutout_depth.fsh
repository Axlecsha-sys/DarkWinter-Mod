// Records cutout terrain depth with the stock atlas sampling and exact alpha threshold.
#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>

uniform sampler2D Sampler0;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize)
                             : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    if (color.a < ALPHA_CUTOUT) discard;
    // The stock terrain vertex shader still supplies the lightmap-modulated vertex alpha.
    fragColor = vec4(0.0);
}
