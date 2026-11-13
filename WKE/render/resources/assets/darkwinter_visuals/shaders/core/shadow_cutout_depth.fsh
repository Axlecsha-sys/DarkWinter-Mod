// Records cutout depth with the same WKE material sampling and alpha threshold as the main world pass.
#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <wke:material_sampling.glsl>
#include <minecraft:terrainglobals.glsl>

uniform sampler2D Sampler0;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = wkeSampleMaterial(Sampler0, texCoord0, 1.0 / vec2(TextureSize), UseRgss) * vertexColor;
    if (color.a < ALPHA_CUTOUT) discard;
    // The stock terrain vertex shader still supplies the lightmap-modulated vertex alpha.
    fragColor = vec4(0.0);
}
