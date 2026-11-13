#include "../src/streaming.hpp"
#include "../src/player.hpp"
#include "../src/settings.hpp"
#include "../src/environment.hpp"
#include <iostream>
#include <thread>
#include <cstring>
using namespace dw::client;
static unsigned checks=0;
static void check(bool ok,const char* why){++checks;if(!ok)throw std::runtime_error(why);}
static bool equal(const GeneratedColumn& a,const GeneratedColumn& b){if(a.x!=b.x||a.z!=b.z||a.sections.size()!=b.sections.size())return false;for(size_t i=0;i<a.sections.size();i++)if(a.sections[i].first.x!=b.sections[i].first.x||a.sections[i].first.y!=b.sections[i].first.y||a.sections[i].first.z!=b.sections[i].first.z||a.sections[i].second!=b.sections[i].second)return false;return true;}
int main(int argc,char** argv){try{
 if(argc<3||argc>4||(argc==4&&std::string(argv[3])!="--raster-only"))throw std::runtime_error("package, owned report and optional --raster-only required");std::filesystem::path dist=argv[1],out=argv[2];std::filesystem::create_directories(out);
 Scene scene(dist/"world.dwv",dist/"assets.dwa",false);Generator gen(scene,19971008),other(scene,19971009);
 {
  Scene raster(dist/"world.dwv",dist/"assets.dwa",false,false);
  check(raster.logicalHash()==scene.logicalHash(),"Raster-only scene preserves the authoritative imported cells");
  check(raster.world.size()==raster.world[7]+size_t(raster.sections)*4096,"Raster-only scene omits unused traversal arrays");
  raster.prepareNative(6);StreamingWorld stream(raster,19971008);stream.bootstrap({.5f,90,.5f});
  check(raster.settled()&&raster.activeJobs()==0&&!raster.instances.empty(),"Raster-only stream publishes geometry without traversal jobs");
  check(raster.edit(-1,100,-1,1),"Raster-only edit accepted");auto dirty=raster.pump();
  check(!dirty.empty()&&raster.cell(-1,100,-1)==1&&raster.settled(),"Raster-only edit publishes matching cell and render data");
  auto bounded=raster.reference({-2.f,100.5f,-.5f},{1,0,0},UINT32_MAX,.5f);
  auto reachable=raster.reference({-2.f,100.5f,-.5f},{1,0,0},UINT32_MAX,2.f);
  check(bounded.material==UINT32_MAX&&reachable.material==1,"Bounded picking rejects distant cells and retains reachable geometry");
  auto journal=out/"raster.dwg",snapshot=out/"raster.dwv";stream.save(journal);raster.save(snapshot);
  Scene restored(snapshot,dist/"assets.dwa",false,false);check(restored.logicalHash()==raster.logicalHash(),"Raster-only snapshot round trip");
  restored.prepareNative(6);StreamingWorld reload(restored,19971008);reload.restore(journal);reload.bootstrap({.5f,90,.5f});
  check(restored.cell(-1,100,-1)==1&&reload.editCount()==1,"Raster-only journal survives regeneration");
 }
 if(argc==4){std::cout<<"CORE_RASTER_SCENE_PASS checks="<<checks<<"\\n";return 0;}
 auto a=gen.generate(-1,-1,3,7),b=gen.generate(0,0,3,7);check(equal(a,gen.generate(-1,-1,3,7)),"Stable negative-coordinate generation");check(!equal(b,other.generate(0,0,3,7)),"Different seeds produce different terrain");
 GeneratedColumn parallel;std::thread worker([&]{parallel=gen.generate(-1,-1,3,7);});gen.generate(1,1,3,7);worker.join();check(equal(a,parallel),"Generation independent of worker/order");
 bool valid=true;size_t solid=0;for(const auto& [key,cells]:a.sections){valid&=key.x==-1&&key.z==-1;for(auto m:cells){valid&=m<scene.materials;solid+=m!=0;}}check(valid&&solid>256,"Generated cell ownership and material bounds");
 bool smooth=true;for(int x=-32;x<32;x++)for(int z=-32;z<32;z++)smooth&=std::abs(gen.height(x,z)-gen.height(x+1,z))<=.5&&std::abs(gen.height(x,z)-gen.height(x,z+1))<=.5;check(smooth,"Terrain continuous across positive/negative chunk edges");
 Generator legacy(scene,19971008);legacy.useVersion(1);auto legacyColumn=legacy.generate(0,0,3,7);std::vector<uint32_t> legacyCells;for(const auto& [key,cells]:legacyColumn.sections)legacyCells.insert(legacyCells.end(),cells.begin(),cells.end());check(dw::checksum(reinterpret_cast<const uint8_t*>(legacyCells.data()),legacyCells.size()*4)==0xc38b0956959790f8ull,"Generator v1 reproduces the saved original column byte for byte");check(!equal(b,legacyColumn),"New native worlds use the denser versioned forest");
 scene.prepareNative();bool flatCap=true;for(uint32_t m=0;m<scene.materials;m++)if(scene.materialName(m).starts_with("darkwinter:test_snowy_grass_slab[")){uint32_t count=scene.assets[scene.assets[1]+m*8+2];for(uint32_t j=0;j<count;j++){size_t t=scene.assets[3]+size_t(scene.triangle_starts[m]+j)*24;auto f=[&](size_t n){return std::bit_cast<float>(scene.assets[n]);};if(f(t+21)>.5f)flatCap&=std::abs(f(t+1)-.625f)<1e-5f&&std::abs(f(t+5))<1e-5f&&std::abs(f(t+9))<1e-5f;}}check(flatCap,"Native terrace does not repeat a captured corner depression");auto words=scene.world.size();auto sections=scene.sections;
 {
 StreamingWorld stream(scene,19971008);stream.bootstrap({.5f,90,.5f});check(stream.generatedColumns==169&&stream.shifts==1,"Initial resident window generated completely");check(scene.sections==1183&&scene.world.size()==words,"Initial resident memory budget");

 // Captured interior, opaque-texel rays from the native Vulkan failure.
 // The canonical CPU material stays unchanged; filtered hits prove the other
 // coincident source surface and preserve a real miss for empty material.
 struct TieRay {V3 ray;uint32_t canonical,other;};
 V3 tieOrigin{.5f,71.6031494141f,4.16094446182f};
 for(const auto& test:std::array<TieRay,2>{{
     {{.0442318022251f,.457510828972f,.888103306293f},130,162},
     {{-.567452609539f,.333934277296f,.752652287483f},131,138}}}){
  auto canonical=scene.reference(tieOrigin,test.ray);
  check(canonical.material==test.canonical&&!canonical.ambiguous,"Observed coplanar ray keeps canonical CPU material");
  auto alternate=scene.reference(tieOrigin,test.ray,test.other);
  check(alternate.material==test.other&&!alternate.ambiguous
        &&std::abs(alternate.distance-canonical.distance)<=Scene::surfaceTieEpsilon,
        "Filtered CPU hit confirms coincident material in the fixed original distance band");
  check(alternate.triangle!=UINT32_MAX&&(scene.assets[alternate.triangle+7]&3u)==0u
        &&(scene.assets[alternate.triangle+11]>>24)==255u,
        "Observed cutout tie uses direct UV and unchanged source alpha");
  auto empty=scene.reference(tieOrigin,test.ray,0);
  check(empty.material==UINT32_MAX&&empty.triangle==UINT32_MAX,"Material filter cannot manufacture a hit in empty space");
 }
 CollisionWorld collision(scene,dist/"collision.dwc");auto spawn=collision.spawn({.5f,float(gen.height(0,0))+2,.5f});check(collision.clear(Player::bounds(spawn)),"Native player spawn clear of terrain");
 check(scene.edit(-1,100,-1,1),"Edit negative coordinate");scene.pump(true);check(stream.editCount()==1,"Edit enters journal");stream.save(out/"world.dwg");scene.save(out/"world.dwv");
 stream.bootstrap({8.5f,110,44.63348f});auto silhouette=scene.reference({8.5f,110,44.63348f},{-.294314950705f,-.311886012554f,.9033857584f});check(silhouette.ambiguous&&scene.materialName(silhouette.material).starts_with("darkwinter:snowy_shrub["),"Observed distant subpixel branch is classified as a geometric roundoff boundary");
 auto sliver=scene.reference({8.5f,110,44.63348f},{.677249968052f,-.28553968668f,.678085207939f});
 check(sliver.ambiguous&&sliver.material==54,"Distant thin triangle uses world-space roundoff even at interior barycentric coordinates");
 stream.bootstrap({160.5f,90,.5f});check(scene.cell(-1,100,-1)==UINT32_MAX,"Far column unloaded");check(scene.sections==sections&&scene.world.size()==words,"Resident budget held after shift");
 stream.bootstrap({.5f,90,.5f});check(scene.cell(-1,100,-1)==1,"Edit survives unload and return");
 stream.tick({-200.5f,90,-200.5f});stream.bootstrap({300.5f,90,300.5f});check(std::bit_cast<int32_t>(scene.world[0])==12&&std::bit_cast<int32_t>(scene.world[2])==12,"Outdated generation cannot replace current destination");
 stream.bootstrap({-32760.f,90,-32760.f});check(std::bit_cast<int32_t>(scene.world[0])==-2048&&scene.world.size()==words,"Negative coordinate limit clamped");
 stream.bootstrap({32760.f,90,32760.f});check(std::bit_cast<int32_t>(scene.world[0])==2035&&scene.world.size()==words,"Positive coordinate limit clamped");
 }
 {Scene saved(out/"world.dwv",dist/"assets.dwa",false);saved.prepareNative();StreamingWorld restored(saved,19971008);restored.restore(out/"world.dwg");restored.bootstrap({.5f,90,.5f});check(restored.editCount()==1&&saved.cell(-1,100,-1)==1,"Cold journal reload reproduces edited terrain");}
 auto bytes=read_file(out/"world.dwg",20*1024*1024);
 for(int test=0;test<7;test++){auto bad=bytes;auto word=[&](size_t at,uint32_t v){std::memcpy(bad.data()+at,&v,4);};if(test==0)bad[0]^=1;if(test==1)bad.back()^=1;if(test==2)word(28,1048577);if(test==3)word(32,32768);if(test==4)word(36,160);if(test==5)word(44,scene.materials);if(test==6)bad[20]^=1;if(test>=2){auto sum=dw::checksum(bad.data(),bad.size()-8);std::memcpy(bad.data()+bad.size()-8,&sum,8);}auto file=out/("invalid-"+std::to_string(test)+".dwg");{std::ofstream f(file,std::ios::binary);f.write(reinterpret_cast<const char*>(bad.data()),bad.size());}bool rejected=false;try{StreamingWorld invalid(scene,19971008);invalid.restore(file);}catch(...){rejected=true;}check(rejected,"Malformed journal rejected");}
 {bool rejected=false;try{StreamingWorld wrong(scene,9);wrong.restore(out/"world.dwg");}catch(...){rejected=true;}check(rejected,"Journal seed mismatch rejected");}
 {std::atomic<bool> cancelled=true;bool stopped=false;try{StreamingWorld stream(scene,19971008);stream.bootstrap({0,90,0},{},&cancelled);}catch(...){stopped=true;}check(stopped,"Cancelled bootstrap stops safely");}
 check(northernSun(0).y<0&&northernSun(1200).y>0&&northernSun(3600).y<0,"North calendar drives dawn, noon and night");check(std::abs(dot(northernSun(1200),northernSun(1200))-1)<1e-5,"Solar vector normalized");
 bool climateValid=true,climateSmooth=true;auto previousClimate=nativeClimate(19971008,0);for(int sec=1;sec<12000;sec++){auto sample=nativeClimate(19971008,sec);climateValid&=sample.preset>=0&&sample.preset<9&&std::isfinite(sample.temperature)&&sample.wind>=0&&sample.wind<=30&&sample.bearing>=0&&sample.bearing<360&&sample.chill()<=sample.temperature;climateSmooth&=std::abs(sample.cover-previousClimate.cover)<.1f&&std::abs(sample.temperature-previousClimate.temperature)<.7f;previousClimate=sample;}check(climateValid,"Climate readings and wind chill stay in the physical model bounds");check(climateSmooth,"Weather and temperature remain continuous across transitions");auto climateBefore=nativeClimate(19971008,3346);nativeClimate(1,0);auto climateAfter=nativeClimate(19971008,3346);check(climateBefore.preset==climateAfter.preset&&climateBefore.wind==climateAfter.wind&&climateBefore.temperature==climateAfter.temperature,"Climate reconstructs from saved seed and clock");
 Settings setting;setting.fullscreen=false;setting.volume=.4f;setting.scale=1.5f;setting.save(out/"settings.cfg");Settings reread;reread.load(out/"settings.cfg");check(!reread.fullscreen&&reread.volume==.4f&&reread.scale==1.5f,"Settings persist across process restart");
 std::ofstream report(out/"checks.json");report<<"{\"completed\":true,\"checks\":"<<checks<<",\"residentSections\":1183}\n";std::cout<<checks<<" generation/streaming/journal checks passed\n";return 0;
 }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
