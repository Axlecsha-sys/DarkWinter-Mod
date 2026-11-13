// Reconstructs camera rays without treating view-bob translation as a direction.
#ifndef DARKWINTER_WEATHER_SKY_RAY
#define DARKWINTER_WEATHER_SKY_RAY
vec3 dwWeatherUnproject(vec2 uv,float depth) {
    vec4 p=mat4(Inv0,Inv1,Inv2,Inv3)*vec4(uv*2.0-1.0,depth,1.0);
    return p.xyz/p.w;
}
vec3 dwWeatherRay(vec2 uv) {
    // Both samples lie on the same projective ray on either clip-depth convention.
    return normalize(dwWeatherUnproject(uv,0.0)-dwWeatherUnproject(uv,1.0));
}
vec3 dwWeatherOrigin() {
    // Perspective projection maps this clip-space direction back to the optical center.
    vec4 p=mat4(Inv0,Inv1,Inv2,Inv3)*vec4(0,0,1,0);
    return abs(p.w)>1e-7?p.xyz/p.w:vec3(0);
}
#endif
