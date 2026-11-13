#version 450
// 26.3 SkyRenderer.renderSunMoonAndStars: view * Y(-90deg) * X(starAngle).
// Positions are the exported original buildStars quads with SmallStarsMixin sizes.
layout(std140,set=0,binding=10) uniform WeatherSkyProjection {
    mat4 WeatherViewProjection;
    vec4 WeatherDepthConvention;
};
layout(std140,set=0,binding=12) uniform NativePresentation {
    vec4 Celestial;
    vec4 SkyStyle;
    vec4 Presentation;
    vec4 Reserved;
};

layout(std430,set=0,binding=16) readonly buffer NativeStars {
    vec4 positions[];
};
void main() {
    vec3 p=positions[gl_VertexIndex].xyz;
    float c=cos(SkyStyle.z),s=sin(SkyStyle.z);
    p=vec3(p.x,c*p.y-s*p.z,s*p.y+c*p.z);
    p=vec3(-p.z,p.y,p.x);
    gl_Position=WeatherViewProjection*vec4(p,1.0);
}
