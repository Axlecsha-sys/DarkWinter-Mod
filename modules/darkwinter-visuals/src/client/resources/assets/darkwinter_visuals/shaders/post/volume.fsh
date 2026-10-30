// Integrates volumetric sunlight and foreground or background haze along view rays.
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D DepthSampler;
uniform sampler2D ShadowSampler;
#include <darkwinter_visuals:scene.glsl>
#include <darkwinter_visuals:winter_haze.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    float depth=nlDepth(DepthSampler,texCoord);
    float distanceBlocks=nlSky(depth)?Air.w:min(length(nlReconstruct(texCoord,depth)),Air.w);
    float distanceCode=log2(1.0+distanceBlocks)/log2(1.0+Air.w);
    vec3 fogRay=nlRay(texCoord);
    float localDistance=nlSky(depth)?dwBackgroundFogDistance(fogRay):length(nlReconstruct(texCoord,depth));
    // RG preserve the old shaft/depth contract. BA carry independent foreground/background haze.
    float localFog=0.0,backgroundFog=0.0;
    if(Flags.w<.5 && Flags.z<.5 && FogRange.w>.5) {
        localFog=dwFogOpacity(fogRay,localDistance,true);
        backgroundFog=nlSky(depth)?localFog:dwFogOpacity(fogRay,dwBackgroundFogDistance(fogRay),true);
    }
    if(Flags.x<.5 || Flags.y<.5 || Flags.w>.5 || Sun.w<.001 || distanceBlocks<.1) {
        fragColor=vec4(0,distanceCode,localFog,backgroundFog);return;
    }
    vec3 direction=nlRay(texCoord);
    float forward=max(dot(direction,Sun.xyz),0.0);
    float phase=.008+.992*pow(forward,10.0);
    int count=int(clamp(WindTime.w,8.0,24.0));
    float stepLength=distanceBlocks/float(count);
    float jitter=.35+.30*nlHash(floor(gl_FragCoord.xy));
    float scattering=0.0,transmittance=1.0;
    for(int i=0;i<24;i++) {
        if(i>=count) break;
        vec3 p=direction*((float(i)+jitter)*stepLength);
        vec3 q=nlLightCoord(p);
        float lit=nlVolumeShadow(ShadowSampler,q,Shadow.z*.5)*nlCoverage(q);
        vec2 advected=p.xz+CameraInfo.xz-FogStyle.yz;
        vec2 wind=normalize(WindTime.xy+vec2(1e-4,0.0));
        float gust=.5+.5*sin(dot(advected,wind)*.041);
        float density=.018*(1.0+Climate.y*.35*gust)*clamp(Climate.w,.1,8.0);
        float stepTransmission=exp(-density*stepLength);
        scattering+=lit*transmittance*(1.0-stepTransmission);
        transmittance*=stepTransmission;
    }
    float lowSun=0.25 + 0.75*smoothstep(.05,.22,Sun.y);
    fragColor=vec4(clamp(scattering*phase*Sun.w*lowSun,0.0,1.0),distanceCode,localFog,backgroundFog);
}
