#version 330
#extension GL_ARB_separate_shader_objects : require
#include <darkwinter:svg_distance.glsl>
layout(location=0) in vec2 svgPosition;
layout(location=1) in vec4 svgTint;
layout(location=2) flat in ivec2 svgRange;
layout(location=3) flat in vec2 svgOptions;
layout(location=0) out vec4 fragColor;
void main(){
    fragColor=svgPaint(svgPosition,svgRange,svgTint,svgOptions);
    if(fragColor.a<=0.0)discard;
}
