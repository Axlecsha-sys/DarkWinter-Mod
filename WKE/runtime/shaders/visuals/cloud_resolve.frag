#version 450
// Adapts VanillaFogPipelines.CLASSIC_CLOUDS/cloud_blit.fsh to one native layer.
// The cloud shader already produced premultiplied RGB. Its original unpremultiply
// followed by SRC_ALPHA blending reduces exactly to cloud.rgb + base*(1-alpha).
// Cloud depth participates only in the test; main/world depth MUST remain unchanged.
layout(set=0,binding=0) uniform sampler2D MainSampler;
layout(set=0,binding=1) uniform sampler2D DepthSampler;
layout(set=0,binding=5) uniform sampler2D NativeSkySampler;
layout(set=0,binding=6) uniform sampler2D NativeCloudSampler;
layout(set=0,binding=15) uniform sampler2D CloudDepthSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

ivec2 pixelAt(vec2 uv,ivec2 size) {
    return clamp(ivec2(uv*vec2(size)),ivec2(0),size-1);
}
void main() {
    float worldDepth=texelFetch(DepthSampler,pixelAt(texCoord,textureSize(DepthSampler,0)),0).r;
    vec4 world=texelFetch(MainSampler,pixelAt(texCoord,textureSize(MainSampler,0)),0);
    vec3 sky=texelFetch(NativeSkySampler,pixelAt(texCoord,textureSize(NativeSkySampler,0)),0).rgb;
    float coverage=worldDepth>1e-7?clamp(world.a,0.,1.):0.;
    ivec2 cloudPixel=pixelAt(texCoord,textureSize(NativeCloudSampler,0));
    vec4 cloud=texelFetch(NativeCloudSampler,cloudPixel,0);
    float cloudDepth=texelFetch(CloudDepthSampler,
        pixelAt(texCoord,textureSize(CloudDepthSampler,0)),0).r;
    // Resolved world RGB is already coverage-weighted. Show the sky/clouds
    // through uncovered samples without a dark halo or double multiplication.
    bool inFront=cloudDepth>=worldDepth;
    vec3 background=(!inFront&&cloud.a>0.)?cloud.rgb+sky*(1.-cloud.a):sky;
    vec3 color=world.rgb+background*(1.-coverage);
    if(inFront&&cloud.a>0.)color=cloud.rgb+color*(1.-cloud.a);
    fragColor=vec4(color,1.0);
}
