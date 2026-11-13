#version 450
// 26.3 core/position_tex.fsh: preserve transparent-texel discard and modulation.
// Only the atlas addressing becomes fixed Sun2D / Moon2DArray descriptors.
layout(set=0,binding=13) uniform sampler2D SunSampler;
layout(set=0,binding=14) uniform sampler2DArray MoonSampler;
layout(location=0) in vec2 texCoord;
layout(location=1) flat in int body;
layout(location=2) flat in vec4 colorModulator;
layout(location=3) flat in float moonPhase;
layout(location=0) out vec4 fragColor;
void main() {
    vec4 color=body==0 ? texture(SunSampler,texCoord)
                      : texture(MoonSampler,vec3(texCoord,moonPhase));
    if(color.a==0.0) discard;
    fragColor=color*colorModulator;
}
