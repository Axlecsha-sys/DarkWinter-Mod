// Applies bounded temporal exposure, analog lens effects, and the final cinematic color treatment.
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MainSampler;
uniform sampler2D DepthSampler;
uniform sampler2D PreviousSampler;
uniform sampler2D HeldSampler;
#include <darkwinter_visuals:scene.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
// Cold analog thriller. This pass never computes sunlight, shadows, rays or world-space fog.
vec2 safeUV(vec2 uv) {
    vec2 p=.5/vec2(textureSize(MainSampler,0));return clamp(uv,p,1.0-p);
}
vec3 sampleImage(vec2 uv) { return texture(MainSampler,safeUV(uv)).rgb; }
// Two finite RAW exposures. No graded-frame feedback, camera reprojection or game-time stepping.
vec3 filmExposure(vec2 uv) {
    uv=safeUV(uv);
    vec3 current=texture(MainSampler,uv).rgb;
    if(FilmTiming.x<.5) return current;
    // Unreprojected history places Sun/Moon discs at old camera positions.
    // Keep temporal film exposure on geometry, while the sky uses this frame.
    // The lens, colour grade, scan structure and grain still apply to both.
    if(nlSky(nlDepth(DepthSampler,uv))) return current;
    float freshness=1.0-smoothstep(.075,.18,FilmTiming.y);
    // Keep the trace short at low frame rates; bound its weight at very high frame rates.
    float shutter=min(.28,Film.z*(1.0/60.0)/max(FilmTiming.y,1.0/240.0));
    float field=mod(floor(gl_FragCoord.y)+FilmTiming.w,2.0);
    float previousWeight=min(.34,shutter+Film.y*.35*field)*freshness;
    vec3 previous=texture(PreviousSampler,uv).rgb;
    vec3 exposure=mix(current,previous,previousWeight);
    float heldFreshness=1.0-smoothstep(.10,.20,FilmTiming.z);
    return mix(exposure,texture(HeldSampler,uv).rgb,Film.w*heldFreshness);
}
vec3 lensImage(vec2 uv,vec2 offset) {
    vec3 center=filmExposure(uv);
    if(Film.x<=0.0) return center;
    // Blue/cyan separation dominates; the central sight area remains sharp.
    return vec3(filmExposure(uv-offset*.30).r,center.g,filmExposure(uv+offset).b);
}
vec3 toYCoCg(vec3 c) { return vec3((c.r+2.0*c.g+c.b)*.25,(c.r-c.b)*.5,(-c.r+2.0*c.g-c.b)*.25); }
vec3 fromYCoCg(vec3 c) { return vec3(c.x+c.y-c.z,c.x+c.z,c.x-c.y-c.z); }
vec3 grade(vec3 c) {
    c=max(c,vec3(0));
    float y=nlLuma(c);
    // Multiplicative toe/mid curve: black stays black, without subtract-and-clamp shadow destruction.
    float mid=smoothstep(.06,.32,y)*(1.0-smoothstep(.66,.96,y));
    float target=y*Look.w*(1.0-.12*mid);
    float hi=max(target-.65,0.0);
    target-=.26*hi*hi;
    c*=max(target,0.0)/max(y,1e-7);
    y=nlLuma(c);
    float shade=1.0-smoothstep(.10,.72,y);
    float sat=clamp(Look.y-.04*shade,0.0,1.2);
    c=mix(vec3(y),c,sat);
    // Noticeable blue-steel shadows and cooler midtones. Preserve neutral luminous highlights.
    float strength=Look.z*(.40+.60*shade);
    vec3 cool=c*vec3(1.0-strength*.60,1.0-strength*.08,1.0+strength*.50);
    cool*=y/max(nlLuma(cool),1e-7);
    c=mix(c,cool,1.0-.70*smoothstep(.72,.98,y));
    // Preserve the distinction between fire/warm objects and cold air; no additive blue veil.
    return clamp(c,0.0,1.0);
}
void main() {
    vec4 source=texture(MainSampler,texCoord);
    if(Flags.z>5.5 && Flags.z<6.5) {
        // Explicit proof that THIS final pass is connected. Never enabled by default.
        fragColor=texCoord.x<.035?vec4(1,0,.65,source.a):source;return;
    }
    if(Flags.z>.5 || Look.x<=0.0) { fragColor=source;return; }
    vec2 size=vec2(textureSize(MainSampler,0));
    // Reference pixel spacing scales with output height; no geometry displacement.
    vec2 px=vec2(1.0)/size;
    float scale=max(size.y/1080.0,1.0);
    vec2 horizontal=vec2(px.x*scale,0);
    vec2 v=(texCoord-.5)*2.0;
    vec2 radial=v*vec2(size.x/size.y,1.0);
    float lensMask=smoothstep(.25,1.30,length(v));
    vec2 offset=radial/max(length(radial),1e-5)*px*(size.y/1080.0)*Film.x*lensMask;
    vec3 exposed=lensImage(texCoord,offset);
    // A small spatial diffusion keeps bright snow edges from becoming harsh coloured outlines.
    vec3 left=sampleImage(texCoord-horizontal),right=sampleImage(texCoord+horizontal);
    vec3 up=sampleImage(texCoord+vec2(0,px.y)),down=sampleImage(texCoord-vec2(0,px.y));
    vec3 soft=exposed+((left+right+up+down)*.25-source.rgb)*.09;
    vec3 chroma=toYCoCg(soft);
    vec3 side=(toYCoCg(sampleImage(texCoord-horizontal*2.0))
              +toYCoCg(sampleImage(texCoord+horizontal*2.0)))*.5;
    chroma.yz=mix(chroma.yz,side.yz,Analog.y);
    vec3 color=grade(fromYCoCg(chroma));
    float edge=smoothstep(.60,1.35,length(v*vec2(.84,1.0)));
    color*=1.0-Analog.z*edge;
    // Faint stationary scan structure; the alternating temporal fields above carry motion.
    float row=.5+.5*cos(3.14159265359*floor(gl_FragCoord.y));
    color*=1.0-Analog.w*row;
    float timeFrame=floor(WindTime.z*24.0);
    vec2 pixel=floor(gl_FragCoord.xy/scale);
    float fine=(nlHash(pixel+vec2(timeFrame*13.0,timeFrame*7.0))
               +nlHash(pixel+vec2(27.0-timeFrame*5.0,timeFrame*11.0)))*.5-.5;
    float coarse=nlHash(floor(pixel*.5)+vec2(timeFrame*3.0,timeFrame*17.0))-.5;
    float grain=fine*.78+coarse*.22;
    float y=nlLuma(color);
    float grainMask=smoothstep(.008,.08,y)*(1.0-.55*smoothstep(.65,.98,y));
    color+=vec3(grain*Analog.x*grainMask);
    fragColor=vec4(clamp(mix(source.rgb,color,Look.x),0.0,1.0),source.a);
}
