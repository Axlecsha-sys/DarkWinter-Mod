#version 450
// Vulkan 1.1 resolve: reverse-Z nearest depth plus true geometric coverage.
// A2C output alpha is squared by the mask/resolve; count covered depth samples
// instead. RGB already contains the resolve average and must not be multiplied again.
layout(set=0,binding=0) uniform sampler2DMS SceneDepth;
layout(set=0,binding=2) uniform sampler2D ResolvedColor;
layout(location=0) out vec4 fragColor;
void main(){
    ivec2 pixel=ivec2(gl_FragCoord.xy);
    float depth=0.;int covered=0,samples=textureSamples(SceneDepth);
    for(int i=0;i<samples;++i){float d=texelFetch(SceneDepth,pixel,i).r;depth=max(depth,d);covered+=d>0.?1:0;}
    gl_FragDepth=depth;
    fragColor=vec4(texelFetch(ResolvedColor,pixel,0).rgb,float(covered)/float(samples));
}
