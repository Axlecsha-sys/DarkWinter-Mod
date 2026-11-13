#pragma once
#include "environment.hpp"
#include <array>
#include <chrono>

namespace dw::client {
struct NativeVisualFrame {
    // Exact mod std140 layouts: SceneFrame416, WeatherSkyProjection80, NativeSky64.
    std::array<float,104> scene{};
    std::array<float,20> projection{};
    std::array<float,16> extras{};
    bool captureHeld=false,allowCapture=false,historyValid=false;
};
static_assert(sizeof(NativeVisualFrame::scene)==416);
static_assert(sizeof(NativeVisualFrame::projection)==80);
static_assert(sizeof(NativeVisualFrame::extras)==64);

class NativeVisuals {
    static constexpr double pi=3.14159265358979323846;
    static constexpr int64_t heldPeriodNanos=55555555; // Java truncation of 1e9/18.
    static int64_t clockNanos() {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    static double smooth(double low,double high,double value) {
        double t=std::clamp((value-low)/(high-low),0.,1.);
        return t*t*(3-2*t);
    }
    // 26.3 Timelines.OVERWORLD_DAY STAR_ANGLE uses the unscaled overworld clock;
    // GeographicSunSkyMixin only replaces Sun/Moon directions and star brightness.
    // Keyframes 0 at 6000 -> 360 at the next 6000, with symmetric cubic Bezier.
    static float starAngle(double daylightSeconds) {
        double phase=std::fmod(std::fmod(daylightSeconds*20.-6000.,24000.)+24000.,24000.)/24000.;
        float x=float(phase);
        auto curve=[](float first,float second) {
            return std::array<float,3>{3.f*first-3.f*second+1.f,-6.f*first+3.f*second,3.f*first};
        };
        auto sample=[](const std::array<float,3>& c,float t){return ((c[0]*t+c[1])*t+c[2])*t;};
        auto xc=curve(.362f,1.f-.362f),yc=curve(.241f,1.f-.241f);
        float t=x;
        for(int i=0;i<4;++i) {
            float error=sample(xc,t)-x;
            if(std::abs(error)<1e-5f)return sample(yc,t)*360.f*float(pi/180.);
            float gradient=(3.f*xc[0]*t+2.f*xc[1])*t+xc[2];
            if(gradient<1e-5f)break;
            t-=std::clamp(error/gradient,-.25f,.25f);
        }
        float low=0,high=1;
        // EasingType's bisection, bounded additionally at float convergence.
        for(int i=0;i<64&&low<high;++i) {
            float error=sample(xc,t)-x;
            if(std::abs(error)<1e-5f)break;
            if(error<0)low=t;else high=t;
            float next=(high+low)/2;
            if(next==t)break;
            t=next;
        }
        return sample(yc,t)*360.f*float(pi/180.);
    }
    struct MotionEnvelope {
        float motion=0;
        float update(double seconds,double angular,double linear,float strength) {
            if(!std::isfinite(seconds)||seconds<=0||seconds>.25
               ||!std::isfinite(angular)||!std::isfinite(linear)||!std::isfinite(strength)) {
                motion=0;return 0;
            }
            double activity=std::max(smooth(4,100,std::max(0.,angular)),
                                     smooth(.08,6,std::max(0.,linear)));
            double response=-std::expm1(-seconds/(activity>motion?.08:.12));
            motion=float(std::clamp(motion+(activity-motion)*response,0.,1.));
            return std::min(.16f,std::max(0.f,strength))*motion;
        }
        float retention()const{return float(1-smooth(.02,.35,motion));}
    };
    // ShadowGrid.captureSun: the light lattice rotates around a nearby anchor,
    // and re-anchoring preserves its phase. Float basis and double compensation
    // match the Java implementation, avoiding subtraction of large float worlds.
    struct ShadowGrid {
        double anchorX=NAN,anchorY=NAN,anchorZ=NAN,sx=NAN,sy=NAN;
        double phaseX=0,phaseY=0;
        static double nearest(double value){return std::floor(value/16.+.5)*16.;}
        static double phase(double value,double step){return value-std::floor(value/step+.5)*step;}
        static double snap(double value,double previous,double step,double phase) {
            if(std::isfinite(previous)&&std::abs(value-previous)<=step*.70)return previous;
            return phase+std::floor((value-phase)/step+.5)*step;
        }
        std::array<float,16> capture(V3 camera,V3 sun) {
            constexpr double radius=40,texel=2*radius/1024.;
            double norm=std::sqrt(double(sun.x)*sun.x+double(sun.y)*sun.y+double(sun.z)*sun.z);
            float fx=float(sun.x/norm),fy=float(sun.y/norm),fz=float(sun.z/norm);
            double horizontal=std::hypot(fx,fz);
            float rx=horizontal>1e-5?float(fz/horizontal):1.f,ry=0;
            float rz=horizontal>1e-5?float(-fx/horizontal):0.f;
            float ux=fy*rz-fz*ry,uy=fz*rx-fx*rz,uz=fx*ry-fy*rx;
            if(!std::isfinite(anchorX)) {
                anchorX=nearest(camera.x);anchorY=nearest(camera.y);anchorZ=nearest(camera.z);
            }
            double dx=camera.x-anchorX,dy=camera.y-anchorY,dz=camera.z-anchorZ;
            double cx=rx*dx+ry*dy+rz*dz,cy=ux*dx+uy*dy+uz*dz,cz=fx*dx+fy*dy+fz*dz;
            sx=snap(cx,sx,texel,phaseX);sy=snap(cy,sy,texel,phaseY);
            if(std::max({std::abs(dx),std::abs(dy),std::abs(dz)})>radius) {
                double nx=nearest(camera.x),ny=nearest(camera.y),nz=nearest(camera.z);
                double ax=nx-anchorX,ay=ny-anchorY,az=nz-anchorZ;
                double shiftX=rx*ax+ry*ay+rz*az,shiftY=ux*ax+uy*ay+uz*az;
                sx-=shiftX;sy-=shiftY;
                phaseX=phase(phaseX-shiftX,texel);phaseY=phase(phaseY-shiftY,texel);
                cx-=shiftX;cy-=shiftY;cz-=fx*ax+fy*ay+fz*az;
                anchorX=nx;anchorY=ny;anchorZ=nz;
            }
            // ortho(-40,40,-40,40,240,.1,true) * capture.view, reverse Z.
            // Round view translation to float before the float matrix multiply,
            // just as JOML does; texture sampling uses this same uploaded matrix.
            constexpr float scale=1.f/40.f,depthScale=1.f/(240.f-.1f);
            float tx=float(cx-sx),ty=float(cy-sy),tz=float(cz-120.);
            return {rx*scale,ux*scale,fx*depthScale,0,
                    ry*scale,uy*scale,fy*depthScale,0,
                    rz*scale,uz*scale,fz*depthScale,0,
                    tx*scale,ty*scale,tz*depthScale+240.f*depthScale,1};
        }
    };
    struct FilmState {
        MotionEnvelope shutter{};
        bool valid=false,cameraKnown=false,firstPerson=false;
        int64_t lastNanos=0,lastHeldNanos=0,nextHeldNanos=0;
        V3 position{};
        float yaw=0,pitch=0,projectionX=0,projectionY=0;
    };
    struct State {
        ShadowGrid grid{};
        FilmState film{};
        int64_t weatherNanos=0;
        double cloudX=0,cloudZ=0,hazeX=0,hazeZ=0;
    };
    State committed{},pending{};
    NativeVisualFrame prepared{};
    bool pendingReady=false,environmentKnown=false;
    uint64_t environmentSeed=0;
    double environmentSeconds=0,daySeconds=0;
    NativeClimate climate{};
    V3 sun{0,1,0};
    NativeMoon moon{};
    int64_t startNanos=clockNanos();

    static bool projectionChanged(float current,float previous) {
        return std::abs(current-previous)>std::max(.01f,std::abs(previous)*.08f);
    }
    static bool cameraCut(const FilmState& f,const Camera& camera,const Params& p,bool firstPerson) {
        if(!f.cameraKnown)return false;
        V3 movement=camera.position-f.position;
        return firstPerson!=f.firstPerson||dot(movement,movement)>16
            ||std::abs(std::remainder(double(camera.yaw-f.yaw),2*pi))*180/pi>55
            ||std::abs(double(camera.pitch-f.pitch))*180/pi>55
            ||projectionChanged(1/(p.origin[3]*p.forward[3]),f.projectionX)
            ||projectionChanged(-1/p.origin[3],f.projectionY);
    }
    static void temporal(NativeVisualFrame& out,FilmState& f,const Camera& camera,
                         const Params& p,bool active,bool firstPerson,int64_t now) {
        if(!active){f={};return;}
        int64_t elapsed=f.lastNanos==0?0:now-f.lastNanos;
        if(elapsed<0||elapsed>250000000||cameraCut(f,camera,p,firstPerson)) {
            f={};elapsed=0;
        }
        double seconds=double(elapsed)*1e-9,angular=0,linear=0;
        if(f.valid&&seconds>0&&f.cameraKnown) {
            angular=std::hypot(std::remainder(double(camera.yaw-f.yaw),2*pi)*180/pi,
                               double(camera.pitch-f.pitch)*180/pi)/seconds;
            V3 delta=camera.position-f.position;
            linear=std::sqrt(double(dot(delta,delta)))/seconds;
        }
        float shutter=f.shutter.update(seconds,angular,linear,.18f),retention=f.shutter.retention();
        out.scene[97]=.12f*retention;out.scene[98]=shutter;out.scene[99]=.12f*retention;
        out.allowCapture=true;out.captureHeld=!f.valid||now>=f.nextHeldNanos;out.historyValid=f.valid;
        out.scene[100]=f.valid?1.f:0.f;out.scene[101]=float(seconds);
        out.scene[102]=f.valid?float(double(now-f.lastHeldNanos)*1e-9):0.f;
        out.scene[103]=float((now/20000000)&1);
        f.lastNanos=now;f.cameraKnown=true;f.firstPerson=firstPerson;f.position=camera.position;
        f.yaw=camera.yaw;f.pitch=camera.pitch;
        f.projectionX=1/(p.origin[3]*p.forward[3]);f.projectionY=-1/p.origin[3];
    }
public:
    static constexpr uint32_t shadowResolution=1024;
    static constexpr float shadowRadius=40,shadowClipRange=239.9f;

    void updateEnvironment(double seconds,uint64_t seed=0,int weather=-1,double daylightSeconds=-1) {
        if(!std::isfinite(seconds)||!std::isfinite(daylightSeconds))
            throw std::invalid_argument("Non-finite native environment time");
        seconds=std::max(0.,seconds);
        if(!environmentKnown||environmentSeed!=seed||seconds<environmentSeconds) {
            committed={};pendingReady=false;
        }
        environmentKnown=true;environmentSeed=seed;environmentSeconds=seconds;
        daySeconds=daylightSeconds<0?seconds:std::max(0.,daylightSeconds);
        climate=nativeClimate(seed,seconds,weather,daySeconds);
        sun=normalized(northernSun(daySeconds));moon=northernMoon(daySeconds);
    }

    NativeVisualFrame prepare(const Camera& camera,const Params& p,float terrainDistance,
                              const std::array<float,7>& video,bool temporalActive,bool firstPerson) {
        if(!(p.origin[3]>0)||!(p.forward[3]>0)||!std::isfinite(p.origin[3])
           ||!std::isfinite(p.forward[3])||!std::isfinite(terrainDistance))
            throw std::invalid_argument("Invalid native visual projection");
        if(!environmentKnown)updateEnvironment(0);
        int64_t now=clockNanos();
        pending=committed;pendingReady=false;
        NativeVisualFrame out{};
        auto vec=[&](size_t offset,float x,float y,float z,float w) {
            out.scene[offset]=x;out.scene[offset+1]=y;out.scene[offset+2]=z;out.scene[offset+3]=w;
        };
        V3 f{p.forward[0],p.forward[1],p.forward[2]};
        V3 r{p.right[0],p.right[1],p.right[2]},u{p.up[0],p.up[1],p.up[2]};
        float vertical=p.origin[3],horizontal=vertical*p.forward[3];
        // Analytic inverse of the actual raster projection, in camera-relative
        // world coordinates. Infinite far point has w=0 by design.
        std::array<float,16> inverse{
            r.x*horizontal,r.y*horizontal,r.z*horizontal,0,
            -u.x*vertical,-u.y*vertical,-u.z*vertical,0,
            0,0,0,20,
            f.x,f.y,f.z,0};
        std::copy(inverse.begin(),inverse.end(),out.scene.begin());
        std::array<float,16> projection{
            r.x/horizontal,-u.x/vertical,0,f.x,
            r.y/horizontal,-u.y/vertical,0,f.y,
            r.z/horizontal,-u.z/vertical,0,f.z,
            0,0,.05f,0};
        std::copy(projection.begin(),projection.end(),out.projection.begin());
        out.projection[16]=1; // Vulkan Z is [0,1].
        auto light=pending.grid.capture(camera.position,sun);
        std::copy(light.begin(),light.end(),out.scene.begin()+16);

        // SceneFrame uses WeatherValues' normalized wind strength (m/s /18).
        // Integrate velocity; changing weather never rescales accumulated drift.
        double dt=pending.weatherNanos==0||!temporalActive?0:
            std::clamp(double(now-pending.weatherNanos)*1e-9,0.,.25);
        pending.weatherNanos=now;
        float windStrength=climate.wind/18.f,windX=climate.windX(),windZ=climate.windZ();
        double vx=windX*windStrength,vz=windZ*windStrength;
        pending.cloudX+=vx*18.*dt;pending.cloudZ+=vz*18.*dt;
        pending.hazeX=std::fmod(pending.hazeX+vx*(.28/.12)*dt,4096.);
        pending.hazeZ=std::fmod(pending.hazeZ+vz*(.28/.12)*dt,4096.);

        float elevation=float(std::asin(std::clamp(double(sun.y),-1.,1.))*180/pi);
        float intensity=float(smooth(-.833,4,elevation)*smooth(.35,5,elevation))*climate.sun;
        bool shadows=video[4]>.5f&&intensity>.001f;
        float fog=std::max(0.f,video[3]),distance=std::max(32.f,terrainDistance);
        vec(32,sun.x,sun.y,sun.z,intensity);
        vec(36,.0009f*fog,.055f,.09f,60.f);
        vec(40,1024.f,80.f/1024.f,.018f/shadowClipRange,.50f);
        vec(44,climate.cover,climate.snowfall,windStrength,climate.fog);
        vec(48,windX,windZ,float(std::fmod(double(now-startNanos)*1e-9,8192.)),12.f);
        vec(52,shadows?1.f:0.f,7.f,0,0); // volume | weather sky | early background.
        vec(56,std::fmod(camera.position.x,4096.f),camera.position.y,
               std::fmod(camera.position.z,4096.f),distance);
        vec(60,0,0,shadowClipRange,.012f);
        // Current run/config/darkwinter/visuals.properties artistic profile:
        // saturation .76, coldness .28, exposure .92, vignette .14.
        vec(64,1.f,.76f,.28f,.92f);
        // Clean forest profile: preserve grading/vignette, remove animated grain and scanlines.
        vec(68,0.f,.12f,.14f,0.f);
        vec(72,.0050f*fog,72.f,42.f,12.f);
        vec(76,.42f,float(pending.hazeX),float(pending.hazeZ),.00028f*fog);
        float available=std::max(24.f,distance-16.f);
        vec(80,available*.55f,available*.88f,6.f,fog>0?1.f:0.f);
        vec(84,climate.cloudRoundness,climate.cloudScale,climate.cloudStretch,climate.skyBlue);
        vec(88,climate.fogBlue,moon.direction.x,moon.direction.y,moon.direction.z);
        float sunHalf=float(100.*std::tan(.53*pi/360.))*2.6f;
        float moonHalf=float(100.*std::tan(.52*pi/360.))*3.2f;
        vec(92,float(pending.cloudX),float(pending.cloudZ),
               std::atan(std::sqrt(2.f)*sunHalf/100.f),std::atan(std::sqrt(2.f)*moonHalf/100.f));
        // Unreprojected alternating/held exposures make fine needles crawl in motion.
        // Keep weather integration above live, but use only this frame for the film.
        vec(96,0,0,0,0);
        temporal(out,pending.film,camera,p,false,firstPerson,now);
        out.extras[0]=float(moon.phase);
        out.extras[1]=float(smooth(-.833,-.26,elevation));
        out.extras[2]=float(smooth(-.60,.20,moon.elevationDegrees));
        out.extras[3]=float(std::pow(std::max(0.,1-smooth(-14.5,-8,elevation)),1.35))*.82f*.48f;
        out.extras[4]=1.12f;out.extras[5]=1;
        out.extras[6]=starAngle(daySeconds);
        out.extras[8]=video[6]>.5f?1.f:0.f;
        prepared=out;pendingReady=true;
        return out;
    }

    // Only after successful GPU submission: history describes a real raw frame.
    // Renderer copies held/current raw images according to prepared's flags.
    void commit() {
        if(!pendingReady)return;
        if(prepared.allowCapture) {
            auto& film=pending.film;
            if(prepared.captureHeld) {
                film.lastHeldNanos=film.lastNanos;
                if(!film.valid||film.nextHeldNanos==0)film.nextHeldNanos=film.lastNanos+heldPeriodNanos;
                else film.nextHeldNanos+=((film.lastNanos-film.nextHeldNanos)/heldPeriodNanos+1)*heldPeriodNanos;
            }
            film.valid=true;
        }
        committed=pending;pendingReady=false;
    }
    // Resize/world/camera ownership changes invalidate temporal images.
    // Ordinary resize does not reset the continuously integrated weather drift.
    void invalidate() {
        committed.film={};pendingReady=false;
    }
};
}
