// Composites the procedural sky and clouds with depth-aware world-height haze.
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MainSampler;
uniform sampler2D DepthSampler;
uniform sampler2D AtmosphereSampler;
uniform sampler2D NativeSkySampler;
uniform sampler2D NativeCloudSampler;
#include <darkwinter_visuals:scene.glsl>
#include <darkwinter_visuals:winter_haze.glsl>
#include <darkwinter_visuals:cloud_shell.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

float gatherShafts(vec2 uv,float expectedDistance) {
    vec2 size=vec2(textureSize(AtmosphereSampler,0));
    vec2 base=floor(uv*size-.5);
    vec2 fraction=fract(uv*size-.5);
    float result=0.0,weightSum=0.0;
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        vec2 q=(base+vec2(x,y)+.5)/size;
        vec2 rg=texelFetch(AtmosphereSampler,clamp(ivec2(base)+ivec2(x,y),ivec2(0),ivec2(size)-1),0).rg;
        float d=exp2(rg.g*log2(1.0+Air.w))-1.0;
        vec2 bilinear=mix(vec2(1.0)-fraction,fraction,vec2(x,y));
        float sameSurface=exp(-abs(d-expectedDistance)/max(1.0,expectedDistance*.08));
        float weight=bilinear.x*bilinear.y*sameSurface;
        result+=rg.r*weight;weightSum+=weight;
    }
    return result/max(weightSum,1e-6);
}
// Native-background-only graph: recreate the removed composite RGBA8 texel
// before any height haze. The renderer keeps legacy/debug/fluid frames on the old graph.
vec4 dwCompositeSource(vec2 uv) {
    ivec2 size=textureSize(MainSampler,0);
    ivec2 pixel=clamp(ivec2(floor(uv*vec2(size))),ivec2(0),size-1);
    vec2 centre=(vec2(pixel)+.5)/vec2(size);
    vec4 source=texelFetch(MainSampler,pixel,0);
    float shafts=0.0;
    // volume.fsh writes exact R=0 for the whole image when Sun.w<.001.
    if(Sun.w>=.001) {
        float depth=nlDepth(DepthSampler,centre);
        float distanceBlocks=nlSky(depth)?Air.w:min(length(nlReconstruct(centre,depth)),Air.w);
        shafts=gatherShafts(centre,distanceBlocks);
    }
    vec3 color=source.rgb;
    vec3 coldSun=vec3(.80,.88,1.0);
    color+=coldSun*shafts*Air.z*(1.0-clamp(color,0.0,1.0)*.8);
    // Preserve the former UNORM8 clamp/round boundary, not a new HDR composition.
    // A hardware half-way rounding choice may differ by one intermediate code.
    return floor(clamp(vec4(color,source.a),0.0,1.0)*255.0+.5)/255.0;
}

// Local haze uses depth-aware reconstruction. Background haze ignores terrain silhouettes.
// This distinction prevents halos and holes in the fog behind narrow trees.
vec2 gatherHaze(vec2 uv,vec3 ray,float expectedDistance,bool sky) {
    if(FogRange.w<.5) return vec2(0.0);
    ivec2 size=textureSize(AtmosphereSampler,0);
    vec2 pos=uv*vec2(size)-.5;
    ivec2 cell=ivec2(floor(pos));
    vec2 f=fract(pos);
    float local=0.0,total=0.0,background=0.0;
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        ivec2 p=clamp(cell+ivec2(x,y),ivec2(0),size-1);
        vec2 q=(vec2(p)+.5)/vec2(size);
        vec2 factors=mix(vec2(1.0)-f,f,vec2(x,y));
        float bilinear=factors.x*factors.y;
        vec2 fog=texelFetch(AtmosphereSampler,p,0).ba;
        background+=fog.y*bilinear;
        if(!sky) {
            float depth=nlDepth(DepthSampler,q);
            if(nlSky(depth)) continue;
            float actualDistance=length(nlReconstruct(q,depth));
            float separation=abs(actualDistance-expectedDistance)/max(1.0,expectedDistance*.025);
            float weight=bilinear*exp(-separation*separation);
            local+=fog.x*weight;
            total+=weight;
        }
    }
    // For a subpixel leaf/pole with no matching low-res sample, use the analytic profile.
    // It is preferable to a sampled background fog alpha painted onto foreground geometry.
    if(sky) local=background;
    else local=total>0.0001?local/total:dwFogOpacity(ray,expectedDistance,false);
    return clamp(vec2(local,background),0.0,1.0);
}

// Sky and clouds have already passed through the native depth/OIT path.
// This stage owns only the independently enabled world-height atmosphere.
void main() {
    vec4 source=dwCompositeSource(texCoord);
    if(Flags.w>.5 || Flags.z>.5 || FogRange.w<.5) { fragColor=source;return; }
    float depth=nlDepth(DepthSampler,texCoord);
    bool sky=nlSky(depth);
    vec3 ray=nlRay(texCoord);
    vec3 p=sky?vec3(0):nlReconstruct(texCoord,depth);
    float distanceBlocks=sky?dwBackgroundFogDistance(ray):length(p);
    vec2 fog=gatherHaze(texCoord,ray,distanceBlocks,sky);
    vec3 air=dwFogColour(ray);
    if(sky) {
        fragColor=vec4(mix(source.rgb,air,fog.y),source.a);
        return;
    }
    vec3 color=mix(source.rgb,air,fog.x);
    float edgeFade=smoothstep(FogRange.x,FogRange.y,length(p.xz))*dwTerrainEdgeOpacity(ray,distanceBlocks);
    if(edgeFade>0.0) {
        vec3 background=texture(NativeSkySampler,texCoord).rgb;
        vec4 cloud=texture(NativeCloudSampler,texCoord);
        float alpha=cloud.a;
        if(nlModuleFlag(2)) alpha=dwVisibleCloudAlpha(ray,dwBackgroundSurfaceDistance(ray),alpha);
        vec3 cloudColor=cloud.rgb/max(cloud.a,1e-6);
        background=mix(background,cloudColor,alpha);
        // An unavailable distant background must never remove extinction already
        // accumulated by the actual foreground along this ray.
        background=mix(background,air,max(fog.y,fog.x));
        color=mix(color,background,edgeFade);
    }
    fragColor=vec4(clamp(color,0.0,1.0),source.a);
}
