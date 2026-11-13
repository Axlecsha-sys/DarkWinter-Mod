// Draws only the work-plane brush outline over the retained scene.
#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 outColor;
layout(push_constant) uniform BrushPush {vec4 ring;vec4 accent;vec4 state;} brush;
void main(){
    if(brush.state.x<0.5)discard;
    vec2 p=(uv-brush.ring.xy)/max(brush.ring.zw,vec2(0.000001));
    float distance=length(p)-1.0,edge=max(fwidth(distance),0.0001);
    float coverage=1.0-smoothstep(edge*0.5,edge*1.8,abs(distance));
    if(coverage<=0.001)discard;
    vec3 color=brush.accent.rgb;
    if(brush.state.y>0.5)color=mix(color/12.92,pow((color+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),color));
    outColor=vec4(color,brush.accent.a*coverage);
}
