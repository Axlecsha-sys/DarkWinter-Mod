// Native-resolution temporal coverage resolve: camera reprojection, depth rejection and local clipping.
// No world-space upscaling, film feedback, hand/HUD history or invented object motion vectors.
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MainSampler;
uniform sampler2D DepthSampler;
uniform sampler2D HistorySampler;
uniform sampler2D HistoryDepthSampler;
uniform sampler2D ReactiveSampler;
uniform sampler2D HistoryReactiveSampler;
layout(std140) uniform WkeTemporalResolve {
    vec4 CurrentInv0;vec4 CurrentInv1;vec4 CurrentInv2;vec4 CurrentInv3;
    vec4 Previous0;vec4 Previous1;vec4 Previous2;vec4 Previous3;
    vec4 CameraDeltaHistory; // current camera - previous camera; history valid
    vec4 ResolveControl;     // retention; relative depth tolerance; depth flip Y; base mode 0/1/2(wind), debug bit16
    vec4 JitterControl;      // current.xy and previous.zw jitter, native pixels
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

vec3 toYCoCg(vec3 c) {
    return vec3((c.r+2.0*c.g+c.b)*.25,(c.r-c.b)*.5,(-c.r+2.0*c.g-c.b)*.25);
}
vec3 fromYCoCg(vec3 c) { return vec3(c.x+c.y-c.z,c.x+c.z,c.x-c.y-c.z); }
ivec2 bounded(ivec2 p,ivec2 size) { return clamp(p,ivec2(0),size-1); }
float depthAt(sampler2D image,ivec2 p,ivec2 size) {
    p=bounded(p,size);
    if(ResolveControl.z>.5) p.y=size.y-1-p.y;
    return texelFetch(image,p,0).r;
}
bool inside(vec2 uv,vec2 pixel) {
    return all(greaterThanEqual(uv,pixel*.5)) && all(lessThanEqual(uv,vec2(1)-pixel*.5));
}
bool debugWeights() { return (int(ResolveControl.w+.5)&16)!=0; }
vec4 debugResult(vec4 color,float alpha) {
    return debugWeights()?vec4(color.rgb,alpha):color;
}
void main() {
    ivec2 size=textureSize(MainSampler,0);
    vec2 pixel=1.0/vec2(size);
    ivec2 point=bounded(ivec2(texCoord*vec2(size)),size);
    vec2 uv=(vec2(point)+.5)*pixel;
    vec4 current=texelFetch(MainSampler,point,0);
    if(ResolveControl.w<.5) { fragColor=debugResult(current,253.0/255.0);return; }

    // Reconstruct the current native sample onto the stable output grid before
    // blending it with stable history. A rejected history must not return a
    // full-strength jittered sample F(p-j). The positive four-tap tent is entirely
    // contained in the existing 3x3 color neighborhood; no extra color fetches.
    // Real tiny particles keep their original raw contrast inside the actual
    // rasterized R8 mask; they already use the short .05 history exposure.
    float currentReactive=texture(ReactiveSampler,uv).r;
    vec2 reconstructionJitter=currentReactive>.5?vec2(0):JitterControl.xy;
    vec3 currentReconstructed=vec3(0);
    vec3 minimum=vec3(1e30),maximum=vec3(-1e30),mean=vec3(0),second=vec3(0);
    ivec2 samplePoint=point;
    float depth=depthAt(DepthSampler,point,size);
    for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
        ivec2 offset=ivec2(x,y);
        ivec2 tap=bounded(point+offset,size);
        vec3 color=(x==0 && y==0)?current.rgb:texelFetch(MainSampler,tap,0).rgb;
        vec3 value=toYCoCg(color);
        minimum=min(minimum,value);maximum=max(maximum,value);
        mean+=value;second+=value*value;
        vec2 tent=max(vec2(0),vec2(1)-abs(vec2(offset)-reconstructionJitter));
        float weight=tent.x*tent.y;
        currentReconstructed+=color*weight;
        if(weight>0.0 && (x!=0 || y!=0)) {
            // Reproject the nearest visible surface contributing to this exact
            // reconstruction footprint. The ray MUST use that depth texel's UV,
            // rather than inserting a neighbor depth into the center ray.
            float tapDepth=depthAt(DepthSampler,tap,size);
            if(tapDepth>depth) { depth=tapDepth;samplePoint=tap; }
        }
    }
    current.rgb=currentReconstructed;
    // History can be invalidated after this frame's jittered projection was uploaded.
    // Its current sample still belongs on the stable grid, including the reset frame.
    if(CameraDeltaHistory.w<.5) { fragColor=debugResult(current,253.0/255.0);return; }
    vec2 sampleUv=(vec2(samplePoint)+.5)*pixel;
    bool sky=depth<=1e-7;
    vec4 reconstructed=mat4(CurrentInv0,CurrentInv1,CurrentInv2,CurrentInv3)
            *vec4(sampleUv*2.0-1.0,max(depth,1e-7),1.0);
    if(abs(reconstructed.w)<1e-8) { fragColor=debugResult(current,254.0/255.0);return; }
    vec3 relative=reconstructed.xyz/reconstructed.w;
    // A sky ray is effectively at infinity: camera translation must not move it.
    vec3 previousRelative=relative+(sky?vec3(0):CameraDeltaHistory.xyz);
    vec4 projected=mat4(Previous0,Previous1,Previous2,Previous3)*vec4(previousRelative,1);
    if(projected.w<=1e-6) { fragColor=debugResult(current,254.0/255.0);return; }
    vec3 previousNdc=projected.xyz/projected.w;
    vec2 previousDepthUv=previousNdc.xy*.5+.5;
    // Raw depth follows the actual selected ray s. Stable history color belongs
    // to output pixel p: add (p-s), then compensate the two raster jitters.
    // For a static camera/surface this maps back to exactly p even when s!=p.
    vec2 previousColorUv=previousDepthUv
            +(vec2(point-samplePoint)+JitterControl.xy-JitterControl.zw)*pixel;
    if(!inside(previousDepthUv,pixel) || !inside(previousColorUv,pixel)) {
        fragColor=debugResult(current,254.0/255.0);return;
    }

    ivec2 oldPoint=ivec2(floor(previousDepthUv*vec2(size)-.5));
    float expectedDepth=previousNdc.z;
    if(!sky && (expectedDepth<=0.0 || expectedDepth>=1.0)) { fragColor=debugResult(current,254.0/255.0);return; }
    float tolerance=max(1e-7,abs(expectedDepth)*clamp(ResolveControl.y,.001,.03));
    float nearestError=1e30,centerError=1e30;
    // Matching any one of the four raw raster samples prevents an artificial
    // reject when subpixel jitter moves a static needle across a texel boundary.
    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
        float oldDepth=depthAt(HistoryDepthSampler,oldPoint+ivec2(x,y),size);
        float error=sky?(oldDepth<=1e-7?0.0:1e30):abs(oldDepth-expectedDepth);
        nearestError=min(nearestError,error);
    }
    float nearestDepth=depthAt(HistoryDepthSampler,ivec2(previousDepthUv*vec2(size)),size);
    centerError=sky?(nearestDepth<=1e-7?0.0:1e30):abs(nearestDepth-expectedDepth);
    if(nearestError>tolerance) { fragColor=debugResult(current,1.0);return; }

    float minDepth=1e30,maxDepth=0;
    bool windGuard=(int(ResolveControl.w+.5)&3)==2 && !sky && length(relative)<26.0;
    if(windGuard) for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
        float tapDepth=depthAt(DepthSampler,point+ivec2(x,y),size);
        minDepth=min(minDepth,tapDepth);maxDepth=max(maxDepth,tapDepth);
    }
    mean/=9.0;second/=9.0;
    vec3 sigma=sqrt(max(second-mean*mean,vec3(0)));
    // Intersection of local min/max and a variance box removes old foreground
    // from newly exposed background, while retaining the two colors of an edge.
    vec3 lower=max(minimum,mean-1.5*sigma);
    vec3 upper=min(maximum,mean+1.5*sigma);
    vec3 history=toYCoCg(texture(HistorySampler,previousColorUv).rgb);
    vec3 clipped=clamp(history,lower,upper);
    vec3 correction=abs(history-clipped);
    float clipping=max(correction.x,max(correction.y,correction.z));
    float colorRange=max(maximum.x-minimum.x,.04);
    float baseWeight=clamp(ResolveControl.x,0.0,.95);
    float historyWeight=baseWeight;
    int reason=0; // debug: 0 retained,1 local clipping,2 real particles,3 motion/depth/wind
    // Local clipping rejects exposed old foreground without claiming color change
    // on a static coplanar texture is object motion.
    historyWeight*=1.0-smoothstep(.04,.30,clipping/colorRange);
    if(historyWeight<baseWeight-.001) reason=1;
    // Actual current particle bounds are on the current raster grid. Their saved
    // prior mask follows the same raw reprojection as HistoryDepth; camera motion
    // cannot leave the old particle exposure attached to the background.
    // Flat static material detail is not reactive. Bounds expand by one native
    // pixel to include sample filtering and the subpixel raw/stable-grid offset.
    float reactive=max(currentReactive,texture(HistoryReactiveSampler,previousDepthUv).r);
    if(reactive>.5 && historyWeight>.05) { historyWeight=.05;reason=2; }
    if(centerError>tolerance && historyWeight>.75) { historyWeight=.75;reason=3; }
    float motionPixels=length((previousColorUv-uv)/pixel);
    // The slow/static limit follows the actual integration weight; a second
    // hard .9 ceiling would silently defeat the eight-phase accumulation window.
    float motionCap=mix(baseWeight,.55,smoothstep(1.0,16.0,motionPixels));
    if(historyWeight>motionCap) { historyWeight=motionCap;reason=3; }
    if(sky) {
        // Clouds/weather have no object velocities. Retain only a short exposure.
        if(historyWeight>.35) { historyWeight=.35;reason=3; }
    } else if(windGuard) {
        // The actual spruce wind pass is restricted to radius24; this two-block
        // guard conservatively limits its high-frequency silhouette history.
        float depthEdge=(maxDepth-minDepth)/max(maxDepth,1e-6);
        if(depthEdge>.03 && historyWeight>.55) { historyWeight=.55;reason=3; }
    }
    vec3 resolved=mix(current.rgb,fromYCoCg(clipped),historyWeight);
    fragColor=vec4(clamp(resolved,vec3(0),vec3(1)),current.a);
    // Opt-in diagnostic only: alpha packs cause*64 + round(weight*63).
    // Codes253/254/255 mean no history / invalid reprojection / raw-depth reject.
    // RGB and the history RGB arithmetic are identical to the normal path.
    float encoded=(float(reason*64)+floor(clamp(historyWeight,0.0,1.0)*63.0+.5))/255.0;
    fragColor=debugResult(fragColor,encoded);
}
