#include "../src/player.hpp"
#include <iostream>
#include <sstream>
#include <iomanip>
using namespace dw::client;
static uint32_t checks=0;
static void require(bool condition,const char* reason){++checks;if(!condition)throw std::runtime_error(reason);}
static void simulate(Player& p,const CollisionWorld& w,PlayerInput in,double seconds,int fps=60){
    for(int i=0;i<int(std::round(seconds*fps));i++)p.advance(w,in,1./fps);
}
int main(int argc,char** argv){
 try{
    if(argc!=3)throw std::runtime_error("Usage: player-checks dist report");
    std::filesystem::path dist=argv[1],out=argv[2];std::filesystem::create_directories(out);
    Scene scene(dist/"world.dwv",dist/"assets.dwa");CollisionWorld world(scene,dist/"collision.dwc");
    uint32_t solid=UINT32_MAX,snow=UINT32_MAX;
    for(uint32_t i=0;i<scene.materials;i++){
        auto name=scene.materialName(i);
        if(name=="minecraft:stone")solid=i;
        if(name.find("test_snowy_grass_slab[")!=std::string::npos&&name.find("type=bottom")!=std::string::npos)snow=i;
    }require(solid!=UINT32_MAX,"Solid test material");
    for(int z=-20;z<=20;z++)for(int x=-20;x<=20;x++){
        require(scene.cell(x,144,z)!=UINT32_MAX,"Owned test section");
        scene.edit(x,144,z,solid);for(int y=145;y<=150;y++)scene.edit(x,y,z,0);
    }
    auto start=[](float x=.5f,float z=.5f){Player p;p.reset({x,145.001f,z},0,0);return p;};
    Player a=start();simulate(a,world,{},1);require(a.grounded&&std::abs(a.feet.y-145)<.001f,"Gravity and stable floor");
    require(world.clear(Player::bounds(a.feet)),"Resting body has no penetration");
    auto travel=[&](PlayerInput in,int fps){Player p=start();simulate(p,world,{},.1,fps);simulate(p,world,in,2,fps);return p;};
    Player walk=travel({1,0,false,false},60),back=travel({-1,0,false,false},60),side=travel({0,1,false,false},60);
    require(walk.distance>6.7&&walk.distance<7.2,"Walking speed and acceleration");
    require(back.distance<walk.distance*.64&&back.distance>walk.distance*.56,"Backwards slower");
    require(side.distance<walk.distance*.83&&side.distance>walk.distance*.76,"Strafe slower");
    auto walk30=travel({1,0,false,false},30),walk144=travel({1,0,false,false},144);
    require(std::abs(walk30.feet.z-walk144.feet.z)<.001f&&walk30.ticks==walk144.ticks,"Movement independent of display rate");
    Player diagonal=travel({1,1,false,false},60);
    require(diagonal.distance<=walk.distance+.01,"No diagonal speed boost");
    float stopped=walk.feet.z;simulate(walk,world,{},.2);require(walk.feet.z>stopped&&walk.feet.z-stopped<.4f,"Short bounded braking on key release");float settled=walk.feet.z;simulate(walk,world,{},.2);require(std::abs(walk.feet.z-settled)<.001f,"Braking settles without continued drift");
    float prior=side.feet.x;simulate(side,world,{0,-1,false,false},.05);
    require(std::abs(side.feet.x-prior)<.001,"Direction reversal replants foot");
    Player jump=start();simulate(jump,world,{},.2);double high=jump.feet.y;
    for(int i=0;i<120;i++){jump.advance(world,{0,0,false,true},1./120);high=std::max(high,double(jump.feet.y));}
    require(jump.jumps==1&&high>146&&high<146.4&&jump.grounded,"Jump arc, landing, no repeated held jumps");
    require(jump.stamina<141&&jump.stamina>139,"Jump stamina cost");
    simulate(jump,world,{},.1);simulate(jump,world,{0,0,false,true},.05);require(jump.jumps==2,"Jump after release and cooldown");
    Player ceiling=start();scene.edit(0,147,0,solid);simulate(ceiling,world,{},.2);simulate(ceiling,world,{0,0,false,true},.4);
    require(ceiling.feet.y<=145.2001f&&world.clear(Player::bounds(ceiling.feet)),"Head collision");scene.edit(0,147,0,0);
    scene.edit(0,145,2,solid);Player step=start();simulate(step,world,{},.2);simulate(step,world,{1,0,false,false},.8);
    require(step.autoSteps>0&&step.feet.y>=145.99&&step.feet.z>2,"Step onto full block without jump");
    require(step.jumps==0&&step.stamina>=149,"Step consumes no jump");
    scene.edit(0,147,2,solid);Player low=start();simulate(low,world,{},.2);simulate(low,world,{1,0,false,false},.8);
    require(low.feet.z<1.701&&low.autoSteps==0,"Headroom prevents step");
    scene.edit(0,147,2,0);
    for(int y=146;y<=149;y++)scene.edit(0,y,2,solid);
    Player wall=start();simulate(wall,world,{},.1);simulate(wall,world,{1,0,false,false},1);
    require(wall.feet.z<=1.701&&wall.autoSteps==0,"Wall is not a stair");
    auto wallSteps=wall.steps;double wallDistance=wall.distance;simulate(wall,world,{1,0,true,false},2);
    require(wall.steps==wallSteps&&std::abs(wall.distance-wallDistance)<.001,"Walking animation uses travelled distance");
    require(wall.stamina>=149,"No sprint drain against wall");
    float fraction=world.cameraDistance({.5f,146,0},{0,0,4},.16f);require(fraction<.5f,"Camera collision with wall");
    bool visible=true;wall.yaw=3.14159265f;wall.view(world,true,visible);require(!visible,"Camera falls back to first person in tight space");
    for(int y=145;y<=149;y++)scene.edit(0,y,2,0);
    Player sprint=start();simulate(sprint,world,{},.1);
    for(int i=0;i<1200;i++){sprint.yaw=((i/60)&1)?3.14159265f:0;sprint.advance(world,{1,0,true,false},1./60);}
    require(sprint.exhausted&&sprint.stamina<10,"Sprint exhaustion after about 19 seconds");
    uint64_t jumps=sprint.jumps;simulate(sprint,world,{0,0,false,true},.1);require(sprint.jumps==jumps,"Exhaustion blocks jumping");
    simulate(sprint,world,{},5);require(sprint.exhausted&&sprint.stamina<150,"Exhaustion persists until full refill");
    simulate(sprint,world,{},6);require(!sprint.exhausted&&sprint.stamina==150,"Full refill releases exhaustion");
    Player negative=start(-10.5f,-10.5f);simulate(negative,world,{1,0,false,false},1);require(world.clear(Player::bounds(negative.feet))&&negative.feet.z>-8,"Negative section coordinates");
    if(snow!=UINT32_MAX){
        scene.edit(4,145,4,snow);std::vector<Box> boxes;world.query({{4,145,4},{5,146,5}},boxes);float top=145;for(auto& box:boxes)top=std::max(top,box.hi.y);
        Player thin;thin.reset({4.5f,147,4.5f});simulate(thin,world,{},1);require(std::abs(thin.feet.y-top)<.001f,"Actual layered snow collision height");
        scene.edit(4,145,4,0);
    }
    Player persistent=start();simulate(persistent,world,{1,0,true,false},1);std::stringstream saved;persistent.write(saved);Player restored;restored.read(saved,world);
    require(std::abs(restored.feet.z-persistent.feet.z)<.00001&&restored.stamina==persistent.stamina,"Player position and stamina round trip");
    bool invalid=false;try{std::stringstream bad("0 145 0 0 0 151 0 0 0");restored.read(bad,world);}catch(...){invalid=true;}require(invalid,"Invalid player state rejected");
    require(!persistent.canPlace(world,int(persistent.feet.x),int(persistent.feet.y),int(persistent.feet.z),solid),"Cannot place a solid block through body");
    Player bounded=start();bounded.advance(world,{1,0,true,false},20);require(bounded.ticks<=31&&bounded.distance<1.5,"Long display stall cannot cause teleport or unbounded catch-up");
    for(uint32_t i=0;i<scene.materials;i++)if(scene.materialName(i).find("test_spruce_log[")!=std::string::npos){
        scene.edit(0,145,2,i);Player tree=start();simulate(tree,world,{},.2);simulate(tree,world,{1,0,false,false},1);
        require(tree.autoSteps==0&&tree.feet.z<2,"Trunk is not an eligible natural step");scene.edit(0,145,2,0);break;
    }
    scene.edit(0,145,2,solid);Player interrupted=start();simulate(interrupted,world,{},.1);
    for(int i=0;i<100&&!interrupted.isClimbing();i++)interrupted.advance(world,{1,0,false,false},1./120);
    require(interrupted.isClimbing(),"Smooth climb starts");float partial=interrupted.feet.y;
    simulate(interrupted,world,{},.05);require(!interrupted.isClimbing()&&interrupted.feet.y<=partial+.001,"Releasing forward cancels climb");
    scene.edit(0,145,2,0);
    auto spawn=world.spawn({16.5f,90,-12.5f});require(world.clear(Player::bounds(spawn)),"Actual biome safe spawn");
    std::ofstream report(out/"checks.json");report<<"{\"completed\":true,\"checks\":"<<checks<<",\"walkDistance\":"<<walk30.distance<<",\"tickRate\":120,\"displayRates\":[30,60,144],\"jumpApex\":"<<high<<",\"spawn\":["<<spawn.x<<","<<spawn.y<<","<<spawn.z<<"]}\n";
    std::cout<<"CORE_PLAYER_CHECKS_PASS checks="<<checks<<"\n";
    return 0;
 }catch(const std::exception& e){std::cerr<<"PLAYER_CHECK_FAILED checks="<<checks<<" "<<e.what()<<"\n";return 1;}
}
