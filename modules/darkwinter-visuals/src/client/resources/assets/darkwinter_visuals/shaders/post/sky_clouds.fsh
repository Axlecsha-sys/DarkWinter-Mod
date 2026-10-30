// Renders continuous weather-driven cloud layers with integrated wind drift.
#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D DepthSampler;
#include <darkwinter_visuals:scene.glsl>
#include <darkwinter_visuals:cloud_shell.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// DARKWINTER SKY / CINEMATIC COLD CLOUDS
// Continuous mixtures of the original overcast sheet, ragged cumulus and wind-stretched layers.
// Every weather value is interpolated on the client; no preset branches or texture crossfades.

float skyHash(vec2 p) {
    vec3 q=fract(vec3(p.xyx)*0.1031);
    q+=dot(q,q.yzx+33.33);
    return fract((q.x+q.y)*q.z);
}

float skyNoise(vec2 p,vec2 repeatCells) {
    vec2 id=floor(p);
    vec2 f=fract(p);f=f*f*(3.0-2.0*f);
    vec2 a=mod(id,repeatCells);
    float v00=skyHash(a);
    float v10=skyHash(mod(a+vec2(1,0),repeatCells));
    float v01=skyHash(mod(a+vec2(0,1),repeatCells));
    float v11=skyHash(mod(a+vec2(1,1),repeatCells));
    return mix(mix(v00,v10,f.x),mix(v01,v11,f.x),f.y);
}

float skyFbm(vec2 p,vec2 repeatCells) {
    float value=0.0;
    float amp=0.5;
    vec2 pos=p;
    vec2 cells=repeatCells;
    for(int i=0;i<5;i++) {
        value+=amp*skyNoise(pos,cells);
        pos=mat2(1.7,-1.2,1.2,1.7)*pos*1.63;
        cells=max(cells*1.63,vec2(1.0));
        amp*=0.53;
    }
    return value;
}

float skyRidged(vec2 p,vec2 repeatCells) {
    float v=0.0;
    float amp=0.55;
    vec2 pos=p;
    vec2 cells=repeatCells;
    for(int i=0;i<3;i++) {
        float n=skyNoise(pos,cells);
        v+=amp*(1.0-abs(n*2.0-1.0));
        pos=mat2(1.4,-1.1,1.1,1.4)*pos*1.92;
        cells=max(cells*1.92,vec2(1.0));
        amp*=0.5;
    }
    return v;
}

float roundClouds(vec2 position,float h,float detail) {
    vec2 p=position/660.0;
    // Broad turbulent deformation breaks the circular footprint before lobes are assembled.
    vec2 warp=vec2(skyNoise(position/235.0+vec2(7.1,h*.7),vec2(96.0)),
                   skyNoise(position/193.0+vec2(h*.6,31.4),vec2(96.0)))-.5;
    p+=warp*.22;
    vec2 cell=floor(p),f=fract(p);
    float density=0.0;
    float population=min(1.0,Climate.x*1.8);
    // Unequal, tilted overlapping puffs share a flatter base. Their size, orientation,
    // shoulders and crown vary per island; no repeated round disks or identical pairs.
    for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
        vec2 id=cell+vec2(x,y);
        float seed=skyHash(id+vec2(41.3,8.7));
        float present=smoothstep(1.0-population-.08,1.0-population+.08,seed);
        if(present<=.0001) continue;
        vec2 centre=vec2(.3)+.4*vec2(skyHash(id+13.0),skyHash(id+29.0));
        vec2 d=f-vec2(x,y)-centre;
        float angle=skyHash(id+47.0)*6.2831853;
        d=mat2(cos(angle),-sin(angle),sin(angle),cos(angle))*d;
        float radius=mix(.23,.40,skyHash(id+71.0));
        float aspect=mix(.53,.82,skyHash(id+91.0));
        float crown=mix(.43,.63,skyHash(id+112.0));
        vec3 a=vec3(d/vec2(radius,radius*aspect),(h-.40)/.29);
        vec3 b=vec3((d-vec2(radius*.53,radius*.16))/vec2(radius*.66,radius*.61),(h-crown)/.32);
        vec3 c=vec3((d+vec2(radius*.54,radius*.22))/vec2(radius*.73,radius*.49),(h-.43)/.26);
        float shape=min(dot(a,a),min(dot(b,b),dot(c,c)));
        float erosion=(detail-.5)*.42+warp.x*.28;
        float puff=1.0-smoothstep(.43,1.03,shape+erosion);
        float base=smoothstep(.07,.22,h);
        density=max(density,puff*base*present);
    }
    return density;
}

float cloudField(vec3 world) {
    vec2 position=world.xz-CloudDrift.xy;
    vec2 wind=normalize(WindTime.xy+vec2(1e-4,0.0));
    float stretch=max(WeatherShape.z,1.0);
    // At stretch=scale=1 this is exactly the old sheet's world coordinate.
    position-=wind*dot(position,wind)*(1.0-1.0/stretch);
    position/=max(WeatherShape.y,.25);
    float cover=clamp(Climate.x,0.0,1.0);
    float h=clamp((world.y-DW_CLOUD_BOTTOM)/(DW_CLOUD_TOP-DW_CLOUD_BOTTOM),0.0,1.0);
    float rounded=0.0;
    if(WeatherShape.x>.001) {
        float edgeDetail=skyNoise(position/170.0+vec2(world.y*.006,world.y*.0025),vec2(96.0));
        rounded=roundClouds(position,h,edgeDetail);
    }
    if(WeatherShape.x>=1.0) return rounded*sqrt(cover);

    vec2 broadPos=position/860.0 + vec2(world.y*0.0014,world.y*0.0006);
    float broadWarp=skyFbm(broadPos*1.25+vec2(9.1,4.7),vec2(24.0))-.5;

    vec2 massPos=position/540.0 + vec2(broadWarp*140.0, broadWarp*54.0)/540.0;
    float masses=skyFbm(massPos+vec2(world.y*0.0018,world.y*0.0009),vec2(40.0));

    vec2 detailPos=position/170.0 + vec2(broadWarp*78.0,-broadWarp*36.0)/170.0;
    float detail=skyFbm(detailPos+vec2(world.y*0.006,world.y*0.0025),vec2(96.0));
    float ridges=skyRidged(detailPos*1.35+vec2(12.5,3.8),vec2(96.0));

    float baseProfile=smoothstep(0.02,0.18,h)*(1.0-smoothstep(0.72,0.98,h));
    float capProfile=1.0-smoothstep(0.55,0.95,h);
    float structure=masses*0.72 + detail*0.20 + ridges*0.18 + capProfile*0.08;
    structure += broadWarp*0.08;
    float sheet=smoothstep(.50+(1.0-cover)*.14,.82,structure)*baseProfile;
    float density=mix(sheet,rounded,WeatherShape.x);
    float storm=nlStormStrength();
    // Lower, ragged storm underside; the shell/depth geometry itself remains unchanged.
    density+=storm*baseProfile*smoothstep(.36,.67,structure)*.34;
    return density*sqrt(cover);
}

void main() {
    fragColor=vec4(0.0);
    if(Flags.w>.5) return;
    if(Flags.z>.5) { fragColor=vec4(vec3(nlDepth(DepthSampler,texCoord)),0.0); return; }
    if(Climate.x<=.00001) return;
    vec3 ray=nlRay(texCoord);
    vec2 span;
    if(!dwCloudInterval(ray,span)) return;
    // Whole background field, including behind silhouettes. Depth gating is done at full resolution.
    // Shallow rays cross small cumulus islands between fixed coarse steps, making
    // horizontal comb-like bands. Refine those rays only; the old overcast keeps 14.
    float refinedLimit=WindTime.w<10.0?18.0:(WindTime.w<14.0?22.0:26.0);
    float refinement=smoothstep(450.0,1700.0,(span.y-span.x)/max(WeatherShape.y,.25));
    int count=int(ceil(mix(14.0,refinedLimit,refinement*WeatherShape.x)));
    float dt=(span.y-span.x)/float(count);
    float optical=0.0,detailAcc=0.0,rimAcc=0.0,heightAcc=0.0;
    float daylight=clamp(Sun.w,0.0,1.0);
    float storm=nlStormStrength();
    float stormMix=min(storm,1.0),severe=max(storm-1.0,0.0);
    vec3 dayLightDir=normalize(Sun.xyz+vec3(0.0,0.20,0.0));
    vec3 nightLightDir=normalize(vec3(-0.22,0.92,0.31));
    vec3 lightDir=normalize(mix(nightLightDir,dayLightDir,daylight));
    float viewLight=pow(max(dot(ray,lightDir),0.0),7.5);
    for(int i=0;i<26;i++) {
        if(i>=count) break;
        float dist=span.x+(float(i)+.5)*dt;
        float height=dwCloudHeight(ray,dist);
        vec3 world=vec3(CameraInfo.x+ray.x*dist,height,CameraInfo.z+ray.z*dist);
        float density=cloudField(world);
        if(density<=0.0001) continue;
        float h=clamp((height-DW_CLOUD_BOTTOM)/(DW_CLOUD_TOP-DW_CLOUD_BOTTOM),0.0,1.0);
        optical+=density*dt*.028*(.65+.35*(1.0-h));
        detailAcc+=density*(1.0-h);
        heightAcc+=density*h;
        rimAcc+=density*(.18+.82*viewLight)*(1.0-abs(h-.58)*1.2);
    }
    float coverage=clamp(1.0-exp(-optical*(mix(.82,1.25,stormMix)+severe*.65)),0.0,
            mix(mix(.64,.84,WeatherShape.x),.94,stormMix)+severe*.04);
    // Perspective to a WORLD-fixed curved layer. There is no camera-height offset to cloud altitude.
    float observer=max(CameraInfo.y,0.0);
    float radius=DW_CLOUD_RADIUS+observer;
    float horizon=-sqrt(observer*(2.0*DW_CLOUD_RADIUS+observer)/(radius*radius));
    coverage*=smoothstep(horizon,horizon+.07,ray.y);
    float thinness=clamp(detailAcc/float(count)*2.1,0.0,1.0);
    float loft=clamp(heightAcc/float(count)*1.9,0.0,1.0);
    float silver=clamp(rimAcc/float(count)*1.85,0.0,1.0);
    vec3 nightBase=mix(vec3(.07,.10,.15),vec3(.15,.21,.30),thinness*.65+loft*.35);
    vec3 dayBase=mix(vec3(.39,.44,.50),vec3(.68,.74,.80),thinness*.60+loft*.40);
    vec3 baseColor=mix(nightBase,dayBase,daylight);
    vec3 rimColor=mix(vec3(.56,.69,.90),vec3(.86,.89,.93),daylight);
    vec3 cloudColor=mix(baseColor,rimColor,clamp(silver*1.1+thinness*.18,0.0,1.0));
    // Keep the old overcast palette; isolated cumulus get brighter silver tops.
    cloudColor=mix(cloudColor,cloudColor*vec3(1.06,1.065,1.07),WeatherShape.x*daylight);
    cloudColor*=mix(1.0,.64,stormMix)*(1.0-severe*.12);
    fragColor=vec4(clamp(cloudColor,0.0,1.0),coverage);
}
