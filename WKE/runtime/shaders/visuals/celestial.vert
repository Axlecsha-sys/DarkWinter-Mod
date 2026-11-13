#version 450
// SkyRenderer.buildCelestialQuad/buildMoonPhases + GeographicSunSkyMixin.
// Draw 12 TRIANGLE_LIST vertices, no vertex buffer. Sun is first, then Moon.
// Depth test/write disabled; CELESTIAL overlay blend: RGB SRC_ALPHA/ONE,
// alpha ONE/ZERO. Source PNGs remain unmodified and non-premultiplied.
layout(std140,set=0,binding=8) uniform DarkWinterScene {
    vec4 Inv0;
    vec4 Inv1;
    vec4 Inv2;
    vec4 Inv3;
    vec4 Light0;
    vec4 Light1;
    vec4 Light2;
    vec4 Light3;
    vec4 Sun;
    vec4 Air;
    vec4 Shadow;
    vec4 Climate;
    vec4 WindTime;
    vec4 Flags;
    vec4 CameraInfo;
    vec4 Options;
    vec4 Look;
    vec4 Analog;
    vec4 FogLayer;
    vec4 FogStyle;
    vec4 FogRange;
    vec4 WeatherShape;
    vec4 WeatherTint;
    vec4 CloudDrift;
    vec4 Film;
    vec4 FilmTiming;
};
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

layout(location=0) out vec2 texCoord;
layout(location=1) flat out int body;
layout(location=2) flat out vec4 colorModulator;
layout(location=3) flat out float moonPhase;

vec3 rotateFromUp(vec3 p,vec3 direction) {
    // Quaternionf.rotationTo((0,1,0), direction), with its antiparallel case.
    vec4 q=direction.y < -0.999999 ? vec4(1,0,0,0)
            : normalize(vec4(direction.z,0.0,-direction.x,1.0+direction.y));
    return p+2.0*cross(q.xyz,cross(q.xyz,p)+q.w*p);
}
void main() {
    const vec2 corners[6]=vec2[6](
        vec2(-1,-1),vec2(1,-1),vec2(1,1),
        vec2(-1,-1),vec2(1,1),vec2(-1,1));
    body=gl_VertexIndex/6;
    vec2 corner=corners[gl_VertexIndex%6];
    vec3 direction=normalize(body==0 ? Sun.xyz : WeatherTint.yzw);
    float bound=body==0 ? CloudDrift.z : CloudDrift.w;
    // SceneFrame stores atan(sqrt(2)*halfSize/distance): undo only that bound.
    float halfSize=100.0*tan(bound)*0.7071067811865475;
    vec3 position=rotateFromUp(vec3(corner.x*halfSize,100.0,corner.y*halfSize),direction);
    gl_Position=WeatherViewProjection*vec4(position,1.0);
    texCoord=corner*0.5+0.5;
    // The exact 26.3 Moon quad reverses both atlas axes relative to the Sun.
    if(body==1) texCoord=1.0-texCoord;
    float rgb=body==0 ? SkyStyle.x : SkyStyle.y;
    float alpha=body==0 ? Celestial.y : Celestial.z;
    colorModulator=vec4(vec3(rgb),alpha);
    moonPhase=clamp(floor(Celestial.x+0.5),0.0,7.0);
}
