// Native-resolution directional edge reconstruction. No frame history or projection jitter.
#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D MainSampler;
uniform sampler2D DepthSampler;
layout(std140) uniform WkeAntialias {
    // enabled; endpoint search radius in native pixels; relative contrast threshold; depth Y flip
    vec4 Controls;
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

float edgeLuma(vec3 color) { return dot(color,vec3(.2126,.7152,.0722)); }
vec2 safeUv(vec2 uv,vec2 pixel) { return clamp(uv,pixel*.5,vec2(1)-pixel*.5); }
float imageLuma(vec2 uv,vec2 pixel) {
    return edgeLuma(texture(MainSampler,safeUv(uv,pixel)).rgb);
}
float imageDepth(ivec2 point,ivec2 size) {
    point=clamp(point,ivec2(0),size-1);
    if(Controls.w>.5) point.y=size.y-1-point.y;
    return texelFetch(DepthSampler,point,0).r;
}
void main() {
    ivec2 size=textureSize(MainSampler,0);
    ivec2 point=clamp(ivec2(texCoord*vec2(size)),ivec2(0),size-1);
    vec4 source=texelFetch(MainSampler,point,0);
    // OFF is an exact source texel, independent of the bilinear sampler used by ON.
    if(Controls.x<.5) { fragColor=source;return; }
    vec2 pixel=1.0/vec2(size),uv=(vec2(point)+.5)*pixel;
    float m=edgeLuma(source.rgb);
    float n=imageLuma(uv+vec2(0,pixel.y),pixel);
    float s=imageLuma(uv-vec2(0,pixel.y),pixel);
    float e=imageLuma(uv+vec2(pixel.x,0),pixel);
    float w=imageLuma(uv-vec2(pixel.x,0),pixel);
    float lo=min(m,min(min(n,s),min(e,w)));
    float hi=max(m,max(max(n,s),max(e,w)));
    float range=hi-lo;
    // Flat and low-contrast pixels keep their original RGB. Grain is added later.
    if(range<max(.03125,hi*clamp(Controls.z,.05,.25))) { fragColor=source;return; }

    float ne=imageLuma(uv+pixel,pixel);
    float nw=imageLuma(uv+vec2(-pixel.x,pixel.y),pixel);
    float se=imageLuma(uv+vec2(pixel.x,-pixel.y),pixel);
    float sw=imageLuma(uv-pixel,pixel);
    float horizontal=2.0*abs(n+s-2.0*m)+abs(nw+sw-2.0*w)+abs(ne+se-2.0*e);
    float vertical=2.0*abs(e+w-2.0*m)+abs(nw+ne-2.0*n)+abs(sw+se-2.0*s);
    bool alongX=horizontal>=vertical;
    float positive=alongX?n:e,negative=alongX?s:w;
    float gradientPositive=abs(positive-m),gradientNegative=abs(negative-m);
    bool towardPositive=gradientPositive>=gradientNegative;
    float gradient=max(gradientPositive,gradientNegative);
    float boundaryLuma=.5*(m+(towardPositive?positive:negative));
    vec2 normal=(alongX?vec2(0,pixel.y):vec2(pixel.x,0))*(towardPositive?1.0:-1.0);
    vec2 tangent=alongX?vec2(pixel.x,0):vec2(0,pixel.y);
    vec2 boundary=uv+normal*.5;

    // Search the actual edge in both directions. A straight edge has no endpoint
    // imbalance and receives no displacement; short staircase steps receive more.
    float limit=clamp(Controls.y,2.0,12.0);
    float distanceNegative=1.0,distancePositive=1.0;
    float lumaNegative=0.0,lumaPositive=0.0;
    bool endNegative=false,endPositive=false;
    for(int step=1;step<=12;step++) {
        if(float(step)>limit) break;
        if(!endNegative) {
            distanceNegative=float(step);
            lumaNegative=imageLuma(boundary-tangent*distanceNegative,pixel)-boundaryLuma;
            endNegative=abs(lumaNegative)>=gradient*.25;
        }
        if(!endPositive) {
            distancePositive=float(step);
            lumaPositive=imageLuma(boundary+tangent*distancePositive,pixel)-boundaryLuma;
            endPositive=abs(lumaPositive)>=gradient*.25;
        }
        if(endNegative&&endPositive) break;
    }
    bool negativeCloser=distanceNegative<distancePositive;
    float nearest=min(distanceNegative,distancePositive);
    float endpoint=negativeCloser?lumaNegative:lumaPositive;
    bool endpointFound=negativeCloser?endNegative:endPositive;
    float edgeOffset=.5-nearest/max(distanceNegative+distancePositive,1.0);
    if(!endpointFound || (endpoint<0.0)==(m<boundaryLuma)) edgeOffset=0.0;

    // Sparse needles can be shorter than a search span. Their restrained subpixel
    // correction is enabled only at a depth discontinuity, never across a smooth
    // snow plane or inside a texture. Reverse-Z clear (zero) is handled naturally.
    ivec2 depthSize=textureSize(DepthSampler,0);
    float subpixelOffset=0.0;
    if(all(equal(size,depthSize))) {
        float dm=imageDepth(point,depthSize);
        float dn=imageDepth(point+ivec2(0,1),depthSize);
        float ds=imageDepth(point-ivec2(0,1),depthSize);
        float de=imageDepth(point+ivec2(1,0),depthSize);
        float dw=imageDepth(point-ivec2(1,0),depthSize);
        float depthScale=max(max(dm,max(max(dn,ds),max(de,dw))),1e-6);
        // Perspective depth on a planar triangle is affine in screen space:
        // its second difference vanishes without requiring world reconstruction.
        float curvature=max(abs(dn+ds-2.0*dm),abs(de+dw-2.0*dm))/depthScale;
        float geometryEdge=smoothstep(.01,.08,curvature);
        float average=(2.0*(n+s+e+w)+ne+nw+se+sw)/12.0;
        float subpixel=smoothstep(0.0,1.0,abs(average-m)/max(range,1e-6));
        subpixelOffset=.25*subpixel*subpixel*geometryEdge;
    }
    float offset=clamp(max(edgeOffset,subpixelOffset),0.0,.5);
    if(offset<=1e-5) { fragColor=source;return; }
    // A single fractional sample across the reconstructed edge. Alpha and depth
    // keep the host compositing contract; hand/HUD are rendered after this pass.
    fragColor=vec4(texture(MainSampler,safeUv(uv+normal*offset,pixel)).rgb,source.a);
}
