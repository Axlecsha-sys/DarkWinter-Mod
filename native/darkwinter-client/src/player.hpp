#pragma once
#include "scene.hpp"
#include <functional>

namespace dw::client {
struct Box {
    V3 lo,hi;bool noStep=false;
    Box shifted(V3 p)const{return{lo+p,hi+p,noStep};}
};
bool overlaps(const Box& a,const Box& b);
class CollisionWorld {
    const Scene& scene;
    struct Shape {uint32_t material;std::vector<Box> boxes;bool snowCap=false;};
    std::vector<Shape> shapes;
    std::vector<uint32_t> defaults;
    std::map<std::tuple<int,int,int>,std::pair<uint32_t,uint32_t>> overrides;
    int radius=0;
    mutable std::vector<Box> scratch;
public:
    mutable uint64_t queries=0,visited=0;
    CollisionWorld(const Scene& s,const std::filesystem::path& file);
    void query(const Box& region,std::vector<Box>& out)const;
    bool clear(const Box& box)const;
    float cameraDistance(V3 start,V3 delta,float radius)const;
    V3 spawn(V3 near)const;
};
struct PlayerInput {float forward=0,strafe=0;bool sprint=false,jump=false,descend=false;};
class Player {
    struct Axis {
        float value=0,lock=0,released=0;int sign=0;
        float filter(float target,float dt);
    } lateral,forward;
    std::vector<Box> candidates;
    double accumulator=0;
    float recovery=0,jumpCooldown=0,landing=0;
    bool climbing=false;float climbStart=0,climbTarget=0,climbTime=0;V3 climbDirection{},climbLanding{};
    bool jumpHeld=false,flying=false;int gameMode=0,flightSlice=0;float flightTrigger=0;
    V3 previous{};
    mutable bool cameraReady=false,cameraAuto=false,cameraWasFirst=false;mutable double cameraClock=0;mutable V3 cameraEye{},cameraLag{};mutable float cameraYaw=0,cameraTurn=0,cameraSide=.8f,cameraFraction=1,cameraClear=0;
    void tick(const CollisionWorld& world,PlayerInput input,float dt);
    void tickFlying(const CollisionWorld& world,PlayerInput input,float dt);
    void updateStamina(bool moving,float dt);
public:
    static constexpr float width=.6f,height=1.8f,eyeHeight=1.62f,fixed=1.f/120;
    V3 feet{},velocity{};
    float yaw=0,pitch=-.12f,bodyYaw=0,stamina=150,phase=0,gait=0;
    bool grounded=false,exhausted=false,running=false;
    uint64_t ticks=0,steps=0,jumps=0,landings=0,autoSteps=0;
    double distance=0;double lastCpuMs=0;
    Player(){candidates.reserve(1024);}
    static Box bounds(V3 p){return{p+V3{-width/2,0,-width/2},p+V3{width/2,height,width/2}};}
    void reset(V3 p,float lookYaw=0,float lookPitch=-.12f);
    void suspend(){lateral={};forward={};jumpHeld=false;flightTrigger=0;flightSlice=0;accumulator=0;previous=feet;climbing=false;}
    void setGameMode(int mode,bool savedFlying=false);
    void changeGameMode(const CollisionWorld& world,int mode);
    int mode()const{return gameMode;}
    bool isFlying()const{return flying;}
    bool mayFly()const{return gameMode==1||gameMode==3;}
    bool mayBuild()const{return gameMode<2;}
    bool noClip()const{return gameMode==3;}
    bool isClimbing()const{return climbing;}
    void advance(const CollisionWorld& world,PlayerInput input,double elapsed);
    V3 visualFeet()const;
    V3 eye()const{return visualFeet()+V3{0,eyeHeight,0};}
    Camera view(const CollisionWorld& world,bool third,bool& bodyVisible,float shoulder=1)const;
    bool canPlace(const CollisionWorld& world,int x,int y,int z,uint32_t material)const;
    void write(std::ostream& out)const;
    void read(std::istream& in,const CollisionWorld& world,int mode=0,bool savedFlying=false);
};
}
