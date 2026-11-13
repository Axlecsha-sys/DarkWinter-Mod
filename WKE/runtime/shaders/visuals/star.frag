#version 450
// Exact stars.fsh ColorModulator behavior. Render with the same OVERLAY blend
// as 26.3: RGB SRC_ALPHA/ONE, alpha ONE/ZERO, depth test/write disabled.
layout(std140,set=0,binding=12) uniform NativePresentation {
    vec4 Celestial;
    vec4 SkyStyle;
    vec4 Presentation;
    vec4 Reserved;
};

layout(location=0) out vec4 fragColor;
void main() {
    fragColor=vec4(Celestial.w);
}
