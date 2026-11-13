#version 450
layout(set=0,binding=1,std430) readonly buffer Assets {uint a[];};
layout(set=0,binding=5,std430) readonly buffer Avatar {uint v[];};
layout(set=0,binding=9) uniform sampler2DArray WorldTextures;
layout(push_constant) uniform View {
    vec4 origin;vec4 forward;vec4 right;vec4 up;uvec4 settings;
    vec4 actor;vec4 pose;vec4 avatar;
} view;
layout(location=0) in vec2 uv;
layout(location=1) in vec3 localPoint;
layout(location=2) flat in vec3 surfaceNormal;
layout(location=3) in vec3 relativePoint;
layout(location=4) flat in uvec2 triangleShape;
layout(location=5) flat in vec3 instanceOrigin;

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
    rejected=(flags&8u)!=0u&&color.a<.5;
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

void main() {
    vec2 uvDx=dFdx(uv),uvDy=dFdy(uv),planarDx=dFdx(localPoint.xz),planarDy=dFdy(localPoint.xz);
    bool rejected;
    if(view.settings.w==0u)worldTexel(uv,localPoint,uvDx,uvDy,planarDx,planarDy,rejected);else actorTexel(rejected);
    if(rejected)discard;
    // No color target: depth writes use exactly the same alpha silhouette.
}
