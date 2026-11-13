#include "player.hpp"
#include <cstring>
#include <iomanip>
namespace dw::client {
namespace {
void check(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
float component(V3 p,int axis){return axis==0?p.x:axis==1?p.y:p.z;}
void component(V3& p,int axis,float value){if(axis==0)p.x=value;else if(axis==1)p.y=value;else p.z=value;}
float approach(float v,float target,float amount){return v+std::clamp(target-v,-amount,amount);}
float wrap(float angle){return std::remainder(angle,6.283185307f);}
float clip(const Box& b,const Box& obstacle,float amount,int axis){
    for(int i=0;i<3;i++)if(i!=axis&&(component(b.hi,i)<=component(obstacle.lo,i)+.00001f||component(b.lo,i)>=component(obstacle.hi,i)-.00001f))return amount;
    if(amount>0&&component(b.hi,axis)<=component(obstacle.lo,axis)+.00001f)
        return std::min(amount,std::max(0.f,component(obstacle.lo,axis)-component(b.hi,axis)));
    if(amount<0&&component(b.lo,axis)>=component(obstacle.hi,axis)-.00001f)
        return std::max(amount,std::min(0.f,component(obstacle.hi,axis)-component(b.lo,axis)));
    return amount;
}
V3 move(V3 position,V3 delta,const std::vector<Box>& boxes){
    // Vertical support first; horizontal movement slides along contacts.
    for(int axis:{1,0,2}){
        float amount=component(delta,axis);Box b=Player::bounds(position);
        for(const Box& obstacle:boxes)amount=clip(b,obstacle,amount,axis);
        component(position,axis,component(position,axis)+amount);
    }
    return position;
}
}
bool overlaps(const Box& a,const Box& b){
    return a.lo.x<b.hi.x-.00001f&&a.hi.x>b.lo.x+.00001f&&a.lo.y<b.hi.y-.00001f&&a.hi.y>b.lo.y+.00001f&&a.lo.z<b.hi.z-.00001f&&a.hi.z>b.lo.z+.00001f;
}
CollisionWorld::CollisionWorld(const Scene& s,const std::filesystem::path& file):scene(s){
    auto bytes=read_file(file,64*1024*1024);size_t at=0;
    auto take=[&](size_t n){check(n<=bytes.size()-at,"Collision bounds");size_t p=at;at+=n;return p;};
    auto u32=[&](){uint32_t v;size_t p=take(4);std::memcpy(&v,bytes.data()+p,4);return v;};
    auto u64=[&](){uint64_t v;size_t p=take(8);std::memcpy(&v,bytes.data()+p,8);return v;};
    check(bytes.size()>=48&&std::memcmp(bytes.data(),"DWCOLL\0\0",8)==0,"Collision header");at=8;
    check(u32()==2,"Collision version");uint32_t nm=u32(),ns=u32(),no=u32();
    check(nm==scene.materials&&ns>0&&ns<65536&&no<=1048576,"Collision counts");
    check(u64()==scene.paletteIdentity,"Collision palette identity");
    uint64_t size=u64(),hash=u64();check(size==bytes.size()-48&&checksum(bytes.data()+48,size)==hash,"Collision integrity");
    defaults.resize(nm);for(auto& d:defaults){d=u32();check(d<ns,"Collision default index");}
    for(uint32_t i=0;i<ns;i++){
        uint32_t material=u32(),count=u32();check(material<nm&&count<=4096,"Collision shape count");
        Shape shape{material,{},scene.materialName(material).starts_with("minecraft:snow[")};uint32_t flags=u32();check(flags<=1,"Collision terrain flags");bool blocked=!flags;
        for(uint32_t j=0;j<count;j++){
            float p[6];for(float& v:p){v=std::bit_cast<float>(u32());check(std::isfinite(v)&&v>=-4&&v<=5,"Collision finite bounds");}
            check(p[0]<p[3]&&p[1]<p[4]&&p[2]<p[5],"Collision ordered bounds");
            for(int k=0;k<3;k++)radius=std::max(radius,int(std::ceil(std::max(-p[k],p[k+3]-1)-.00001f)));
            shape.boxes.push_back({{p[0],p[1],p[2]},{p[3],p[4],p[5]},blocked});
        }shapes.push_back(std::move(shape));
    }
    for(uint32_t i=0;i<nm;i++)check(shapes[defaults[i]].material==i,"Collision default ownership");
    for(uint32_t i=0;i<no;i++){
        int x=std::bit_cast<int32_t>(u32()),y=std::bit_cast<int32_t>(u32()),z=std::bit_cast<int32_t>(u32());uint32_t material=u32(),shape=u32();
        check(material<nm&&shape<ns&&shapes[shape].material==material,"Collision override ownership");
        check(overrides.emplace(std::make_tuple(x,y,z),std::make_pair(material,shape)).second,"Duplicate collision override");
    }
    check(at==bytes.size(),"Collision trailing data");
}
void CollisionWorld::query(const Box& region,std::vector<Box>& out)const{
    ++queries;out.clear();int x0=int(std::floor(region.lo.x))-radius,x1=int(std::floor(region.hi.x))+radius;
    int y0=int(std::floor(region.lo.y))-radius,y1=int(std::floor(region.hi.y))+radius;
    int z0=int(std::floor(region.lo.z))-radius,z1=int(std::floor(region.hi.z))+radius;
    check(x1-x0<=64&&y1-y0<=256&&z1-z0<=64,"Collision query budget");
    int top=(std::bit_cast<int32_t>(scene.world[1])+int(scene.world[5]))*16;
    for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++){
        ++visited;uint32_t material=scene.cell(x,y,z);
        if(material==UINT32_MAX){
            // Finite imported region has solid borders; sky remains open.
            if(y<top){Box b{{float(x),float(y),float(z)},{float(x+1),float(y+1),float(z+1)},true};if(overlaps(region,b))out.push_back(b);}continue;
        }
        uint32_t shape=defaults[material];auto it=overrides.find({x,y,z});
        if(it!=overrides.end()&&it->second.first==material)shape=it->second.second;
        bool capDenied=false;
        if(shapes[shape].snowCap){
            uint32_t below=scene.cell(x,y-1,z);
            if(below==UINT32_MAX)capDenied=true;
            else{const auto& support=shapes[defaults[below]];capDenied=support.snowCap||support.boxes.empty()||support.boxes.front().noStep;}
        }
        for(const Box& local:shapes[shape].boxes){Box b=local.shifted({float(x),float(y),float(z)});b.noStep=b.noStep||capDenied;if(overlaps(region,b))out.push_back(b);}
    }
}
bool CollisionWorld::clear(const Box& b)const{query(b,scratch);return scratch.empty();}
float CollisionWorld::cameraDistance(V3 start,V3 delta,float padding)const{
    Box swept{{std::min(start.x,start.x+delta.x)-padding,std::min(start.y,start.y+delta.y)-padding,std::min(start.z,start.z+delta.z)-padding},
              {std::max(start.x,start.x+delta.x)+padding,std::max(start.y,start.y+delta.y)+padding,std::max(start.z,start.z+delta.z)+padding}};
    query(swept,scratch);float fraction=1;
    for(const Box& b:scratch){float near=0,far=fraction;
        for(int k=0;k<3;k++){float o=component(start,k),d=component(delta,k),lo=component(b.lo,k)-padding,hi=component(b.hi,k)+padding;
            if(std::abs(d)<1e-8f){if(o<lo||o>hi){near=2;break;}}else{float a=(lo-o)/d,c=(hi-o)/d;near=std::max(near,std::min(a,c));far=std::min(far,std::max(a,c));}
        }
        if(near<=far&&far>=0)fraction=std::min(fraction,std::max(0.f,near-.01f));
    }return fraction;
}
V3 CollisionWorld::spawn(V3 near)const{
    int minY=std::bit_cast<int32_t>(scene.world[1])*16,top=minY+int(scene.world[5])*16;
    for(int ring=0;ring<=12;ring++)for(int dz=-ring;dz<=ring;dz++)for(int dx=-ring;dx<=ring;dx++){
        if(ring&&std::abs(dx)!=ring&&std::abs(dz)!=ring)continue;
        float x=std::floor(near.x)+dx+.5f,z=std::floor(near.z)+dz+.5f;
        // Prefer the actual ground near the source camera, not tree canopies.
        float high=std::min(float(top)-Player::height,near.y+3);
        Box column{{x-.3f,float(minY),z-.3f},{x+.3f,high+Player::height,z+.3f}};
        std::vector<Box> boxes;query(column,boxes);float best=-1e9f;
        for(const Box& b:boxes){float y=b.hi.y;if(y<=high&&y>best&&!b.noStep&&clear(Player::bounds({x,y+.0001f,z})))best=y;}
        if(best>-1e8f)return{x,best+.0001f,z};
    }
    throw std::runtime_error("No safe player spawn in imported biome");
}
float Player::Axis::filter(float target,float dt){
    int next=target>.0001f?1:target<-.0001f?-1:0;
    if(!next){value=approach(value,0,18.f*dt);released+=dt;lock=std::max(0.f,lock-dt);if(released>=.18f)sign=0;return value;}
    if(sign&&sign!=next){value=0;lock=std::max(0.f,.10f-released);}
    sign=next;released=0;
    if(lock>0){lock=std::max(0.f,lock-dt);return 0;}
    value=approach(value,target,25.3f*dt);return value;
}
void Player::reset(V3 p,float lookYaw,float lookPitch){
    feet=previous=p;velocity={};cameraReady=cameraAuto=cameraWasFirst=false;cameraLag={};cameraTurn=cameraClear=0;cameraFraction=1;yaw=bodyYaw=lookYaw;pitch=lookPitch;stamina=150;phase=gait=0;
    grounded=exhausted=running=jumpHeld=flying=false;gameMode=flightSlice=0;flightTrigger=0;recovery=jumpCooldown=landing=0;climbing=false;accumulator=0;lateral={};forward={};
}
void Player::setGameMode(int mode,bool savedFlying){
    check(mode>=0&&mode<=3,"Invalid player game mode");
    const bool nextFlying=mode==3||(mode==1&&(flying||savedFlying));
    if(mode!=gameMode||nextFlying!=flying){lateral={};forward={};climbing=false;flightSlice=0;flightTrigger=0;cameraReady=false;}
    gameMode=mode;flying=nextFlying;
    if(flying){grounded=false;gait=landing=0;}
}
void Player::changeGameMode(const CollisionWorld& world,int mode){
    check(mode>=0&&mode<=3,"Invalid player game mode");
    if(mode==gameMode)return;
    // ServerPlayerGameMode.changeGameModeForPlayer: leaving Spectator close to
    // unobstructed ground disables flight; an embedded player keeps flight.
    const bool stopFlight=mode==1&&flying&&world.clear(bounds(feet))
        &&!world.clear(bounds(feet+V3{0,-1,0}));
    setGameMode(mode);
    if(stopFlight)flying=false;
}
void Player::updateStamina(bool moving,float dt){
    if(running&&moving){stamina=std::max(0.f,stamina-8*dt);recovery=std::max(recovery,1.f);if(stamina<=0){exhausted=true;running=false;}}
    else if(recovery<=0)stamina=std::min(150.f,stamina+(moving?12.f:18.f)*dt);
}
void Player::tickFlying(const CollisionWorld& world,PlayerInput input,float dt){
    // Minecraft 26.3 Abilities/LocalPlayer/Player/LivingEntity: 20 Hz flight impulses,
    // .05 ability speed, .91 horizontal drag and .6 vertical drag. Collision remains 120 Hz.
    running=input.sprint&&input.forward>0&&!exhausted&&stamina>0;
    if(flightSlice==0){
        for(int axis=0;axis<3;axis++)if(std::abs(component(velocity,axis))<.06f)component(velocity,axis,0);
        const float acceleration=.05f*20.f*.98f*(running?2.f:1.f);
        velocity.x+=(std::cos(yaw)*input.strafe+std::sin(yaw)*input.forward)*acceleration;
        velocity.z+=(-std::sin(yaw)*input.strafe+std::cos(yaw)*input.forward)*acceleration;
        velocity.y+=(float(input.jump)-float(input.descend))*.05f*3.f*20.f;
    }
    const V3 delta=velocity*dt;V3 next=feet+delta;
    if(!noClip()){
        const auto b=bounds(feet);
        Box swept{{std::min(b.lo.x,b.lo.x+delta.x)-.001f,std::min(b.lo.y,b.lo.y+delta.y)-.002f,std::min(b.lo.z,b.lo.z+delta.z)-.001f},
                  {std::max(b.hi.x,b.hi.x+delta.x)+.001f,std::max(b.hi.y,b.hi.y+delta.y)+.001f,std::max(b.hi.z,b.hi.z+delta.z)+.001f}};
        world.query(swept,candidates);next=move(feet,delta,candidates);
        grounded=velocity.y<0&&next.y-feet.y>delta.y+.00001f;
        for(int axis=0;axis<3;axis++)if(std::abs(component(next,axis)-component(feet,axis)-component(delta,axis))>.00001f)component(velocity,axis,0);
        if(grounded)flying=false;
    }else grounded=false;
    feet=next;const float travelled=std::hypot(feet.x-previous.x,feet.z-previous.z);distance+=travelled;
    updateStamina(travelled>.00001f,dt);gait=landing=0;climbing=false;
    bodyYaw+=wrap(yaw-bodyYaw)*(1-std::exp(-dt/.16f));
    if(++flightSlice==6){velocity.x*=.91f;velocity.z*=.91f;velocity.y*=.6f;flightSlice=0;}
}
void Player::tick(const CollisionWorld& world,PlayerInput input,float dt){
    ++ticks;previous=feet;jumpCooldown=std::max(0.f,jumpCooldown-dt);recovery=std::max(0.f,recovery-dt);
    landing*=std::exp(-dt/.12f);
    if(exhausted&&stamina>=150)exhausted=false;
    float length=std::hypot(input.strafe,input.forward);if(length>1){input.strafe/=length;input.forward/=length;}
    flightTrigger=std::max(0.f,flightTrigger-dt);
    if(mayFly()&&gameMode!=3&&input.jump&&!jumpHeld){
        if(flightTrigger>0){
            flying=!flying;flightTrigger=0;flightSlice=0;climbing=false;lateral={};forward={};
            if(flying&&grounded){velocity.y=8.4f;grounded=false;}
        }else flightTrigger=.35f;
    }
    if(flying){jumpHeld=input.jump;tickFlying(world,input,dt);return;}
    running=input.sprint&&input.forward>0&&!exhausted&&stamina>0;
    float speed=running?5.612f:3.54f,base=input.forward<0?3.54f*.6f:speed;
    float localX=lateral.filter(input.strafe*base*.8f,dt),localZ=forward.filter(input.forward*base,dt);
    velocity.x=std::cos(yaw)*localX+std::sin(yaw)*localZ;
    velocity.z=-std::sin(yaw)*localX+std::cos(yaw)*localZ;
    bool jumped=false;
    if(climbing&&(input.jump||input.forward<=0||dot(V3{velocity.x,0,velocity.z},climbDirection)<.5f*std::hypot(velocity.x,velocity.z)))climbing=false;
    if(input.jump&&!jumpHeld&&(grounded||climbing)&&!exhausted&&jumpCooldown<=0){
        float cost=running?18.f:10.f;
        if(stamina>=cost){stamina-=cost;exhausted=stamina<=0;velocity.y=8.4f;grounded=false;jumpCooldown=.9f;recovery=1.5f;++jumps;jumped=true;}
    }jumpHeld=input.jump;
    if(jumped)climbing=false;
    velocity.y=std::max(-55.f,velocity.y-28.f*dt);
    V3 delta=velocity*dt;
    Box b=bounds(feet);Box swept{{std::min(b.lo.x,b.lo.x+delta.x)-.001f,std::min(b.lo.y,b.lo.y+delta.y)-.002f,std::min(b.lo.z,b.lo.z+delta.z)-.001f},
                                {std::max(b.hi.x,b.hi.x+delta.x)+.001f,std::max(b.hi.y,b.hi.y+delta.y)+1.126f,std::max(b.hi.z,b.hi.z+delta.z)+.001f}};
    world.query(swept,candidates);
    if(climbing){
        Box landingBox=bounds(climbLanding+V3{0,-.002f,0});bool supported=false;
        for(const Box& obstacle:candidates)if(!obstacle.noStep&&std::abs(obstacle.hi.y-climbTarget)<.002f&&overlaps(landingBox,obstacle)){supported=true;break;}
        V3 lift=move(feet,{0,std::max(0.f,climbTarget-feet.y),0},candidates);
        if(!supported||lift.y<climbTarget-.0001f)climbing=false;
    }
    auto climbMove=[&](){
        climbTime=std::min(.25f,climbTime+dt);float f=climbTime/.25f;f=f*f*(3-2*f);
        float target=climbStart+(climbTarget-climbStart)*f;
        V3 plan{delta.x,std::max(0.f,target-feet.y),delta.z};V3 result=move(feet,plan,candidates);
        if(result.y-feet.y+1e-5f<plan.y)climbing=false;
        velocity.y=0;return result;
    };
    V3 next=climbing?climbMove():move(feet,delta,candidates);
    bool horizontal=std::abs(next.x-feet.x-delta.x)>.00001f||std::abs(next.z-feet.z-delta.z)>.00001f;
    if(!climbing&&grounded&&!jumped&&!input.jump&&horizontal&&input.forward>.001f){
        bool blocked=false;Box ahead=bounds(feet+V3{delta.x,0,delta.z});
        for(const Box& box:candidates)if(box.noStep&&overlaps(ahead,box))blocked=true;
        if(!blocked){
            V3 raised=move(feet,{0,1.125f,0},candidates);
            V3 across=move(raised,{delta.x,0,delta.z},candidates);
            V3 down=move(across,{0,-(raised.y-feet.y)-.002f,0},candidates);
            float gain=std::hypot(down.x-feet.x,down.z-feet.z),old=std::hypot(next.x-feet.x,next.z-feet.z);
            if(gain>old+.00001f&&down.y>feet.y+.0001f&&down.y<=feet.y+1.1251f){
                climbStart=feet.y;climbTarget=down.y;climbTime=0;climbLanding=down;
                climbDirection=normalized(V3{delta.x,0,delta.z});climbing=true;next=climbMove();++autoSteps;
            }
        }
    }
    bool wasGrounded=grounded;
    if(climbing&&climbTime>=.25f){
        climbing=false;
        Box support=bounds(next+V3{0,-.002f,0});
        for(const Box& box:candidates)if(overlaps(support,box)){grounded=true;break;}
    } else grounded=false;
    grounded=grounded||(!climbing&&next.y-feet.y>delta.y+.00001f&&velocity.y<0);
    if(grounded){if(!wasGrounded&&velocity.y<-2){landing=std::min(.12f,-velocity.y*.006f);++landings;}velocity.y=0;}
    else if(std::abs(next.y-feet.y-delta.y)>.00001f)velocity.y=0;
    feet=next;float travelled=std::hypot(feet.x-previous.x,feet.z-previous.z);distance+=travelled;
    bool moving=travelled>.00001f;
    updateStamina(moving,dt);
    float target=grounded?std::min(1.f,travelled/dt/3.54f):0;
    gait+=(target-gait)*(1-std::exp(-dt/.09f));
    if(grounded){float before=std::floor(phase/3.14159265f);phase+=travelled/(running?2.05f:1.65f)*3.14159265f;if(std::floor(phase/3.14159265f)>before)++steps;}
    if(phase>6283)phase-=6283.185307f;
    if(moving||std::abs(wrap(yaw-bodyYaw))>.7f)bodyYaw+=wrap(yaw-bodyYaw)*(1-std::exp(-dt/.16f));
}
void Player::advance(const CollisionWorld& world,PlayerInput input,double elapsed){
    check(std::isfinite(elapsed)&&elapsed>=0,"Player elapsed time");auto start=std::chrono::steady_clock::now();
    accumulator+=std::min(elapsed,.25);int count=0;
    while(accumulator+1e-9>=double(fixed)&&count<31){tick(world,input,fixed);accumulator-=fixed;++count;}
    lastCpuMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
}
V3 Player::visualFeet()const{
    float blend=std::clamp(float(accumulator/double(fixed)),0.f,1.f);
    return previous*(1-blend)+feet*blend-V3{0,landing,0};
}
Camera Player::view(const CollisionWorld& world,bool third,bool& bodyVisible,float shoulder)const{
 V3 at=eye();float bob=std::sin(phase*2)*.016f*gait;Camera c{at+V3{0,bob,0},yaw,pitch};bodyVisible=false;
 if(!third||noClip()){cameraReady=cameraAuto=false;cameraWasFirst=true;return c;}
 double clock=double(ticks)*fixed+accumulator;float dt=cameraReady?float(std::clamp(clock-cameraClock,0.,.1)):0;
 if(!cameraReady||dot(at-cameraEye,at-cameraEye)>9){cameraLag={};cameraTurn=0;cameraSide=.8f*shoulder;cameraFraction=cameraWasFirst?.35f:1;cameraClear=0;cameraAuto=false;cameraYaw=yaw;cameraEye=at;cameraReady=true;}
 auto lag=[&](float old,float delta,float seconds){if(dt<1e-8)return old;float a=-std::expm1(-dt/seconds);return old*(1-a)-delta*(seconds*a/dt);};
 V3 delta=at-cameraEye;float tau=running?.15f:.12f;cameraLag.x=lag(cameraLag.x,delta.x*.66f,tau);cameraLag.z=lag(cameraLag.z,delta.z*.66f,tau);float length=std::hypot(cameraLag.x,cameraLag.z),limit=running?.34f:.25f;if(length>limit){cameraLag.x*=limit/length;cameraLag.z*=limit/length;}
 cameraLag.y=std::clamp(lag(cameraLag.y,delta.y*(climbing?.3f:grounded?1.f:.25f),climbing?.045f:grounded?.16f:.03f),climbing?-.12f:grounded?-.70f:-.30f,.30f);
 float turn=wrap(yaw-cameraYaw);cameraTurn=std::abs(turn)>2.269f?0:std::clamp(lag(cameraTurn,turn,running?.16f:.12f),running?-.227f:-.14f,running?.227f:.14f);cameraYaw=yaw;cameraEye=at;cameraClock=clock;
 cameraSide+=(shoulder*.8f-cameraSide)*(1-std::exp(-9*dt));V3 f{std::sin(yaw)*std::cos(pitch),std::sin(pitch),std::cos(yaw)*std::cos(pitch)},r{std::cos(yaw),0,-std::sin(yaw)},up=cross(f,r);
 float step=phase/3.14159265359f,p=step-std::floor(step);auto smooth=[](float x){x=std::clamp(x,0.f,1.f);return x*x*(3-2*x);};float contact=p<.16f?smooth(p/.16f):1-smooth((p-.16f)/.84f);float amount=climbing?0:gait,sway=std::sin(phase)*(running?.040f:.025f)*amount;
 V3 arm=f*-3.f+r*(cameraSide+sway)+up*.3f;float cs=std::cos(cameraTurn),sn=std::sin(cameraTurn);arm={cs*arm.x+sn*arm.z,arm.y,-sn*arm.x+cs*arm.z};arm=arm+cameraLag+V3{0,-(running?.075f:.045f)*contact*amount,0};float target=world.cameraDistance(at,arm,.18f),available=3*target;
 if(cameraAuto){if(available>=1.25f)cameraClear+=dt;else cameraClear=0;if(cameraClear<.25f)return c;cameraAuto=false;cameraFraction=std::min(target,.35f);cameraClear=0;}
 if(available<.65f){cameraAuto=true;cameraClear=0;cameraFraction=target;return c;}
 cameraFraction+=(target-cameraFraction)*(1-std::exp(-(available<1.6f?9.f:3.5f)*dt));cameraFraction=std::min(cameraFraction,target);c.position=at+arm*cameraFraction;bodyVisible=true;cameraWasFirst=false;return c;
}
bool Player::canPlace(const CollisionWorld& world,int x,int y,int z,uint32_t material)const{
    (void)world;(void)material;
    return !overlaps(bounds(feet),{{float(x),float(y),float(z)},{float(x+1),float(y+1),float(z+1)}});
}
void Player::write(std::ostream& out)const{
    out<<std::setprecision(17)<<feet.x<<' '<<feet.y<<' '<<feet.z<<' '<<yaw<<' '<<pitch<<' '<<stamina<<' '<<exhausted<<' '<<recovery<<' '<<jumpCooldown<<' '<<velocity.x<<' '<<velocity.y<<' '<<velocity.z<<' '<<grounded<<'\n';
}
void Player::read(std::istream& in,const CollisionWorld& world,int mode,bool savedFlying){
    V3 p;float y,look,energy,delay,cool;V3 v;int tired,onGround;
    check(bool(in>>p.x>>p.y>>p.z>>y>>look>>energy>>tired>>delay>>cool>>v.x>>v.y>>v.z>>onGround),"Player save incomplete");
    check(std::isfinite(p.x)&&std::isfinite(p.y)&&std::isfinite(p.z)&&std::abs(p.x)<32768&&std::abs(p.y)<32768&&std::abs(p.z)<32768&&std::isfinite(y)&&std::abs(y)<1e6f&&std::isfinite(look)&&std::abs(look)<=1.551f&&std::isfinite(energy)&&energy>=0&&energy<=150&&(tired==0||tired==1)&&std::isfinite(delay)&&delay>=0&&delay<=2&&std::isfinite(cool)&&cool>=0&&cool<=1,"Player save invalid");
    const float horizontalLimit=(mode==1||mode==3)?32.f:20.f;
    check(std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z)&&std::abs(v.x)<horizontalLimit&&std::abs(v.z)<horizontalLimit&&std::abs(v.y)<=55&&(onGround==0||onGround==1),"Player velocity invalid");
    check(mode==3||world.clear(bounds(p)),"Saved player intersects world");reset(p,y,look);stamina=energy;exhausted=tired;recovery=delay;jumpCooldown=cool;velocity=v;grounded=onGround;setGameMode(mode,savedFlying);
}
}
