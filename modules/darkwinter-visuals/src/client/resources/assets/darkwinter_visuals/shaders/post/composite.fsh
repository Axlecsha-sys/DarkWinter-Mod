// Composites depth-aware volumetric light shafts into the world image.
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MainSampler;
uniform sampler2D DepthSampler;
uniform sampler2D VolumeSampler;
#include <darkwinter_visuals:scene.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

float gatherShafts(vec2 uv,float expectedDistance) {
    vec2 size=vec2(textureSize(VolumeSampler,0));
    vec2 base=floor(uv*size-.5);
    vec2 fraction=fract(uv*size-.5);
    float result=0.0,weightSum=0.0;
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        vec2 q=(base+vec2(x,y)+.5)/size;
        vec2 rg=texelFetch(VolumeSampler,clamp(ivec2(base)+ivec2(x,y),ivec2(0),ivec2(size)-1),0).rg;
        float d=exp2(rg.g*log2(1.0+Air.w))-1.0;
        vec2 bilinear=mix(vec2(1.0)-fraction,fraction,vec2(x,y));
        float sameSurface=exp(-abs(d-expectedDistance)/max(1.0,expectedDistance*.08));
        float weight=bilinear.x*bilinear.y*sameSurface;
        result+=rg.r*weight;weightSum+=weight;
    }
    return result/max(weightSum,1e-6);
}
void main() {
    vec4 source=texture(MainSampler,texCoord);
    float depth=nlDepth(DepthSampler,texCoord);
    float distanceBlocks=nlSky(depth)?Air.w:min(length(nlReconstruct(texCoord,depth)),Air.w);
    float shafts=gatherShafts(texCoord,distanceBlocks);
    vec3 color=source.rgb;
    if(Flags.z>.5) {
        if(Flags.z>2.5 && Flags.z<3.5) color=vec3(shafts);
        fragColor=vec4(color,source.a);return;
    }
    vec3 coldSun=vec3(.80,.88,1.0);
    // Integrated scattering is clamped to 0..1. Air.z bounds added light per channel.
    color+=coldSun*shafts*Air.z*(1.0-clamp(color,0.0,1.0)*.8);
    fragColor=vec4(clamp(color,0.0,1.0),source.a);
}
