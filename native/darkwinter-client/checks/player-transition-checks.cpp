// Behavioral regressions for discontinuities: edits, stalls, saves and input interruption.
#include "../src/player.hpp"
#include <iostream>
#include <sstream>
#include <iomanip>
using namespace dw::client;
static unsigned checks=0;
static void require(bool b,const char* what){++checks;if(!b)throw std::runtime_error(what);}
static void run(Player& p,const CollisionWorld& w,PlayerInput in,double seconds,int fps=120){
 for(int i=0;i<int(std::round(seconds*fps));++i)p.advance(w,in,1./fps);
}
int main(int argc,char** argv){
 try{
  if(argc!=3)throw std::runtime_error("dist report required");
  std::filesystem::path dist=argv[1],out=argv[2];std::filesystem::create_directories(out);
  Scene scene(dist/"world.dwv",dist/"assets.dwa");CollisionWorld world(scene,dist/"collision.dwc");
  uint32_t stone=UINT32_MAX,slab=UINT32_MAX,log=UINT32_MAX,snow=UINT32_MAX;
  for(uint32_t i=0;i<scene.materials;++i){auto n=scene.materialName(i);
   if(n=="minecraft:stone")stone=i;
   if(n.find("test_snowy_grass_slab[")!=std::string::npos&&n.find("type=bottom")!=std::string::npos)slab=i;
   if(n.find("test_spruce_log[")!=std::string::npos&&n.find("axis=y")!=std::string::npos)log=i;
   if(n=="minecraft:snow[layers=2]")snow=i;
  }
  require(stone!=UINT32_MAX,"Stone fixture available");
  for(int x=-20;x<=20;++x)for(int z=-20;z<=20;++z){scene.edit(x,144,z,stone);for(int y=145;y<=150;++y)scene.edit(x,y,z,0);}
  auto start=[&](float x=.5f,float z=.5f){Player p;p.reset({x,145.001f,z},0,0);run(p,world,{},.1);return p;};
  Player walk=start();run(walk,world,{1,0,false,false},3);
  require(walk.steps>=5&&walk.phase>3.14f,"Grounded route produces alternating measured steps");
  require(world.clear(Player::bounds(walk.feet)),"Grounded route has no body penetration");
  Player jump=start();run(jump,world,{0,0,false,true},.125);
  require(jump.velocity.y>0&&!jump.grounded,"Save fixture is ascending");
  std::stringstream encoded;jump.write(encoded);Player resumed;resumed.read(encoded,world);
  require(dot(resumed.feet-jump.feet,resumed.feet-jump.feet)<1e-10f&&resumed.velocity.y==jump.velocity.y,"Midair save preserves position and vertical velocity");
  run(jump,world,{},.5);run(resumed,world,{},.5);
  require(std::abs(jump.feet.y-resumed.feet.y)<.0001f,"Reload follows the same jump trajectory");
  run(resumed,world,{0,0,false,true},1);
  require(resumed.grounded,"Reloaded jump lands on collision surface");
  scene.edit(0,145,2,stone);
  auto climb=[&](){Player p=start();for(int i=0;i<120&&!p.isClimbing();++i)p.advance(world,{1,0,false,false},1./120);require(p.isClimbing(),"Climb begins on eligible ledge");return p;};
  Player removed=climb();float before=removed.feet.y;scene.edit(0,145,2,0);
  removed.advance(world,{1,0,false,false},1./120);
  require(!removed.isClimbing()&&removed.feet.y<=before+.001f,"Removing landing cancels lift on the next physics tick");
  run(removed,world,{},.8);require(removed.grounded&&std::abs(removed.feet.y-145)<.001f,"Removed landing resumes gravity");
  scene.edit(0,145,2,stone);Player reversed=climb();before=reversed.feet.y;
  run(reversed,world,{-1,0,false,false},.05);
  require(!reversed.isClimbing()&&reversed.feet.y<=before+.001f,"Reversal cancels climb without lifting");
  Player space=climb();run(space,world,{1,0,false,true},.025);
  require(!space.isClimbing(),"Manual jump input interrupts assisted climb");
  scene.edit(0,145,2,0);
  if(slab!=UINT32_MAX){
   scene.edit(0,145,2,slab);Player half=start();run(half,world,{1,0,false,false},.8);
   require(half.autoSteps>0&&half.feet.z>2&&half.feet.y<146.001f,"Actual terrain half-block is traversable");
   scene.edit(0,145,2,0);
  }
  // A falling player sweeps across a floor, even after a long presentation stall.
  Player fall;fall.reset({.5f,150,.5f});fall.velocity.y=-55;fall.advance(world,{},.25);
  require(fall.grounded&&fall.feet.y>=144.999f&&world.clear(Player::bounds(fall.feet)),"Terminal-speed fall cannot tunnel through floor");
  Player rate30=start(),rate144=start();run(rate30,world,{1,0,false,false},4,30);run(rate144,world,{1,0,false,false},4,144);
  require(std::abs(rate30.feet.z-rate144.feet.z)<.001f&&rate30.ticks==rate144.ticks,"Longer walking route remains independent of display rate");
  for(int x=60;x<=63;++x)for(int z=0;z<=3;++z){scene.edit(x,144,z,stone);for(int y=145;y<=149;++y)scene.edit(x,y,z,0);}
  Player edge=start(62.5f,1.5f);edge.yaw=1.5707963268f;run(edge,world,{1,0,true,false},2);
  require(edge.feet.x<=63.7001f&&world.clear(Player::bounds(edge.feet)),"Imported edge blocks walking outside loaded world");
  if(log!=UINT32_MAX&&snow!=UINT32_MAX){
   scene.edit(4,145,2,log);scene.edit(4,146,2,snow);
   std::vector<Box> boxes;world.query({{4,146,2},{5,147,3}},boxes);
   bool denied=false;for(auto b:boxes)denied=denied||b.noStep;
   require(denied,"Snow cap on a trunk is not natural stepping terrain");
   scene.edit(4,145,2,0);scene.edit(4,146,2,0);
  }
  // Mode abilities and flight are exercised after the original walking fixtures.
  const unsigned modeFlightStart=checks;
  for(int x=-10;x<=-7;++x)for(int z=-10;z<=5;++z)for(int y=145;y<=156;++y)scene.edit(x,y,z,0);
  auto airborne=[](int mode=1,bool savedFlying=true,float x=-8.5f){
   Player p;p.reset({x,147,-8.5f},0,0);p.setGameMode(mode,savedFlying);return p;
  };
  require(world.clear(Player::bounds({-8.5f,147,-8.5f})),"Flight fixture has clear air above the floor");
  struct ModeCase {int value;bool fly,build,noclip,initialFlight;const char* label;};
  const ModeCase modes[]={
   {0,false,true,false,false,"Survival walks, builds and keeps collision"},
   {1,true,true,false,false,"Creative starts grounded with flight permission and collision"},
   {2,false,false,false,false,"Adventure walks with collision and cannot build"},
   {3,true,false,true,true,"Spectator immediately flies without collision or building"}
  };
  for(const auto& mode:modes){
   Player p=start(-8.5f,-8.5f);p.setGameMode(mode.value);
   require(p.mode()==mode.value&&p.mayFly()==mode.fly&&p.mayBuild()==mode.build
    &&p.noClip()==mode.noclip&&p.isFlying()==mode.initialFlight
    &&p.grounded!=mode.initialFlight,mode.label);
  }
  Player guarded=airborne();
  for(int invalidMode:{-1,4}){
   bool rejected=false;try{guarded.setGameMode(invalidMode);}catch(const std::exception&){rejected=true;}
   require(rejected&&guarded.mode()==1&&guarded.isFlying()&&!guarded.noClip(),"Invalid and default-only mode values cannot change player abilities");
  }

  const PlayerInput pressJump{0,0,false,true,false},pressDescend{0,0,false,false,true};
  Player toggle=start(-8.5f,-8.5f);toggle.setGameMode(1);
  run(toggle,world,pressJump,.1);
  require(!toggle.isFlying()&&toggle.jumps==1,"First Creative jump edge performs a ground jump");
  run(toggle,world,pressJump,.15);
  require(!toggle.isFlying()&&toggle.jumps==1,"Holding jump does not manufacture a second edge");
  run(toggle,world,{},.05);run(toggle,world,pressJump,.05);
  require(toggle.isFlying()&&!toggle.grounded&&!toggle.noClip(),"Second jump edge inside the window enables Creative flight");
  run(toggle,world,pressJump,.2);
  require(toggle.isFlying(),"Held jump ascends without repeatedly toggling flight");
  run(toggle,world,{},.4);run(toggle,world,pressJump,.05);
  require(toggle.isFlying(),"One new jump edge cannot disable established Creative flight");
  run(toggle,world,{},.05);run(toggle,world,pressJump,.05);
  require(!toggle.isFlying()&&!toggle.noClip(),"A fresh double jump edge disables Creative flight");
  Player expired=start(-8.5f,-8.5f);expired.setGameMode(1);
  run(expired,world,pressJump,.05);run(expired,world,{},.4);run(expired,world,pressJump,.05);
  require(!expired.isFlying(),"Jump edges outside the double-tap window do not enable flight");
  Player interruptedToggle=start(-8.5f,-8.5f);interruptedToggle.setGameMode(1);
  run(interruptedToggle,world,pressJump,.05);interruptedToggle.suspend();run(interruptedToggle,world,pressJump,.05);
  require(!interruptedToggle.isFlying(),"Input suspension clears an unfinished flight toggle gesture");

  Player changing=airborne(3,false);changing.setGameMode(1);
  require(changing.mode()==1&&changing.isFlying()&&!changing.noClip()&&changing.mayBuild(),"Spectator to Creative keeps flight but restores collision and building");
  changing.setGameMode(0,true);
  require(changing.mode()==0&&!changing.isFlying()&&!changing.mayFly()&&!changing.noClip(),"Survival revokes flight even when a saved flying flag is supplied");
  before=changing.feet.y;run(changing,world,{},.1);
  require(changing.feet.y<before-.01f&&changing.velocity.y<0,"Leaving flight resumes gravity");
  Player adventure=airborne();adventure.setGameMode(2,true);before=adventure.feet.y;run(adventure,world,{},.1);
  require(adventure.mode()==2&&!adventure.isFlying()&&!adventure.mayFly()&&!adventure.noClip()
   &&!adventure.mayBuild()&&adventure.feet.y<before-.01f,"Adventure revokes Creative flight and resumes falling");

  Player spectator=airborne(3,false);before=spectator.feet.y;run(spectator,world,pressJump,.15);
  require(spectator.isFlying()&&!spectator.grounded&&spectator.feet.y>before+.2f&&spectator.velocity.y>0,"Spectator jump input ascends");
  before=spectator.feet.y;run(spectator,world,pressDescend,.3);
  require(spectator.isFlying()&&spectator.feet.y<before-.2f&&spectator.velocity.y<0,"Spectator descend input reverses vertical motion");
  Player balanced=airborne(3,false);before=balanced.feet.y;run(balanced,world,{0,0,false,true,true},.25);
  require(balanced.isFlying()&&std::abs(balanced.feet.y-before)<.0001f&&balanced.velocity.y==0,"Simultaneous ascent and descent cancel without gravity");
  Player forced=airborne(3,false);run(forced,world,pressJump,.05);run(forced,world,{},.05);run(forced,world,pressJump,.05);
  require(forced.mode()==3&&forced.isFlying()&&forced.noClip(),"Double jump cannot turn off forced Spectator flight");

  Player cruise=airborne(),diagonalFlight=airborne();
  const V3 cruiseStart=cruise.feet;run(cruise,world,{1,0,false,false,false},.5);
  require(cruise.feet.z>cruiseStart.z+1&&std::abs(cruise.feet.y-cruiseStart.y)<.0001f
   &&cruise.steps==0&&cruise.jumps==0,"Level flight accelerates without gravity or walking steps");
  run(diagonalFlight,world,{1,1,false,false,false},.5);
  const float straightDistance=cruise.feet.z-cruiseStart.z;
  const float diagonalDistance=std::hypot(diagonalFlight.feet.x-cruiseStart.x,diagonalFlight.feet.z-cruiseStart.z);
  require(diagonalDistance<=straightDistance+.002f&&diagonalDistance>straightDistance*.98f,"Diagonal flight does not gain extra speed");
  Player coast=airborne();const V3 coastStart=coast.feet;coast.velocity={2,2,2};run(coast,world,{},.15);
  require(coast.feet.z>coastStart.z&&coast.feet.y>coastStart.y&&coast.velocity.y>0
   &&coast.velocity.y<coast.velocity.z&&coast.velocity.z<2
   &&std::abs(coast.velocity.x-coast.velocity.z)<.0001f,"Released flight retains inertia and brakes vertically sooner than horizontally");
  const float coastingZ=coast.feet.z;run(coast,world,{},.8);
  require(coast.isFlying()&&coast.velocity.y==0&&coast.velocity.z>0&&coast.feet.z>coastingZ,"Vertical flight settles while horizontal momentum still decays");
  Player flight30=airborne(),flight144=airborne();
  run(flight30,world,{1,0,false,true,false},.5,30);run(flight30,world,{},.5,30);
  run(flight144,world,{1,0,false,true,false},.5,144);run(flight144,world,{},.5,144);
  require(flight30.ticks==flight144.ticks&&dot(flight30.feet-flight144.feet,flight30.feet-flight144.feet)<.000001f
   &&dot(flight30.velocity-flight144.velocity,flight30.velocity-flight144.velocity)<.000001f,"Flight impulses and braking are independent of display rate");

  // A wall inside the free coasting distance distinguishes collision from slow movement.
  for(int x=-10;x<=-7;++x)for(int y=145;y<=154;++y)scene.edit(x,y,-7,stone);
  Player blockedFlight=airborne(),freeFlight=airborne(1,true,-4.5f),throughWall=airborne(3,false);
  blockedFlight.velocity=freeFlight.velocity=throughWall.velocity={0,0,18};
  blockedFlight.advance(world,{},.25);freeFlight.advance(world,{},.25);throughWall.advance(world,{},.25);
  require(freeFlight.feet.z>-5.7f,"Free flight control crosses the complete wall distance during a bounded stall");
  require(blockedFlight.isFlying()&&blockedFlight.feet.z<=-7.2999f&&blockedFlight.feet.z>-7.5f
   &&world.clear(Player::bounds(blockedFlight.feet))&&!blockedFlight.isClimbing()&&blockedFlight.autoSteps==0,"Creative flight sweeps into the wall without tunneling or assisted climbing");
  require(throughWall.isFlying()&&throughWall.noClip()&&std::abs(throughWall.feet.z-freeFlight.feet.z)<.001f,"Spectator crosses a solid wall like the unobstructed flight control");
  Player flightLanding;flightLanding.reset({-8.5f,145.2f,-8.5f},0,0);flightLanding.setGameMode(1,true);flightLanding.velocity.y=-18;flightLanding.advance(world,{},.25);
  require(flightLanding.grounded&&!flightLanding.isFlying()&&flightLanding.feet.y>=144.999f
   &&world.clear(Player::bounds(flightLanding.feet)),"Creative descent collides with the floor and exits flight");
  Player throughFloor;throughFloor.reset({-8.5f,145.2f,-8.5f},0,0);throughFloor.setGameMode(3);throughFloor.velocity.y=-18;throughFloor.advance(world,{},.25);
  require(throughFloor.isFlying()&&!throughFloor.grounded&&throughFloor.feet.y<144,"Spectator descent ignores the same solid floor");

  Player savedFlyer=airborne();savedFlyer.velocity={1,2,3};savedFlyer.stamina=117;
  std::ostringstream flightSave;savedFlyer.write(flightSave);
  for(const auto& mode:modes){
   Player restored;std::istringstream input(flightSave.str());restored.read(input,world,mode.value,true);
   require(restored.mode()==mode.value&&restored.isFlying()==mode.fly&&restored.mayFly()==mode.fly
    &&restored.mayBuild()==mode.build&&restored.noClip()==mode.noclip
    &&dot(restored.feet-savedFlyer.feet,restored.feet-savedFlyer.feet)<1e-10f
    &&dot(restored.velocity-savedFlyer.velocity,restored.velocity-savedFlyer.velocity)<1e-10f
    &&restored.stamina==savedFlyer.stamina,"External world mode and saved flight restore without losing player state");
  }
  Player nonflyingCreative=airborne(3,false);std::istringstream groundedInput(flightSave.str());nonflyingCreative.read(groundedInput,world,1,false);
  require(nonflyingCreative.mode()==1&&!nonflyingCreative.isFlying()&&nonflyingCreative.mayFly()&&!nonflyingCreative.noClip(),"Reading nonflying Creative replaces previous Spectator abilities");
  Player embedded;embedded.reset({-8.5f,147,-6.5f},0,0);std::ostringstream embeddedSave;embedded.write(embeddedSave);
  require(!world.clear(Player::bounds(embedded.feet)),"Embedded save fixture actually intersects the wall");
  for(int mode:{0,1,2}){
   bool rejected=false;try{Player restored;std::istringstream input(embeddedSave.str());restored.read(input,world,mode,true);}catch(const std::exception&){rejected=true;}
   require(rejected,"Collision-enabled modes reject an embedded save even with saved flight");
  }
  Player embeddedSpectator;std::istringstream embeddedInput(embeddedSave.str());embeddedSpectator.read(embeddedInput,world,3,false);
  require(embeddedSpectator.mode()==3&&embeddedSpectator.isFlying()&&embeddedSpectator.noClip()
   &&dot(embeddedSpectator.feet-embedded.feet,embeddedSpectator.feet-embedded.feet)<1e-10f
   &&!world.clear(Player::bounds(embeddedSpectator.feet)),"Spectator alone restores the exact occupied position");
  run(embeddedSpectator,world,{1,0,false,false,false},.5);
  require(embeddedSpectator.isFlying()&&embeddedSpectator.feet.z>-5.7f
   &&world.clear(Player::bounds(embeddedSpectator.feet)),"Restored Spectator can leave an occupied save position");
  bool corruptSpectator=false;
  try{Player restored;std::istringstream bad("0 147 0 0 0 151 0 0 0 0 0 0 0");restored.read(bad,world,3,false);}catch(const std::exception&){corruptSpectator=true;}
  require(corruptSpectator,"Spectator collision bypass does not bypass numeric save validation");
  // The live server transition adds ground handling; saved abilities still use setGameMode.
  Player nearGround;nearGround.reset({-4.5f,145.5f,-8.5f},0,0);nearGround.setGameMode(3);nearGround.changeGameMode(world,1);
  require(nearGround.mode()==1&&!nearGround.isFlying()&&nearGround.mayFly()&&!nearGround.noClip()
   &&nearGround.feet.y==145.5f,"Spectator to Creative less than one block above ground disables flight without moving the player");
  Player aboveGround=airborne(3,false);aboveGround.changeGameMode(world,1);
  require(aboveGround.mode()==1&&aboveGround.isFlying()&&!aboveGround.noClip()
   &&aboveGround.feet.y==147,"Spectator to Creative more than one block above ground preserves flight");
  Player embeddedChange;embeddedChange.reset(embedded.feet,0,0);embeddedChange.setGameMode(3);embeddedChange.changeGameMode(world,1);
  require(embeddedChange.mode()==1&&embeddedChange.isFlying()&&!embeddedChange.noClip()
   &&!world.clear(Player::bounds(embeddedChange.feet)),"An embedded Spectator keeps flight when changing to Creative");
  Player sameCreative;sameCreative.reset({-4.5f,145.5f,-8.5f},0,0);sameCreative.setGameMode(1,true);sameCreative.changeGameMode(world,1);
  require(sameCreative.mode()==1&&sameCreative.isFlying()&&!sameCreative.noClip()
   &&sameCreative.feet.y==145.5f,"Applying the unchanged Creative mode near ground preserves existing flight");
  for(int x=-10;x<=-7;++x)for(int y=145;y<=154;++y)scene.edit(x,y,-7,0);
  const unsigned modeFlightChecks=checks-modeFlightStart;
  std::ofstream f(out/"checks.json");f<<std::setprecision(9)<<"{\"completed\":true,\"checks\":"<<checks<<",\"modeFlightChecks\":"<<modeFlightChecks<<",\"groundedSteps\":"<<walk.steps<<",\"distance\":"<<walk.distance<<",\"queries\":"<<world.queries<<",\"visitedCells\":"<<world.visited<<"}\n";
  require(bool(f),"Transition report written");
  std::cout<<"CORE_PLAYER_TRANSITIONS_PASS "<<checks<<" checks\n";return 0;
 }catch(const std::exception& e){std::cerr<<"PLAYER_TRANSITION_FAILED "<<checks<<" "<<e.what()<<"\n";return 1;}
}
