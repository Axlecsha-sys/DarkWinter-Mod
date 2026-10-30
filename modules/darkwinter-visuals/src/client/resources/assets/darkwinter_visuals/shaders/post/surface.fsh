// Applies directional surface shadows while preserving the source world image.
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MainSampler;
uniform sampler2D DepthSampler;
uniform sampler2D ShadowSampler;
#include <darkwinter_visuals:scene.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 source=texture(MainSampler,texCoord);
    float d=nlDepth(DepthSampler,texCoord);
    bool sky=nlSky(d);
    vec3 ray=nlRay(texCoord);
    vec3 p=nlReconstruct(texCoord,d),n=vec3(0,1,0);
    vec3 color=source.rgb;
    float blocked=0.0;
    float worldDistance=sky?Air.w:length(p);
    if(!sky) n=nlNormal(DepthSampler,texCoord,p);
    if(!sky && Flags.x>.5 && Flags.w<.5 && Sun.w>.001) {
        float facing=max(dot(n,Sun.xyz),0.0);
        vec3 q=nlLightCoord(p+n*Options.w);
        float validPlane=smoothstep(.025,.08,facing);
        float lit=nlSurfaceShadow(ShadowSampler,q,nlReceiverGradient(n),Shadow.z);
        blocked=(1.0-lit)*nlCoverage(q)*Sun.w*validPlane;
        float lowSun=0.35 + 0.65*smoothstep(.05,.20,Sun.y);
        float farShadowFade=1.0-smoothstep(85.0,180.0,worldDistance);
        float darkness=blocked*Shadow.w*(.36+.54*facing)*lowSun*farShadowFade;
        color*=vec3(1.0)-darkness*vec3(1.0,.90,.76);
    }
    // Atmospheric extinction is now applied once, after sky compositing.
    // Do not restore the old distance-colour mix here: it double-fogs the scene.
    if(Flags.z>.5 && Flags.z<1.5) {
        float sd=texture(ShadowSampler,texCoord).r;
        color=Flags.x>.5?vec3(sd):vec3(.5,0,.5);
    } else if(Flags.z>1.5 && Flags.z<2.5) {
        color=sky?vec3(.12,.20,.35):mix(vec3(.85),vec3(.05),blocked);
    } else if(Flags.z>3.5 && Flags.z<4.5) {
        color=sky?vec3(0):vec3(clamp(worldDistance/100.0,0.0,1.0));
    } else if(Flags.z>4.5 && Flags.z<5.5) color=sky?vec3(0):n*.5+.5;
    fragColor=vec4(clamp(color,0.0,1.0),source.a);
}
