// Occludes the underwater horizon with water fog using the 26.4 sky occluder's elevation range.
#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 viewDirection;
layout(location = 0) out vec4 fragColor;

void main() {
    vec3 worldDirection = normalize(transpose(mat3(ModelViewMat)) * viewDirection);
    float elevation = degrees(asin(clamp(worldDirection.y, -1.0, 1.0)));
    // Reversed smoothstep edges are undefined in GLSL; use the equivalent descending transition.
    float occlusion = 1.0 - smoothstep(20.0, 40.0, elevation);
    fragColor = vec4(ColorModulator.rgb, occlusion);
}
