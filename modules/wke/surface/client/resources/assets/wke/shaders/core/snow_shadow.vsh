// The very same published snow vertices are projected into the real directional shadow depth.
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
layout(location=0) in vec4 Position;
layout(location=1) in vec4 UVLight;
layout(location=2) in vec4 NormalDepth;
layout(std140) uniform SnowView {
    vec4 WkeSnowCameraOffset;
    vec4 WkeSnowAtlasBounds;
    vec4 WkeSnowSun;
    vec4 WkeSnowLighting;
};
void main(){
    gl_Position=ProjMat*ModelViewMat*vec4(Position.xyz+WkeSnowCameraOffset.xyz,1);
}
