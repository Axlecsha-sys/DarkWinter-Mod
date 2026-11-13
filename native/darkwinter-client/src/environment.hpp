#pragma once
#include "scene.hpp"
namespace dw::client {
// Port of the project's SolarEphemeris.calculateCalendar, driven by the saved game clock.
inline V3 northernSun(double seconds){
 using namespace std::chrono;double clock=std::max(0.,seconds)*5.,day=std::floor(clock/24000.),hour=std::fmod(clock/1000.+6.,24.);
 auto date=year_month_day{sys_days{year{1997}/10/8}+days{int64_t(std::floor((6+clock/1000.)/24.))}};
 auto jan=sys_days{date.year()/1/1};double doy=double((sys_days{date}-jan).count()),yearDays=date.year().is_leap()?366.:365.;(void)day;
 double gamma=6.283185307179586/yearDays*(doy+(hour-12)/24.);
 double eq=229.18*(.000075+.001868*std::cos(gamma)-.032077*std::sin(gamma)-.014615*std::cos(2*gamma)-.040849*std::sin(2*gamma));
 double decl=.006918-.399912*std::cos(gamma)+.070257*std::sin(gamma)-.006758*std::cos(2*gamma)+.000907*std::sin(2*gamma)-.002697*std::cos(3*gamma)+.00148*std::sin(3*gamma);
 double solar=std::fmod(hour*60+eq-320+300+1440,1440),angle=(solar/4-180)*.017453292519943295,phi=73*.017453292519943295;
 return {float(-std::cos(decl)*std::sin(angle)),float(std::sin(phi)*std::sin(decl)+std::cos(phi)*std::cos(decl)*std::cos(angle)),float(-std::cos(phi)*std::sin(decl)+std::sin(phi)*std::cos(decl)*std::cos(angle))};
}

struct NativeClimate {int preset=0;float cover=1,fog=1,sun=1,skyBlue=0,fogBlue=0,wind=0,bearing=0,temperature=0;
 float snowfall=0,cloudRoundness=0,cloudScale=1,cloudStretch=1;
 // Wind speed remains metres/second for gameplay/HUD. WeatherValues' shader
 // strength is wind/18; these directions describe air movement, not its source.
 float windX()const{return -std::sin(bearing*.017453292519943295f);}
 float windZ()const{return std::cos(bearing*.017453292519943295f);}
 float chill()const{double v=wind*3.6;if(temperature>0||v==0)return temperature;double t=v<5?temperature+(-1.59+.1345*temperature)/5*v:13.12+.6215*temperature-11.37*std::pow(v,.16)+.3965*temperature*std::pow(v,.16);return float(std::min(double(temperature),t));}
};
// Same climate formula, profile table and neighbour graph as the mod; native seed/clock timeline.
inline NativeClimate nativeClimate(uint64_t seed,double seconds,int manual=-1,double daySeconds=-1){
 seconds=std::max(0.,seconds);auto random=[&](uint64_t index,uint64_t tag){uint64_t h=seed+index*0x9e3779b97f4a7c15ull+tag;h=(h^(h>>30))*0xbf58476d1ce4e5b9ull;h=(h^(h>>27))*0x94d049bb133111ebull;return h^(h>>31);};auto unit=[&](uint64_t index,uint64_t tag){return double(random(index,tag)>>11)/9007199254740992.;};
 struct Cache{uint64_t seed=0,step=0;bool ready=false;int from=0,to=0;double start=0,next=0,duration=0;};static thread_local Cache c;
 if(!c.ready||c.seed!=seed||seconds<c.start){c={seed,0,true,0,0,0,480+double(random(0,1)%721),0};}
 static constexpr int neighbours[9][3]={{2,4,5},{2,3,3},{1,0,4},{2,8,4},{2,0,5},{4,0,6},{5,7,8},{6,6,8},{3,6,2}};
 while(seconds>=c.next){c.from=c.to;c.start=c.next;++c.step;c.to=neighbours[c.to][random(c.step,2)%(c.to==1?2:3)];c.duration=45+double(random(c.step,3)%46);c.next+=480+double(random(c.step,4)%721);}
 float blend=c.duration?float(std::clamp((seconds-c.start)/c.duration,0.,1.)):1;blend=blend*blend*(3-2*blend);int target=manual>=0&&manual<9?manual:c.to,previous=manual>=0?target:c.from;
 static constexpr float cover[]={1,0,.32f,.18f,.42f,.82f,1,1,.25f},fog[]={1,.22f,.48f,.15f,.65f,1.35f,3.8f,6.7f,.38f},sun[]={1,1.10f,1.03f,1.04f,.88f,.55f,.10f,.04f,.95f},blue[]={0,1,.65f,.80f,.45f,.15f,.03f,0,.60f},lo[]={2,1,2,8,.6f,0,12,21,8},hi[]={6,3,5,14,2,0,18,28,14};
 NativeClimate s;s.preset=target;s.cover=std::lerp(cover[previous],cover[target],blend);s.fog=std::lerp(fog[previous],fog[target],blend);s.sun=std::lerp(sun[previous],sun[target],blend);s.skyBlue=std::lerp(blue[previous],blue[target],blend);static constexpr float fogBlue[]={0,.95f,.55f,1,.60f,.35f,.25f,.22f,.75f};s.fogBlue=std::lerp(fogBlue[previous],fogBlue[target],blend);
 // Exact additional WeatherPreset profiles, blended on the existing timeline.
 static constexpr float snow[]={0,0,0,0,.035f,.38f,1,1.65f,0};
 static constexpr float roundness[]={0,1,1,.35f,.70f,.30f,0,0,.40f};
 static constexpr float scale[]={1,1,.70f,1.35f,.70f,1.15f,.65f,.52f,1.20f};
 static constexpr float stretch[]={1,1,1,3,1,1.45f,2.2f,3.4f,3.4f};
 s.snowfall=std::lerp(snow[previous],snow[target],blend);
 s.cloudRoundness=std::lerp(roundness[previous],roundness[target],blend);
 s.cloudScale=std::lerp(scale[previous],scale[target],blend);
 s.cloudStretch=std::lerp(stretch[previous],stretch[target],blend);

 uint64_t windEpoch=uint64_t(seconds/420);double wt=std::clamp(std::fmod(seconds,420.)/60,0.,1.);wt=wt*wt*(3-2*wt);auto direction=[&](uint64_t k){double north=.65,coast=40./55,weights[]={14+2*north,20+9*coast,17+9*coast,7-2*north,7-3*north,6-2*north,12-4*coast,12+2*north},total=0;for(double v:weights)total+=v;double selected=unit(k,91)*total;for(int i=0;i<8;i++)if((selected-=weights[i])<0)return i*45.;return 315.;};double bearing0=direction(windEpoch?windEpoch-1:0),bearing1=direction(windEpoch),delta=std::fmod(bearing1-bearing0+540.,360.)-180;if(delta==-180)delta=180;s.bearing=float(std::fmod(bearing0+delta*wt+360.,360.));double speedUnit=std::lerp(unit(windEpoch?windEpoch-1:0,92),unit(windEpoch,92),wt),speed=std::lerp(lo[previous]+(hi[previous]-lo[previous])*speedUnit,lo[target]+(hi[target]-lo[target])*speedUnit,blend);s.wind=float(speed*(.94+.04*std::sin(seconds*.28)+.02*std::sin(seconds*.73+1.4)));
 using namespace std::chrono;double gameHours=6+(daySeconds<0?seconds:std::max(0.,daySeconds))/200.,hour=std::fmod(gameHours,24.);auto date=year_month_day{sys_days{year{1997}/10/8}+days{int64_t(std::floor(gameHours/24))}};double doy=double((sys_days{date}-sys_days{date.year()/1/1}).count()+1),north=.65,coast=40./55,seasonal=-6-11*north+(20-5*coast)*std::cos((doy-205)*6.28318530718/365.2425),daily=(1+2.2*(1-s.cover))*std::cos((hour-15)*6.28318530718/24),front[]={0,-1,0,0,0,0,-1.5,-3.5,-2},offset[]={-5,-3,-1.5,1,3,1.5,-1,-4.5};s.temperature=float(std::clamp(seasonal+daily+std::lerp(front[previous],front[target],blend)+std::lerp(offset[int(bearing0/45)%8],offset[int(bearing1/45)%8],wt),-65.,25.));return s;
}
// Same low-order visual lunar ephemeris as LunarEphemeris.calculateCalendar.
// This is the mod's authored 29.53-day model, not a scientific JPL ephemeris.
struct NativeMoon {V3 direction{};float elevationDegrees=0;uint32_t phase=0;};
inline NativeMoon northernMoon(double seconds) {
 constexpr double pi=3.14159265358979323846,rad=pi/180.,synodic=29.530588853;
 auto positive=[](double value,double period){return std::fmod(std::fmod(value,period)+period,period);};
 double clock=std::max(0.,seconds)*5.,day=std::floor(clock/24000.);
 double hour=positive((clock-day*24000.)/1000.+6.,24.);
 using namespace std::chrono;
 auto date=year_month_day{sys_days{year{1997}/10/8}+days{int64_t(std::floor((6.+clock/1000.)/24.))}};
 double dayOfYear=double((sys_days{date}-sys_days{date.year()/1/1}).count());
 double season=(dayOfYear+hour/24.)*365./(date.year().is_leap()?366.:365.);
 double anomaly=positive(357.528+.9856003*season,360.)*rad;
 double solar=positive(280.460+.9856474*season+1.915*std::sin(anomaly)+.020*std::sin(2*anomaly),360.)*rad;
 double lunarDays=clock/24000.,age=positive(synodic*.5+lunarDays,synodic);
 double lambda=solar+age*(2*pi/synodic),node=positive(125.08-.052954*lunarDays,360.)*rad;
 double beta=std::asin(std::sin(5.145*rad)*std::sin(lambda-node)),obliquity=23.4393*rad;
 double x=std::cos(beta)*std::cos(lambda);
 double y=std::cos(beta)*std::sin(lambda)*std::cos(obliquity)-std::sin(beta)*std::sin(obliquity);
 double z=std::cos(beta)*std::sin(lambda)*std::sin(obliquity)+std::sin(beta)*std::cos(obliquity);
 double alphaMoon=std::atan2(y,x),delta=std::asin(std::clamp(z,-1.,1.));
 double alphaSun=std::atan2(std::cos(obliquity)*std::sin(solar),std::cos(solar));
 double gamma=2*pi/365.*season;
 double equation=229.18*(.000075+.001868*std::cos(gamma)-.032077*std::sin(gamma)
                      -.014615*std::cos(2*gamma)-.040849*std::sin(2*gamma));
 double minutes=positive(hour*60.+equation-320.+300.,1440.);
 double angle=(minutes/4.-180.)*rad+alphaSun-alphaMoon,latitude=73.*rad;
 double east=-std::cos(delta)*std::sin(angle);
 double up=std::sin(latitude)*std::sin(delta)+std::cos(latitude)*std::cos(delta)*std::cos(angle);
 double north=std::cos(latitude)*std::sin(delta)-std::sin(latitude)*std::cos(delta)*std::cos(angle);
 uint32_t octant=uint32_t(std::floor(age/synodic*8.+.5))&7u;
 return {{float(east),float(up),float(-north)},float(std::asin(std::clamp(up,-1.,1.))/rad),(4u+octant)&7u};
}

}
