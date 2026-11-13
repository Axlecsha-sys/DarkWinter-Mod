// Exact binary32 alpha coverage predicate for normalized shadow caster textures.
// Main-pass RGBA sampling remains in material_sampling.glsl.
#ifndef WKE_MATERIAL_SHADOW_COVERAGE_GLSL
#define WKE_MATERIAL_SHADOW_COVERAGE_GLSL
#include <wke:material_sampling.glsl>

bool wkeShadowAtlasCovers(sampler2D source,vec2 uv,vec2 texelSize,vec2 du,vec2 dv,
                          int rgss,float vertexAlpha,float cutoff,out int taps) {
    float texelsPerPixel=max(length(du/texelSize),length(dv/texelSize));
    taps=0; // Zero denotes the unchanged host magnification/transition/non-RGSS path.
    if(rgss==1 && texelsPerPixel>1.0 && vertexAlpha==1.0 && cutoff>=0.0 && cutoff<=1.0) {
        // All bound atlas/dedicated alpha channels are normalized [0,1].
        // At most five binary32 add-rounding errors separate a prefix from any
        // reassociation of the original four-tap sum. 2^-18 exceeds that error
        // plus threshold rounding (six times 2^-23). Multiplication by .25 is exact.
        float acceptAbove=4.0*cutoff+0.000003814697265625;
        vec4 first=textureGrad(source,uv+du*.125+dv*.375,du,dv);
        vec4 sum=first;taps=1;
        if(sum.a>acceptAbove || (first.a==1.0 && cutoff<=.25)) return true;
        vec4 second=textureGrad(source,uv-du*.125-dv*.375,du,dv);
        sum+=second;taps=2;
        // Exact unit terms also provide an exact lower bound, including cutoff .5.
        if(sum.a>acceptAbove || (first.a==1.0 && second.a==1.0 && cutoff<=.5)) return true;
        vec4 third=textureGrad(source,uv+du*.375-dv*.125,du,dv);
        sum+=third;taps=3;
        if(sum.a>acceptAbove || (first.a==1.0 && second.a==1.0 && third.a==1.0 && cutoff<=.75)) return true;
        sum+=textureGrad(source,uv-du*.375+dv*.125,du,dv);taps=4;
        // Preserve the original accumulation/order and less-than predicate at the boundary.
        vec4 color=(sum*.25)*vec4(1.0,1.0,1.0,vertexAlpha);
        return !(color.a<cutoff);
    }
    vec4 color=wkeSampleAtlas(source,uv,texelSize,du,dv,rgss)*vec4(1.0,1.0,1.0,vertexAlpha);
    return !(color.a<cutoff);
}

bool wkeShadowMaterialCovers(sampler2D source,vec2 uv,vec2 texelSize,int rgss,
                             float vertexAlpha,float cutoff,out int taps) {
    // Derivatives precede material selection and every potentially divergent return.
    vec2 du=dFdx(uv),dv=dFdy(uv);
#ifdef WKE_FOLIAGE_TEXTURES
    if(WkeFoliageParams.x>.5) {
        for(int layer=0;layer<2;++layer) {
            vec4 bounds=WkeFoliageRects[layer];
            vec2 extent=bounds.zw-bounds.xy;
            if(all(greaterThan(extent,vec2(0.0))) &&
               all(greaterThanEqual(uv,bounds.xy)) && all(lessThanEqual(uv,bounds.zw))) {
                vec2 localUv=(uv-bounds.xy)/extent;
                vec2 localDu=du/extent,localDv=dv/extent;
                if(layer==0) return wkeShadowAtlasCovers(WkeNeedlesSampler,localUv,
                        1.0/vec2(textureSize(WkeNeedlesSampler,0)),localDu,localDv,rgss,vertexAlpha,cutoff,taps);
                return wkeShadowAtlasCovers(WkeBranchSampler,localUv,
                        1.0/vec2(textureSize(WkeBranchSampler,0)),localDu,localDv,rgss,vertexAlpha,cutoff,taps);
            }
        }
    }
#endif
    return wkeShadowAtlasCovers(source,uv,texelSize,du,dv,rgss,vertexAlpha,cutoff,taps);
}
bool wkeShadowMaterialCovers(sampler2D source,vec2 uv,vec2 texelSize,int rgss,float vertexAlpha,float cutoff) {
    int unusedTaps;
    return wkeShadowMaterialCovers(source,uv,texelSize,rgss,vertexAlpha,cutoff,unusedTaps);
}
#endif
