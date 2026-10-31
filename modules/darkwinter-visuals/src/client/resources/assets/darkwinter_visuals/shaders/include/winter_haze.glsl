// Computes world-height atmospheric density and fog opacity along view rays.
#ifndef DARKWINTER_WINTER_HAZE
#define DARKWINTER_WINTER_HAZE
// Independent world-height atmosphere. No cloud texture, screen-height bands or camera-relative layer.
// FogLayer / FogStyle / FogRange are appended to the shared std140 block.

float dwDaylight() { return smoothstep(-0.14,0.16,Sun.y); }

float dwPeriodicNoise(vec2 p, float period) {
    vec2 cell=mod(floor(p),period);
    vec2 f=fract(p); f=f*f*(3.0-2.0*f);
    float a=nlHash(cell);
    float b=nlHash(mod(cell+vec2(1,0),period));
    float c=nlHash(mod(cell+vec2(0,1),period));
    float d=nlHash(mod(cell+vec2(1,1),period));
    return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
}

// A finite WORLD-height layer. Subtracting the tail makes density reach zero
// continuously, instead of leaving an opaque 12-km horizon above the ground mist.
const float DW_FOG_TAIL=0.018315639; // exp(-4)
float dwLayerTop(float base,float falloff) { return base+4.0*max(falloff,1.0); }

// Integral of the unbounded exponential, used inside the finite layer below.
float dwExponentialIntegral(float y0,float y1,float distanceBlocks,float base,float falloff) {
    float lo=min(y0,y1), hi=max(y0,y1);
    float k=max(falloff,1.0);
    if(hi<=base) return distanceBlocks;
    float span=hi-lo;
    if(span<0.01) return distanceBlocks*exp(-max(0.5*(lo+hi)-base,0.0)/k);
    if(lo>=base) {
        float u=span/k;
        float average=u<0.001?1.0-u*0.5+u*u/6.0:(1.0-exp(-u))/u;
        return distanceBlocks*exp(-(lo-base)/k)*average;
    }
    float below=(base-lo)/span;
    float u=(hi-base)/k;
    float average=u<0.001?1.0-u*0.5+u*u/6.0:(1.0-exp(-u))/u;
    return distanceBlocks*(below+(1.0-below)*average);
}

float dwHeightIntegral(float y0,float y1,float distanceBlocks,float base,float falloff) {
    float lo=min(y0,y1),hi=max(y0,y1),top=dwLayerTop(base,falloff);
    if(distanceBlocks<=0.0 || lo>=top) return 0.0;
    if(hi>top) {
        distanceBlocks*=(top-lo)/max(hi-lo,0.00001);
        hi=top;
    }
    float exponential=dwExponentialIntegral(lo,hi,distanceBlocks,base,falloff);
    return max((exponential-DW_FOG_TAIL*distanceBlocks)/(1.0-DW_FOG_TAIL),0.0);
}

// Clip sampling to the same absolute layer top as the analytic integral.
vec2 dwLayerSegment(vec3 ray,float start,float finish,float top) {
    float y0=CameraInfo.y+ray.y*start,y1=CameraInfo.y+ray.y*finish;
    if(min(y0,y1)>=top) return vec2(start);
    if(y0>top) start=mix(start,finish,(top-y0)/(y1-y0));
    else if(y1>top) finish=mix(start,finish,(top-y0)/(y1-y0));
    return vec2(start,finish);
}

// Inverse cumulative density, used to distribute detail samples through the actual fog.
// Equal-distance sky steps used to span kilometres, then snap their sample point toward
// the layer boundary as camera pitch changed. Equal optical-mass strata have no such
// midpoint/boundary switch and cannot move a thick sampled patch above the observer.
float dwHeightSampleDistance(float y0,float y1,float start,float finish,
                            float base,float falloff,float fraction) {
    if(max(y0,y1)<=base || abs(y1-y0)<.01) return mix(start,finish,fraction);
    float k=max(falloff,1.0),sampleHeight;
    if(min(y0,y1)>=base) {
        // Shift the exponential to the lower endpoint to avoid underflow at high altitude.
        float low=min(y0,y1);
        float weight=mix(exp(-(y0-low)/k),exp(-(y1-low)/k),fraction);
        sampleHeight=low-k*log(max(weight,1e-7));
    } else {
        float h0=y0-base,h1=y1-base;
        float mass0=h0<=0.0?h0:k*(1.0-exp(-h0/k));
        float mass1=h1<=0.0?h1:k*(1.0-exp(-h1/k));
        float mass=mix(mass0,mass1,fraction);
        sampleHeight=base+(mass<=0.0?mass:-k*log(max(1.0-mass/k,1e-7)));
    }
    return mix(start,finish,clamp((sampleHeight-y0)/(y1-y0),0.0,1.0));
}

float dwFogPattern(vec3 p) {
    // Both scales repeat exactly at CameraInfo.xz's 4096-block seam.
    vec2 xz=mod(p.xz-FogStyle.yz,4096.0);
    float broad=dwPeriodicNoise(xz/256.0+vec2(p.y*0.009,p.y*0.006),16.0);
    float fine=dwPeriodicNoise(xz/64.0+vec2(p.y*0.021,-p.y*0.007),64.0);
    float ribbon=0.5+0.5*sin(p.y*0.10+broad*6.2831853);
    float field=0.65*broad+0.25*fine+0.10*ribbon;
    return clamp(1.0+FogStyle.x*(field*2.0-1.0)*1.8,0.2,1.8);
}

float dwFogOpacity(vec3 ray,float distanceBlocks,bool detailed) {
    float storm=nlStormStrength();
    float start=mix(FogLayer.w,min(FogLayer.w,4.0),min(storm,1.0));
    start=mix(start,min(start,2.5),max(storm-1.0,0.0));
    if(FogRange.w<0.5 || distanceBlocks<=start) return 0.0;
    float finish=min(distanceBlocks,16000.0);
    if(finish<=start) return 0.0;
    float y0=CameraInfo.y+ray.y*start;
    float y1=CameraInfo.y+ray.y*finish;
    // Broad air is a weak extension of the same ground layer. An unbounded 256-block
    // falloff used to make the 12-km sky ray opaque even when terrain fog was gone.
    float airFalloff=FogLayer.z*1.5;
    float optical=min(FogStyle.w*dwHeightIntegral(y0,y1,finish-start,FogLayer.y,airFalloff),.16);
    if(!detailed || FogStyle.x<=0.001) {
        optical+=FogLayer.x*dwHeightIntegral(y0,y1,finish-start,FogLayer.y,FogLayer.z);
    } else {
        int count=int(clamp(FogRange.z,4.0,6.0));
        float integral=dwHeightIntegral(y0,y1,finish-start,FogLayer.y,FogLayer.z);
        vec2 segment=dwLayerSegment(ray,start,finish,dwLayerTop(FogLayer.y,FogLayer.z));
        float sampleY0=CameraInfo.y+ray.y*segment.x;
        float sampleY1=CameraInfo.y+ray.y*segment.y;
        float detail=0.0,weightSum=0.0;
        for(int i=0;i<6;i++) {
            if(i>=count || integral<=0.00001) break;
            float sampleT=dwHeightSampleDistance(sampleY0,sampleY1,segment.x,segment.y,FogLayer.y,FogLayer.z,
                    (float(i)+.5)/float(count));
            vec3 p=vec3(CameraInfo.x,CameraInfo.y,CameraInfo.z)+ray*sampleT;
            // Samples follow exponential optical mass; reweight the removed tail.
            // This retains stable detail without iterative inverse-CDF solves.
            float rawDensity=exp(-max(p.y-FogLayer.y,0.0)/max(FogLayer.z,1.0));
            float weight=max(1.0-DW_FOG_TAIL/max(rawDensity,DW_FOG_TAIL),0.0);
            detail+=dwFogPattern(p)*weight;
            weightSum+=weight;
        }
        optical+=FogLayer.x*integral*(weightSum>.00001?detail/weightSum:1.0);
    }
    optical*=clamp(Climate.w,0.0,8.0);
    // Wind-driven snow remains airborne above the shallow ground fog layer.
    // It follows path length, so a blizzard still limits visibility on mountain ridges.
    optical+=(storm*.025+max(storm-1.0,0.0)*.020)*(finish-start);
    return 1.0-exp(-min(max(optical,0.0),20.0));
}

float dwTerrainEdgeOpacity(vec3 ray,float distanceBlocks) {
    float meanDensity=dwHeightIntegral(CameraInfo.y,CameraInfo.y+ray.y*distanceBlocks,
            distanceBlocks,FogLayer.y,FogLayer.z)/max(distanceBlocks,0.001);
    float atmospheric=clamp(meanDensity*max(Climate.w,0.0)+nlStormStrength(),0.0,1.0);
    // Physical mist can be absent in clear weather or above its world-height layer.
    // The last strip still must meet the background before chunks stop rendering:
    // multiplying that endpoint by mist density left bright, abrupt terrain edges.
    // Only horizontal range controls this terminal fade, so climbing never erases
    // nearby ground below the camera or moves the actual fog layer upward.
    float horizontalDistance=distanceBlocks*length(ray.xz);
    float streamingEnd=max(FogRange.y+1.0,CameraInfo.w-16.0);
    float terminal=smoothstep(FogRange.y,streamingEnd,horizontalDistance);
    return mix(atmospheric,1.0,terminal);
}

// A low-detail distant surface is only a background for unavailable terrain, never actual mesh/LOD.
// Keeping it separate from clouds avoids painting cloud swirls on trees or snow.
const float DW_DISTANT_SURFACE_Y=63.0;
float dwBackgroundSurfaceDistance(vec3 ray) {
    if(ray.y>=-0.00001 || CameraInfo.y<=DW_DISTANT_SURFACE_Y) return 1e9;
    return max((DW_DISTANT_SURFACE_Y-CameraInfo.y)/ray.y,0.0);
}
float dwBackgroundFogDistance(vec3 ray) {
    // Sky is beyond the local streaming range. Integrate a long WORLD-space path,
    // so distant clouds dissolve into air before the geometric cloud-shell horizon.
    // Upward rays naturally leave the exponential layer, rather than receiving a fixed alpha.
    // A captured sky has no actual sea-plane geometry. Stopping its extinction at that
    // hypothetical plane made the terminal background clearer than the foreground.
    // Both volume sampling and its full-resolution resolve use this same distant path.
    return nlModuleFlag(4)?12000.0:min(12000.0,dwBackgroundSurfaceDistance(ray));
}
vec3 dwFogColour(vec3 ray) {
    float day=dwDaylight();
    vec3 air=mix(vec3(0.078,0.099,0.131),vec3(0.37,0.415,0.46),day);
    vec3 blueAir=mix(vec3(.065,.092,.143),vec3(.34,.425,.525),day);
    air=mix(air,blueAir,WeatherTint.x);
    // Restrained directional response, not bloom and not a hard white horizon stripe.
    float facing=pow(max(dot(ray,normalize(Sun.xyz)),0.0),4.0);
    air*=1.0+0.08*facing*Sun.w;
    float storm=nlStormStrength();
    return air*mix(1.0,.91,min(storm,1.0))*(1.0-max(storm-1.0,0.0)*.05);
}
vec3 dwDistantBackground(vec3 ray) {
    float day=dwDaylight();
    vec3 horizon=mix(vec3(0.062,0.083,0.115),vec3(0.32,0.38,0.45),day);
    vec3 zenith=mix(vec3(0.017,0.027,0.048),vec3(0.25,0.335,0.445),day);
    vec3 blueHorizon=mix(vec3(.047,.073,.125),vec3(.29,.405,.56),day);
    vec3 blueZenith=mix(vec3(.012,.025,.060),vec3(.12,.265,.475),day);
    horizon=mix(horizon,blueHorizon,WeatherShape.w);
    zenith=mix(zenith,blueZenith,WeatherShape.w);
    vec3 sky=mix(horizon,zenith,smoothstep(0.0,0.85,max(ray.y,0.0)));
    // Smooth LOWER hemisphere substrate: no literal white/black void when flying above the mist.
    float ground=1.0-smoothstep(-0.20,-0.025,ray.y);
    float distanceBlocks=min(dwBackgroundSurfaceDistance(ray),16000.0);
    vec2 p=mod(CameraInfo.xz+ray.xz*distanceBlocks,4096.0);
    float relief=0.96+0.08*dwPeriodicNoise(p/1024.0,4.0);
    vec3 surface=mix(vec3(0.062,0.080,0.106),vec3(0.27,0.315,0.345),day)*relief;
    float storm=nlStormStrength();
    return mix(sky,surface,ground)*mix(1.0,.74,min(storm,1.0))*(1.0-max(storm-1.0,0.0)*.10);
}
#endif
