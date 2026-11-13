#pragma once
#include "renderer.hpp"
#include "native_visuals.hpp"

namespace dw::client {
// DarkWinter owns the geographic calendar, weather and visual profile. The WKE
// renderer consumes their packed frame data through its host-neutral interface.
class DarkWinterVisualProvider final : public RenderVisualProvider {
 NativeVisuals source;
public:
 RenderEnvironment environment(double seconds,uint64_t seed,int weather,double daySeconds) override {
  source.updateEnvironment(seconds,seed,weather,daySeconds);
  const auto sample=nativeClimate(seed,seconds,weather,daySeconds);
  const float bearing=sample.bearing*.01745329252f;
  return {{sample.cover,sample.sun,sample.fog,sample.skyBlue,
           -std::sin(bearing)*sample.wind,std::cos(bearing)*sample.wind,
           float(seconds),sample.fogBlue},
          northernSun(daySeconds<0?seconds:daySeconds)};
 }
 RenderVisualFrame prepare(const Camera& camera,const Params& params,float terrainDistance,
     const std::array<float,7>& video,bool temporalActive,bool firstPerson) override {
  const auto frame=source.prepare(camera,params,terrainDistance,video,temporalActive,firstPerson);
  return {frame.scene,frame.projection,frame.extras,
          frame.captureHeld,frame.allowCapture,frame.historyValid};
 }
 void commit() override {source.commit();}
 void invalidate() override {source.invalidate();}
};
}
