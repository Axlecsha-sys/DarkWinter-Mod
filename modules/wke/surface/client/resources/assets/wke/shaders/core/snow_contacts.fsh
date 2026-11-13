// The same authored DarkWinter snow albedo as the surrounding terrain, with actual mesh normals/light.
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
uniform sampler2D Sampler0;
layout(location=0) in float sphericalVertexDistance;
layout(location=1) in float cylindricalVertexDistance;
layout(location=2) in vec4 vertexColor;
layout(location=3) in vec2 snowUv;
layout(location=4) flat in int sharedWallHidden;
layout(location=0) out vec4 fragColor;
void main(){
    if(sharedWallHidden!=0)discard;
    vec4 color=texture(Sampler0,snowUv)*vertexColor;
    fragColor=apply_fog(color,sphericalVertexDistance,cylindricalVertexDistance,
            FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,FogColor);
}
