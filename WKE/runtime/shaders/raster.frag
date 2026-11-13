#version 450
layout(set=0,binding=1,std430) readonly buffer Assets {uint a[];};
layout(set=0,binding=5,std430) readonly buffer Avatar {uint v[];};
layout(set=0,binding=9) uniform sampler2DArray WorldTextures;
layout(push_constant) uniform View {
    vec4 origin;vec4 forward;vec4 right;vec4 up;uvec4 settings;
    vec4 actor;vec4 pose;vec4 avatar;
} view;
layout(location=0) centroid in vec2 uv;
layout(location=1) centroid in vec3 localPoint;
layout(location=2) flat in vec3 surfaceNormal;
layout(location=3) centroid in vec3 relativePoint;
layout(location=4) flat in uvec2 triangleShape;
layout(location=5) flat in vec3 instanceOrigin;

// Re-evaluate the source triangle only for a fragment that rasterization emitted.
// CPU direction()/reference() use ordered float operations with contraction off.
// Precise locals preserve that operation order instead of an interpolated point
// on the hardware's subpixel triangle. This is not a scene/BVH ray traversal.
float sourceDot(vec3 a,vec3 b) {
    precise float value=(a.x*b.x+a.y*b.y)+a.z*b.z;
    return value;
}
vec3 sourceCross(vec3 a,vec3 b) {
    precise vec3 value=vec3(a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x);
    return value;
}
vec3 assetPoint(uint at) {
    return uintBitsToFloat(uvec3(a[at],a[at+1u],a[at+2u]));
}
vec2 assetUv(uint at) {
    return uintBitsToFloat(uvec2(a[at],a[at+1u]));
}
vec3 pixelRay() {
    precise float horizontal=(2.*gl_FragCoord.x/view.right.w-1.)*view.forward.w*view.origin.w;
    precise float vertical=(1.-2.*gl_FragCoord.y/view.up.w)*view.origin.w;
    precise vec3 direction=(view.forward.xyz+view.right.xyz*horizontal)+view.up.xyz*vertical;
    precise float inverseLength=1./sqrt(sourceDot(direction,direction));
    precise vec3 ray=direction*inverseLength;
    return ray;
}
bool sourceIntersection(out float distance,out vec2 coordinates,out vec3 modelPoint,
                        out vec3 cameraRelative,out float depth) {
    uint t=triangleShape.x;
    precise vec3 ray=pixelRay();
    precise vec3 localOrigin=view.origin.xyz-instanceOrigin;
    vec3 p=assetPoint(t),e1=assetPoint(t+4u),e2=assetPoint(t+8u);
    precise vec3 q=sourceCross(ray,e2);
    precise float determinant=sourceDot(e1,q);
    if(abs(determinant)<1e-9)return false;
    precise float inverseDeterminant=1./determinant;
    precise vec3 s=localOrigin-p;
    precise float u=sourceDot(s,q)*inverseDeterminant;
    if(u<0.||u>1.)return false;
    precise vec3 r=sourceCross(s,e1);
    precise float v=sourceDot(ray,r)*inverseDeterminant;
    if(v<0.||u+v>1.)return false;
    precise float hitDistance=sourceDot(e2,r)*inverseDeterminant;
    if(!(hitDistance>1e-4)||hitDistance>512.)return false;
    precise vec2 hitUv=(assetUv(t+12u)+assetUv(t+14u)*u)+assetUv(t+16u)*v;
    precise vec3 relative=ray*hitDistance;
    precise vec3 point=localOrigin+relative;
    // A coordinate-constant plane has one depth independent of triangle area.
    // Keep the source hit, UVs and alpha exactly as above; stabilize only depth.
    // Camera-relative subtraction preserves precision near the world limits.
    precise vec3 depthRelative=relative;
    if(e1.x==0.&&e2.x==0.&&ray.x!=0.)
        depthRelative=ray*((p.x-localOrigin.x)/ray.x);
    else if(e1.y==0.&&e2.y==0.&&ray.y!=0.)
        depthRelative=ray*((p.y-localOrigin.y)/ray.y);
    else if(e1.z==0.&&e2.z==0.&&ray.z!=0.)
        depthRelative=ray*((p.z-localOrigin.z)/ray.z);
    precise float forwardDistance=sourceDot(depthRelative,view.forward.xyz);
    if(forwardDistance<.05)return false;
    precise float hitDepth=.05/forwardDistance;
    distance=hitDistance;coordinates=hitUv;modelPoint=point;
    cameraRelative=relative;depth=hitDepth;
    return true;
}

uint hash2(int x,int z) {
    uint h=uint(x)*0x1f123bb5u^uint(z)*0x5f356495u;
    h^=h>>16;h*=0x45d9f3bu;return h^(h>>16);
}
vec4 rgba(uint packed) {
    return vec4(packed&255u,(packed>>8)&255u,(packed>>16)&255u,packed>>24)/255.;
}
vec4 worldTexel(vec2 coordinates,vec3 modelPoint,vec2 uvDx,vec2 uvDy,
                vec2 planarDx,vec2 planarDy,out bool rejected) {
    uint t=triangleShape.x,shape=triangleShape.y;
    uint flags=a[t+7u],d=a[1]+shape*8u;
    ivec3 cell=ivec3(instanceOrigin);
    vec2 tex=coordinates,texDx=uvDx,texDy=uvDy;
    // These authored atlas mappings are identical to trace.comp::texel.
    if((flags&3u)==1u) {
        uint tile=hash2(int(floor(float(cell.x)/4)),int(floor(float(cell.z)/4)))&7u;
        bool horizontal=abs(surfaceNormal.y)>.9;
        vec2 field=horizontal?modelPoint.xz:tex;
        texDx=(horizontal?planarDx:uvDx)*.25;texDy=(horizontal?planarDy:uvDy)*.25;
        tex=(vec2((cell.x%4+4)%4,(cell.z%4+4)%4)+field)/4;
        if((tile&4u)!=0u){tex.x=1-tex.x;texDx.x=-texDx.x;texDy.x=-texDy.x;}
        for(uint i=0u;i<(tile&3u);++i){tex=vec2(1-tex.y,tex.x);texDx=vec2(-texDx.y,texDx.x);texDy=vec2(-texDy.y,texDy.x);}
    } else if((flags&3u)==2u) {
        uint tile=hash2(cell.x-int(a[d+4u]),cell.z-int(a[d+5u]))&15u;
        vec2 quadrant=step(vec2(.5),tex);
        if(quadrant!=vec2(1)){
            tex=quadrant*.5+(vec2(tile&3u,tile>>2)+(tex-quadrant*.5)*2)/8;
            texDx*=.25;texDy*=.25;
        }
    }
    vec4 sampled;
    if((view.settings.x&2u)!=0u) {
        // Explicit original-source alpha mode for the independent geometry oracle.
        // This path does not claim to validate filtered texture appearance.
        uint td=a[4]+a[t+3u]*4u,width=a[td+1u],height=a[td+2u];
        uvec2 pixel=uvec2(clamp(tex,vec2(0),vec2(.999999))*vec2(width,height));
        sampled=rgba(a[a[5]+a[td]+pixel.x+width*pixel.y]);
    } else {
        // Derivatives came from continuous interpolants before any source-hit or
        // alpha discard. Atlas tile rotation/scaling is applied to gradients too.
        if((flags&8u)!=0u) {
            vec2 dimensions=vec2(textureSize(WorldTextures,0).xy);
            float footprint=max(length(texDx*dimensions),length(texDy*dimensions));
            // Keep at least a 4x4 coverage mask for very distant needles; reducing
            // an entire cutout layer to one thresholded texel would erase a crown.
            float limit=max(dimensions.x,dimensions.y)*.25;
            float scale=min(1.,limit/max(footprint,1e-8));texDx*=scale;texDy*=scale;
        }
        sampled=textureGrad(WorldTextures,vec3(clamp(tex,vec2(0),vec2(1)),float(a[t+3u])),texDx,texDy);
    }
    vec4 color=sampled*rgba(a[t+11u]);
    rejected=(flags&8u)!=0u&&color.a<((view.settings.x&4u)!=0u?.001:.5);
    return color;
}
vec4 actorTexel(out bool rejected) {
    uint t=triangleShape.x;
    uvec2 pixel=uvec2(clamp(uv,vec2(0),vec2(.999999))*vec2(v[5],v[6]));
    uint packed=v[v[4]+pixel.x+v[5]*pixel.y];
    // Avatar cutout tests the source alpha before tint, as actorTrace does.
    rejected=(packed>>24)<128u;
    return rgba(packed)*rgba(v[t+11u]);
}

layout(set=0,binding=4,std430) readonly buffer Overlay {uint ui[];};
layout(set=0,binding=8,std140) uniform SceneFrame {
    mat4 inverseViewProjection;
    mat4 shadowFromRelative;
    vec4 Sun;vec4 Air;vec4 Shadow;vec4 Climate;vec4 WindTime;vec4 Flags;
    vec4 CameraInfo;vec4 Options;vec4 Look;vec4 Analog;vec4 FogLayer;vec4 FogStyle;
    vec4 FogRange;vec4 WeatherShape;vec4 WeatherTint;vec4 CloudDrift;vec4 Film;vec4 FilmTiming;
} scene;
layout(location=0) out vec4 rawColor;
layout(location=1) out uvec2 materialDistance;

void main() {
    vec2 uvDx=dFdx(uv),uvDy=dFdy(uv),planarDx=dFdx(localPoint.xz),planarDy=dFdy(localPoint.xz);
    // Every surviving path assigns depth, including actor and first-person hand.
    gl_FragDepth=gl_FragCoord.z;
    float distance=length(relativePoint);
    vec3 position=relativePoint;
    bool rejected;
    vec4 sampleColor;
    if(view.settings.w==0u) {
        vec2 coordinates=uv;vec3 modelPoint=localPoint;
        // Adjacent axis-aligned snow faces must share an identical depth even
        // when their source triangles have very different areas. Keep raster
        // coverage; this plane evaluation deliberately does not reject edges.
        uint triangle=triangleShape.x;
        vec3 e1=assetPoint(triangle+4u),e2=assetPoint(triangle+8u);
        int axis=e1.x==0.&&e2.x==0.?0:e1.y==0.&&e2.y==0.?1:e1.z==0.&&e2.z==0.?2:-1;
        if(axis>=0){
            precise vec3 ray=pixelRay();
            if(ray[axis]!=0.){
                precise vec3 localOrigin=view.origin.xyz-instanceOrigin;
                precise float planeDistance=(assetPoint(triangle)[axis]-localOrigin[axis])/ray[axis];
                precise float forwardDistance=sourceDot(ray*planeDistance,view.forward.xyz);
                if(forwardDistance>0.)gl_FragDepth=.05/forwardDistance;
            }
        }
        // Exact source-ray clipping belongs only to the independent oracle.
        // Clipping an MSAA fragment at the pixel center deletes valid edge samples.
        if((view.settings.x&2u)!=0u){
            float depth;
            if(!sourceIntersection(distance,coordinates,modelPoint,position,depth))discard;
            gl_FragDepth=depth;
        }
        sampleColor=worldTexel(coordinates,modelPoint,uvDx,uvDy,planarDx,planarDy,rejected);
    } else {
        sampleColor=actorTexel(rejected);
    }
    // Screen-space alpha coverage integrates cutout edges with the MSAA samples.
    float coverage=1.;
    if(view.settings.w==0u&&(view.settings.x&4u)!=0u&&(a[triangleShape.x+7u]&8u)!=0u)
        coverage=clamp((sampleColor.a-.5)/max(fwidth(sampleColor.a),1e-3)+.5,0.,1.);
    if(rejected||coverage<=0.)discard;
    vec3 normal=surfaceNormal;
    if(dot(normal,position)>0)normal=-normal;
    bool northern=length(view.avatar.yzw)>.5;
    vec3 sun=northern?normalize(view.avatar.yzw):normalize(vec3(-.35,.8,-.25));
    float daylight=northern?smoothstep(-.14,.04,sun.y):1.;
    float sunlight=northern?max(scene.Sun.w,0.):1.;
    float ambient=mix(.055,.28,daylight)+mix(.025,.17,daylight)*max(normal.y,0.);
    float lighting=ambient+.85*sunlight*max(dot(normal,sun),0.);
    float brightness=ui[0]==0u?1.:uintBitsToFloat(ui[4u+ui[0]*ui[1]]);
    vec3 linearAlbedo=pow(sampleColor.rgb,vec3(2.2));
    // A neutral lit LDR source. The original mod passes own shadow, sky, fog,
    // grading and film; no native cold tint, tone curve or repeated fog here.
    rawColor=vec4(pow(clamp(linearAlbedo*lighting*brightness,0.,1.),vec3(1./2.2)),coverage);
    materialDistance=view.settings.w==0u
        ?uvec2(a[a[1]+triangleShape.y*8u+3u],floatBitsToUint(distance))
        :uvec2(0xffffffffu,0x7f800000u);
    // Actor/hand pipelines disable writes to attachment 1. This integer MRT,
    // depth-tested with color, avoids the old racing fragment SSBO writes.
}
