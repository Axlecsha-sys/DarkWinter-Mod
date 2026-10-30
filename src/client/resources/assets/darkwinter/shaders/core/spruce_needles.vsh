// Animates spruce twig and needle geometry with bounded wind displacement.
#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 AnchorPhase;
layout(location = 3) in vec4 DirectionLength;
layout(location = 4) in vec4 NeedleColor;
layout(location = 5) in vec4 LightSize;

layout(std140) uniform SpruceWind {
    vec4 NeedleWind; // World X/Z flow, bounded displacement, game-time seconds.
    vec4 NeedleCameraOffset;
};

uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float detailVisibility;

void main() {
    vec3 direction = DirectionLength.xyz;
    vec3 tangent = abs(direction.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : normalize(cross(direction, vec3(0.0, 1.0, 0.0)));
    vec3 bitangent = cross(direction, tangent);
    bool woody = Position.z > 1.5;
    float tip = Position.y;
    vec3 anchor = AnchorPhase.xyz + NeedleCameraOffset.xyz;
    float visible = 1.0 - smoothstep(NeedleCameraOffset.w - 5.0, NeedleCameraOffset.w, length(anchor));
    vec3 crossSection;
    if (woody) {
        float radius = LightSize.w * mix(1.0, 0.24, tip);
        crossSection = (tangent * Position.x + bitangent * (Position.z - 3.0)) * radius;
    } else {
        vec3 side = Position.z < 0.5 ? tangent : bitangent;
        crossSection = side * (Position.x * LightSize.z);
    }
    vec3 local = direction * (tip * DirectionLength.w) + crossSection;

    float phase = NeedleWind.w * 1.7 + AnchorPhase.w;
    float gust = 0.72 + 0.20 * sin(phase) + 0.08 * sin(phase * 2.31 + AnchorPhase.w);
    vec2 sway = NeedleWind.xy * gust + vec2(-NeedleWind.y, NeedleWind.x) * (0.1 * sin(phase * 1.37));
    // Four axial segments use identical deformation for wood and attached needles. The root
    // stays pinned to the chunk-rendered scaffold; stronger climate winds flex the entire twig.
    local.xz += sway * (NeedleWind.z * tip * tip);
    vec3 pos = anchor + local * visible;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vec4 materialColor = woody ? vec4(0.90, 0.87, 0.81, 1.0) : NeedleColor;
    vertexColor = materialColor * sample_lightmap(Sampler2, ivec2(LightSize.xy));
    texCoord0 = UV0;
    detailVisibility = visible;
}
