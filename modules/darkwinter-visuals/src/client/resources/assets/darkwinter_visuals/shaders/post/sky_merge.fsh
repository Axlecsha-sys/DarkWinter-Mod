// Composites the procedural sky and clouds with depth-aware world-height haze.
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MainSampler;
uniform sampler2D DepthSampler;
uniform sampler2D SkySampler;
uniform sampler2D AtmosphereSampler;
uniform sampler2D NativeSkySampler;
uniform sampler2D NativeCloudSampler;
#include <darkwinter_visuals:scene.glsl>
#include <darkwinter_visuals:winter_haze.glsl>
#include <darkwinter_visuals:cloud_shell.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

vec4 smoothCloud(vec2 uv) {
    ivec2 size=textureSize(SkySampler,0);
    vec2 pos=uv*vec2(size)-.5;
    ivec2 cell=ivec2(floor(pos));
    vec2 f=fract(pos);
    ivec2 hi=size-1;
    vec4 a=texelFetch(SkySampler,clamp(cell,ivec2(0),hi),0);
    vec4 b=texelFetch(SkySampler,clamp(cell+ivec2(1,0),ivec2(0),hi),0);
    vec4 c=texelFetch(SkySampler,clamp(cell+ivec2(0,1),ivec2(0),hi),0);
    vec4 d=texelFetch(SkySampler,clamp(cell+ivec2(1,1),ivec2(0),hi),0);
    return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
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

vec3 celestialDetail(vec2 uv,vec3 center) {
    // Replace vanilla's broad day/night fog tint, but preserve small sun/moon/star discs.
    vec2 px=1.0/vec2(textureSize(MainSampler,0));
    vec3 centreRay=nlRay(uv);
    vec3 rayDx=nlRay(uv+vec2(px.x,0))-centreRay;
    vec3 rayDy=nlRay(uv+vec2(0,px.y))-centreRay;
    float angularPixel=max(1e-6,max(length(rayDx),length(rayDy)));
    vec2 offsets[8]=vec2[8](vec2(1,0),vec2(-1,0),vec2(0,1),vec2(0,-1),
        vec2(.707,.707),vec2(-.707,.707),vec2(.707,-.707),vec2(-.707,-.707));
    // An averaged distant sample containing the Moon cuts a negative copy into
    // a curved sky gradient. Wide extraction therefore belongs ONLY to the real
    // solar/lunar quads. Their supplied bounds include every phase and size.
    // A zero Moon direction retains the original large-disc vanilla fallback.
    bool geographic=dot(WeatherTint.yzw,WeatherTint.yzw)>.5;
    float aa=angularPixel*1.5;
    float sunBound=2.0*sin((CloudDrift.z+aa)*.5);
    float moonBound=2.0*sin((CloudDrift.w+aa)*.5);
    bool disc=length(centreRay-Sun.xyz)<=sunBound
           || length(centreRay-WeatherTint.yzw)<=moonBound;
    if(geographic && !disc) {
        // Stars need only a compact neighbourhood, never the broad sky tint.
        // The maximum rejects smooth gradients; terrain is not a dark baseline
        // for sky holes between needles. Retain narrow-FOV star cores as well.
        float starRadius=max(2.0,.006/angularPixel);
        vec3 brightest=vec3(0.0); float neighbours=0.0;
        for(int i=0;i<8;i++) {
            vec2 q=uv+offsets[i]*px*starRadius;
            if(any(lessThan(q,vec2(0))) || any(greaterThan(q,vec2(1)))) continue;
            if(!nlSky(nlDepth(DepthSampler,q))) continue;
            brightest=max(brightest,texture(MainSampler,q).rgb); neighbours+=1.0;
        }
        if(neighbours<3.0) return vec3(0.0);
        vec3 star=max(center-brightest,vec3(0.0));
        return star*smoothstep(.008,.035,nlLuma(star));
    }
    // The smallest angular step includes diagonal shear near screen corners.
    // Using the largest step here moved vertical samples back into the Moon
    // near the screen edge at wide FOV. Keep that largest step only for AA/stars.
    float dx2=dot(rayDx,rayDx),dy2=dot(rayDy,rayDy),crossStep=dot(rayDx,rayDy);
    float discriminant=sqrt(max((dx2-dy2)*(dx2-dy2)+4.0*crossStep*crossStep,0.0));
    float minimumStep=sqrt(max((dx2+dy2-discriminant)*.5,1e-12));
    // A reflected footprint must not reach the real quad plus its AA margin,
    // even with 4x bodies. The 15% guard covers finite-step perspective curvature.
    float sampleAngle=geographic?max(.045,2.0*max(CloudDrift.z,CloudDrift.w)+2.0*aa):.045;
    float radius=max(8.0,sampleAngle*(geographic?1.15:1.0)/minimumStep);
    vec3 sum=vec3(0.0); float count=0.0;
    for(int i=0;i<8;i++) {
        vec2 q=uv+offsets[i]*px*radius;
        if(any(lessThan(q,vec2(0))) || any(greaterThan(q,vec2(1)))) continue;
        if(!nlSky(nlDepth(DepthSampler,q))) continue;
        sum+=texture(MainSampler,q).rgb; count+=1.0;
    }
    if(count<1.0) return vec3(0.0);
    vec3 detail=max(center-sum/count,vec3(0.0));
    return detail*smoothstep(.008,.035,nlLuma(detail));
}
vec3 stellarHalo(vec2 uv,vec3 center) {
    vec2 px=1.0/vec2(textureSize(MainSampler,0));
    float total=nlLuma(center),peak=total;
    vec2 offsets[8]=vec2[8](vec2(1,0),vec2(-1,0),vec2(0,1),vec2(0,-1),
        vec2(1,1),vec2(-1,1),vec2(1,-1),vec2(-1,-1));
    for(int i=0;i<8;i++) {
        vec2 q=clamp(uv+offsets[i]*px,px*.5,vec2(1)-px*.5);
        float l=nlSky(nlDepth(DepthSampler,q))?nlLuma(texture(MainSampler,q).rgb):nlLuma(center);
        total+=l; peak=max(peak,l);
    }
    float surround=total/9.0;
    float isolated=smoothstep(.16,.54,peak)*(1.0-smoothstep(.08,.24,surround));
    float soft=smoothstep(.10,.38,peak)*(1.0-smoothstep(.05,.16,surround));
    return vec3(.19,.27,.44)*soft*.016+vec3(.48,.62,.92)*isolated*.020;
}

void legacyBackground() {
    vec4 source=texture(MainSampler,texCoord);
    if(Flags.w>.5 || Flags.z>.5) { fragColor=source; return; }
    float depth=nlDepth(DepthSampler,texCoord);
    bool sky=nlSky(depth);
    vec3 ray=nlRay(texCoord);
    vec3 p=sky?vec3(0):nlReconstruct(texCoord,depth);
    float distanceBlocks=sky?dwBackgroundFogDistance(ray):length(p);
    float edgeFade=(!sky && FogRange.w>.5)
        ? smoothstep(FogRange.x,FogRange.y,length(p.xz))*dwTerrainEdgeOpacity(ray,distanceBlocks) : 0.0;
    // Do not run costly background/halo work for clear nearby ground below the clouds.
    float storm=nlStormStrength();
    float fogStart=mix(FogLayer.w,min(FogLayer.w,4.0),min(storm,1.0));
    fogStart=mix(fogStart,min(fogStart,2.5),max(storm-1.0,0.0));
    if(!sky && distanceBlocks<=fogStart && CameraInfo.y<DW_CLOUD_BOTTOM && edgeFade<=0.0) {
        fragColor=source; return;
    }
    vec2 fog=gatherHaze(texCoord,ray,distanceBlocks,sky);
    vec4 cloud=smoothCloud(texCoord);
    vec3 air=dwFogColour(ray);
    float backgroundCloud=dwVisibleCloudAlpha(ray,dwBackgroundSurfaceDistance(ray),clamp(cloud.a,0.0,1.0));
    vec3 background=mix(dwDistantBackground(ray),cloud.rgb,backgroundCloud);
    background=mix(background,air,fog.y);
    vec3 color;
    if(sky) {
        float visible=(1.0-backgroundCloud)*(1.0-fog.y);
        color=background+(celestialDetail(texCoord,source.rgb)+stellarHalo(texCoord,source.rgb))*visible;
    } else {
        float localCloud=dwVisibleCloudAlpha(ray,distanceBlocks,clamp(cloud.a,0.0,1.0));
        color=mix(source.rgb,cloud.rgb,localCloud);
        color=mix(color,air,fog.x);
        // World-height mist starts the fade; only the final horizontal strip guarantees full blending.
        color=mix(color,background,edgeFade);
    }
    fragColor=vec4(clamp(color,0.0,1.0),source.a);
}


// Sky and clouds have already passed through the native depth/OIT path.
// This stage owns only the independently enabled world-height atmosphere.
void main() {
    if(!nlModuleFlag(4)) { legacyBackground();return; }
    vec4 source=texture(MainSampler,texCoord);
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
