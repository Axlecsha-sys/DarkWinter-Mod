// Reconstructs sky directions without the projection translation introduced by view bobbing.
#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) out vec3 viewDirection;

vec3 unprojectDirectionPoint(mat4 inverseProjection, vec4 position) {
    vec4 point = inverseProjection * position;
    return point.xyz / point.w;
}

void main() {
    vec2 position = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2) * 2.0 - 1.0;
    gl_Position = vec4(position, 1.0, 1.0);
    mat4 inverseProjection = inverse(ProjMat);
    viewDirection = unprojectDirectionPoint(inverseProjection, vec4(position, 0.0, 1.0))
                  - unprojectDirectionPoint(inverseProjection, vec4(position, 1.0, 1.0));
}
