// Draws the established procedural background before geographic celestial bodies and terrain fog.
#version 330
#extension GL_ARB_separate_shader_objects : require
#include <darkwinter_visuals:scene.glsl>
#include <darkwinter_visuals:weather_sky_ray.glsl>
#include <darkwinter_visuals:winter_haze.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    fragColor=vec4(dwDistantBackground(dwWeatherRay(texCoord)),1.0);
}
