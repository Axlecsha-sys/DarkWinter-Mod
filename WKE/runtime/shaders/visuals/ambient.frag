#version 450
// Native raster contact-AO adapter, deliberately separate from the mod surface pass.
// Replaces the native BVH AO rays with eight bounded screen-space depth samples.
// This is not a port of a mod effect and does not claim ray-traced AO equivalence.
// Off returns the exact source sample. Radius <=0.85 blocks, darkness <=0.25.
layout(set=0,binding=0) uniform sampler2D MainSampler;
layout(set=0,binding=1) uniform sampler2D DepthSampler;
layout(std140,set=0,binding=8) uniform DarkWinterScene {
    vec4 Inv0;
    vec4 Inv1;
    vec4 Inv2;
    vec4 Inv3;
    vec4 Light0;
    vec4 Light1;
    vec4 Light2;
    vec4 Light3;
    vec4 Sun;
    vec4 Air;
    vec4 Shadow;
    vec4 Climate;
    vec4 WindTime;
    vec4 Flags;
    vec4 CameraInfo;
    vec4 Options;
    vec4 Look;
    vec4 Analog;
    vec4 FogLayer;
    vec4 FogStyle;
    vec4 FogRange;
    vec4 WeatherShape;
    vec4 WeatherTint;
    vec4 CloudDrift;
    vec4 Film;
    vec4 FilmTiming;
};
layout(std140,set=0,binding=12) uniform NativePresentation {
    vec4 Celestial;
    vec4 SkyStyle;
    vec4 Presentation;
    vec4 Reserved;
};

layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

float nativeDepth(vec2 uv) {
    ivec2 size=textureSize(DepthSampler,0);
    if(Options.x>0.5) uv.y=1.0-uv.y;
    return texelFetch(DepthSampler,clamp(ivec2(uv*vec2(size)),ivec2(0),size-1),0).r;
}
vec3 nativePosition(vec2 uv,float depth) {
    vec4 p=mat4(Inv0,Inv1,Inv2,Inv3)*vec4(uv*2.0-1.0,max(depth,1e-7),1.0);
    return p.xyz/p.w;
}
void main() {
    vec4 source=texture(MainSampler,texCoord);
    float strength=clamp(Presentation.x,0.0,1.0);
    if(strength<=0.0) { fragColor=source;return; }

    const float radius=0.85;
    float depth=nativeDepth(texCoord);
    vec3 p=nativePosition(texCoord,depth);
    // Derivatives use the existing fragment quad, not extra texture taps.
    // Compute them before varying sky/edge branches; reject discontinuous quads.
    vec3 dx=dFdx(p),dy=dFdy(p);
    float pixelSpan=max(length(dx),length(dy));
    vec3 crossNormal=cross(dx,dy);
    float normalLength=length(crossNormal);
    vec2 size=vec2(textureSize(DepthSampler,0));
    vec2 edge=min(texCoord,1.0-texCoord)*size;
    if(depth<=1e-7 || pixelSpan<=1e-7 || pixelSpan>radius*0.35
            || normalLength<=1e-10 || min(edge.x,edge.y)<1.5) {
        fragColor=source;return;
    }
    vec3 normal=crossNormal/normalLength;
    if(dot(normal,p)>0.0) normal=-normal;
    float pixelRadius=clamp(radius/pixelSpan,1.0,32.0);
    const vec2 directions[8]=vec2[8](
        vec2(1,0),vec2(0.70710678,0.70710678),vec2(0,1),vec2(-0.70710678,0.70710678),
        vec2(-1,0),vec2(-0.70710678,-0.70710678),vec2(0,-1),vec2(0.70710678,-0.70710678));
    float occlusion=0.0;
    for(int i=0;i<8;i++) {
        float ring=(i&1)==0?1.0:0.65;
        vec2 uv=texCoord+directions[i]*(pixelRadius*ring)/size;
        if(any(lessThan(uv,vec2(0.0))) || any(greaterThan(uv,vec2(1.0)))) continue;
        float otherDepth=nativeDepth(uv);
        if(otherDepth<=1e-7) continue;
        vec3 delta=nativePosition(uv,otherDepth)-p;
        float distanceBlocks=length(delta);
        if(distanceBlocks<=0.025 || distanceBlocks>=radius) continue;
        float height=max(dot(normal,delta)-0.025,0.0);
        float horizon=smoothstep(0.02,0.5,height/distanceBlocks);
        float reach=1.0-distanceBlocks/radius;
        occlusion+=horizon*reach*reach;
    }
    // The fixed denominator attenuates sky/invalid taps instead of amplifying edges.
    float edgeWeight=smoothstep(1.5,4.0,min(edge.x,edge.y));
    float darkness=0.25*strength*edgeWeight*clamp(occlusion*(1.75/8.0),0.0,1.0);
    fragColor=vec4(source.rgb*(1.0-darkness),source.a);
}
