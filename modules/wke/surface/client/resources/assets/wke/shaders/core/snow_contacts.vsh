// Authored snow geometry uses actual solar direction and a geometry-derived sky visibility.
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
layout(location=0) in vec4 Position;
layout(location=1) in vec4 UVLight;
layout(location=2) in vec4 NormalDepth;
layout(std140) uniform SnowView {
    vec4 WkeSnowCameraOffset;
    vec4 WkeSnowAtlasBounds;
    vec4 WkeSnowSun;
    vec4 WkeSnowLighting;
};
uniform sampler2D Sampler2;
layout(location=0) out float sphericalVertexDistance;
layout(location=1) out float cylindricalVertexDistance;
layout(location=2) out vec4 vertexColor;
layout(location=3) out vec2 snowUv;
layout(location=4) flat out int sharedWallHidden;
void main(){
    vec3 pos=Position.xyz+WkeSnowCameraOffset.xyz;
    gl_Position=ProjMat*ModelViewMat*vec4(pos,1);
    sharedWallHidden=0;
    if(Position.w<0.0){
        int side=int(-Position.w)-1;
        // pos is camera-relative. A positive outward plane distance puts the camera inside this owner.
        float outwardDistance=side==0?-pos.z:side==1?pos.x:side==2?pos.z:-pos.x;
        sharedWallHidden=outwardDistance>0.0?1:0;
    }
    sphericalVertexDistance=fog_spherical_distance(pos);
    cylindricalVertexDistance=fog_cylindrical_distance(pos);
    vec3 n=normalize(NormalDepth.xyz);
    float upperHemisphere=clamp(.5+.5*n.y,0.0,1.0);
    float skyAccess=clamp(UVLight.w/240.0,0.0,1.0);
    float solarWeight=clamp(WkeSnowSun.w*WkeSnowLighting.z*skyAccess,0.0,1.0);
    float solarFacing=max(dot(n,WkeSnowSun.xyz),0.0);
    // Position.w is visibility of the actual neighbouring upper-sky hemisphere, not a depth tint.
    float skyVisibility=Position.w<0.0?1.0:clamp(Position.w,0.0,1.0);
    float visibility=mix(1.0,skyVisibility,WkeSnowLighting.w);
    float ambient=WkeSnowLighting.x+WkeSnowLighting.y*upperHemisphere*visibility;
    float horizontal=(1.0-solarWeight)+solarWeight*max(WkeSnowSun.y,0.0);
    float irradiance=((1.0-solarWeight)*ambient+solarWeight*solarFacing)/max(horizontal,.05);
    vertexColor=sample_lightmap(Sampler2,ivec2(UVLight.zw))*vec4(vec3(irradiance),1);
    snowUv=mix(WkeSnowAtlasBounds.xy,WkeSnowAtlasBounds.zw,UVLight.xy);
}
