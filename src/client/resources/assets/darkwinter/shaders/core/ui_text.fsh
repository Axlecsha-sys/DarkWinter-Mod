#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 0) out vec4 fragColor;

void main() {
    // Four subpixel samples integrate coverage at the actual on-screen size.
    // Limit the footprint to the atlas's transparent four-texel gutter.
    vec2 guard = vec2(3.0) / vec2(textureSize(Sampler0, 0));
    vec2 dx = dFdx(texCoord0) * 0.25;
    vec2 dy = dFdy(texCoord0) * 0.25;
    vec2 a = clamp(dx + dy, -guard, guard);
    vec2 b = clamp(dx - dy, -guard, guard);
    float coverage = 0.25 * (texture(Sampler0, texCoord0 + a).r
                          + texture(Sampler0, texCoord0 - a).r
                          + texture(Sampler0, texCoord0 + b).r
                          + texture(Sampler0, texCoord0 - b).r);
    vec4 ink = vertexColor * ColorModulator;
    // Coverage belongs in alpha; multiplying RGB by it as well darkens thin strokes twice.
    ink.a *= coverage;
    if (ink.a < (1.0 / 255.0)) discard;
    fragColor = ink;
}
