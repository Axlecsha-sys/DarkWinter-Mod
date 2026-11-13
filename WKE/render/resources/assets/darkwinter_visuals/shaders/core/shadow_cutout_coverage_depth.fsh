// Exact early acceptance of normalized RGSS alpha; preserves original cutout depth.
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
#include <wke:material_shadow_coverage.glsl>
#include <minecraft:terrainglobals.glsl>
uniform sampler2D Sampler0;
layout(location=2) in vec4 vertexColor;
layout(location=3) in vec2 texCoord0;
layout(location=0) out vec4 fragColor;
void main() {
    if(!wkeShadowMaterialCovers(Sampler0,texCoord0,1.0/vec2(TextureSize),UseRgss,vertexColor.a,ALPHA_CUTOUT)) discard;
    fragColor=vec4(0.0);
}
