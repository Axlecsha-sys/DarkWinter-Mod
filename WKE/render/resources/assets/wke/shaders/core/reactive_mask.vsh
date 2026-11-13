#version 330
#extension GL_ARB_separate_shader_objects : require
// Raster NDC coordinates from actual world projection. The host viewport applies exactly the world's orientation.
layout(location=0) in vec3 Position;
layout(location=1) in vec4 ClipBounds;
void main() {
    vec2 clip=mix(ClipBounds.xy,ClipBounds.zw,Position.xy);
    gl_Position=vec4(clip,0.0,1.0);
}
