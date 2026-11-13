// Samples the actual RGBA8 frame and draws a bounded work-plane brush outline.
#version 450
layout(binding=0) uniform sampler2D previewImage;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 outColor;
layout(push_constant) uniform Overlay {
    vec4 ring;
    vec4 accent;
    vec4 state;
} overlay;
void main() {
    vec4 pixel=texture(previewImage,uv);
    vec3 color=mix(vec3(0.025,0.055,0.085),pixel.rgb,pixel.a);
    if(overlay.state.x>0.5) {
        vec2 p=(uv-overlay.ring.xy)/max(overlay.ring.zw,vec2(0.000001));
        float distance=length(p)-1.0;
        float edge=max(fwidth(distance),0.0001);
        float stroke=1.0-smoothstep(edge*0.5,edge*1.8,abs(distance));
        color=mix(color,overlay.accent.rgb,stroke*overlay.accent.a);
    }
    if(overlay.state.y>0.5)color=mix(color/12.92,pow((color+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),color));
    outColor=vec4(color,1.0);
}
