// Defines shared scene uniforms and depth, lighting, and shadow sampling helpers.
#ifndef DARKWINTER_VISUALS_SCENE
#define DARKWINTER_VISUALS_SCENE
// Exact byte layout is generated/checked against SceneFrame.java and pipeline JSON.
layout(std140) uniform DarkWinterScene {
    vec4 Inv0; vec4 Inv1; vec4 Inv2; vec4 Inv3;
    vec4 Light0; vec4 Light1; vec4 Light2; vec4 Light3;
    vec4 Sun;         // world direction TO sun; intensity
    vec4 Air;         // extinction/block; cap; shaft strength; ray length
    vec4 Shadow;      // resolution; world texel; min depth bias; shadow strength
    vec4 Climate;    // cloud cover; snowfall; wind speed; fog multiplier
    vec4 WindTime;   // wind X,Z; seconds; sample count
    vec4 Flags;      // shadow valid; mask: 1 volume,2 weather sky,4 captured background; debug; in fluid
    vec4 CameraInfo; // camera X,Y,Z (XZ modulo4096); terrain render distance
    vec4 Options;    // depth flip Y; shadow flip Y; clip span; normal offset/block
    vec4 Look;       // film strength; saturation; coldness; exposure
    vec4 Analog;     // grain; chroma bleed; vignette; scan structure
    vec4 FogLayer;   // base density; WORLD reference height; height falloff; clear near distance
    vec4 FogStyle;   // density variation; continuous world wind offset X,Z; broad air density
    vec4 FogRange;   // horizontal fade start,end; density samples (4 or 6); haze enabled
    vec4 WeatherShape; // roundness; cloud scale; stretch along wind; sky blueness
    vec4 WeatherTint;  // fog blueness; world direction TO geographic Moon (zero = vanilla fallback)
    vec4 CloudDrift;   // integrated cloud offset X,Z; sun/moon quad angular bounds (radians)
    vec4 Film;         // chromatic offset (1080p px); interlace; shutter; step printing
    vec4 FilmTiming;   // history valid; previous frame age; held exposure age; 50 Hz field parity
};
bool nlModuleFlag(int value) { return (int(Flags.y)&value)!=0; }
float nlLuma(vec3 c) { return dot(c,vec3(.2126,.7152,.0722)); }
// Snowfall above one is the severe-blizzard extension, not an extrapolated colour mix.
float nlStormStrength() { return clamp(Climate.y,0.0,2.0)*smoothstep(.40,.95,Climate.z); }
vec2 nlDepthUV(vec2 uv) { return vec2(uv.x,mix(uv.y,1.0-uv.y,Options.x)); }
bool nlSky(float d) { return d<=1e-7; }
float nlDepth(sampler2D map,vec2 uv) {
    ivec2 size=textureSize(map,0);
    ivec2 p=clamp(ivec2(nlDepthUV(uv)*vec2(size)),ivec2(0),size-1);
    return texelFetch(map,p,0).r;
}
vec3 nlReconstruct(vec2 uv,float depth) {
    vec4 p=mat4(Inv0,Inv1,Inv2,Inv3)*vec4(uv*2.0-1.0,max(depth,1e-7),1.0);
    float w=abs(p.w)<1e-7?(p.w<0.0?-1e-7:1e-7):p.w;
    return p.xyz/w;
}
vec3 nlRay(vec2 uv) { return normalize(nlReconstruct(uv,.0001)); }
vec3 nlLightCoord(vec3 p) {
    vec4 c=mat4(Light0,Light1,Light2,Light3)*vec4(p,1.0);
    vec3 q=c.xyz/c.w;
    q.xy=q.xy*.5+.5;
    q.y=mix(q.y,1.0-q.y,Options.y);
    return q;
}
float nlCoverage(vec3 q) {
    if(q.z<=0.0 || q.z>=1.0) return 0.0;
    float edge=min(min(q.x,q.y),min(1.0-q.x,1.0-q.y));
    return smoothstep(.025,.08,edge);
}
vec3 nlNeighbor(sampler2D depthMap,vec2 uv,vec3 fallback) {
    if(any(lessThan(uv,vec2(0))) || any(greaterThan(uv,vec2(1)))) return fallback;
    float d=nlDepth(depthMap,uv);
    return nlSky(d)?fallback:nlReconstruct(uv,d);
}
// Pick the short side of each depth discontinuity. No cross-triangle screen derivatives.
vec3 nlNormal(sampler2D depthMap,vec2 uv,vec3 p) {
    vec2 px=1.0/vec2(textureSize(depthMap,0));
    vec3 dx0=p-nlNeighbor(depthMap,uv-vec2(px.x,0),p);
    vec3 dx1=nlNeighbor(depthMap,uv+vec2(px.x,0),p)-p;
    vec3 dy0=p-nlNeighbor(depthMap,uv-vec2(0,px.y),p);
    vec3 dy1=nlNeighbor(depthMap,uv+vec2(0,px.y),p)-p;
    vec3 dx=dot(dx0,dx0)<dot(dx1,dx1)?dx0:dx1;
    vec3 dy=dot(dy0,dy0)<dot(dy1,dy1)?dy0:dy1;
    if(dot(dx,dx)<1e-12) dx=dot(dx0,dx0)>dot(dx1,dx1)?dx0:dx1;
    if(dot(dy,dy)<1e-12) dy=dot(dy0,dy0)>dot(dy1,dy1)?dy0:dy1;
    vec3 n=cross(dx,dy);
    float l=dot(n,n);
    if(l<1e-16) return vec3(0,1,0);
    n*=inversesqrt(l);
    return dot(n,-p)<0.0?-n:n;
}
// Transform the receiver plane normal to orthographic shadow UV-depth coordinates.
// Rows are orthogonal: inverse-transpose reduces to a dot product divided by row length^2.
vec2 nlReceiverGradient(vec3 n) {
    vec3 x=.5*vec3(Light0.x,Light1.x,Light2.x);
    vec3 y=.5*vec3(Light0.y,Light1.y,Light2.y)*(1.0-2.0*Options.y);
    vec3 z=vec3(Light0.z,Light1.z,Light2.z);
    vec3 plane=vec3(dot(n,x)/max(dot(x,x),1e-12),
                    dot(n,y)/max(dot(y,y),1e-12),dot(n,z)/max(dot(z,z),1e-12));
    if(abs(plane.z)<1e-5) return vec2(0);
    return clamp(-plane.xy/plane.z,vec2(-16),vec2(16));
}
float nlCompare(sampler2D map,ivec2 p,float referenceDepth) {
    ivec2 size=textureSize(map,0);
    if(any(lessThan(p,ivec2(0))) || any(greaterThanEqual(p,size))) return 1.0;
    float d=texelFetch(map,p,0).r;
    return step(d,referenceDepth); // reverse-Z; positive receiver bias moves toward light
}
vec3 nlWeights(float f) {
    return vec3(.5*(.5-f)*(.5-f),.75-f*f,.5*(.5+f)*(.5+f));
}
// Subtexel-continuous 3x3 quadratic filter, with receiver depth computed at EACH texel centre.
float nlSurfaceShadow(sampler2D map,vec3 q,vec2 plane,float bias) {
    vec2 size=vec2(textureSize(map,0));
    vec2 p=q.xy*size-.5;
    ivec2 centre=ivec2(floor(p+.5));
    vec2 f=p-vec2(centre);
    vec3 wx=nlWeights(f.x),wy=nlWeights(f.y);
    float result=0.0;
    for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
        ivec2 t=centre+ivec2(x,y);
        vec2 sampleUV=(vec2(t)+.5)/size;
        float receiver=q.z+dot(plane,sampleUV-q.xy)+bias;
        result+=nlCompare(map,t,receiver)*wx[x+1]*wy[y+1];
    }
    return clamp(result,0.0,1.0);
}
// Volume samples are points in air, not receiver planes. Do not apply surface slope correction.
float nlVolumeShadow(sampler2D map,vec3 q,float bias) {
    vec2 size=vec2(textureSize(map,0));
    vec2 p=q.xy*size-.5;
    ivec2 base=ivec2(floor(p));vec2 f=fract(p);
    float a=nlCompare(map,base,q.z+bias);
    float b=nlCompare(map,base+ivec2(1,0),q.z+bias);
    float c=nlCompare(map,base+ivec2(0,1),q.z+bias);
    float d=nlCompare(map,base+ivec2(1,1),q.z+bias);
    return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
}
float nlHash(vec2 p) {
    vec3 q=fract(vec3(p.xyx)*.1031);q+=dot(q,q.yzx+33.33);
    return fract((q.x+q.y)*q.z);
}
#endif
