#include "../src/scene.hpp"
#include <iostream>
#include <cstring>
using namespace dw::client;
static uint32_t checks=0;
static void check(bool b,const char* message){++checks;if(!b)throw std::runtime_error(message);}
int main(int argc,char** argv){
 try{
  if(argc!=3)throw std::runtime_error("root and owned output required");std::filesystem::path root=argv[1],out=argv[2];std::filesystem::create_directories(out);
  {
   Scene road(root/"build/core/client/world.dwv",root/"build/core/client/assets.dwa");
   check(road.sections==60&&road.materials==22,"DWA v1 road compatibility");
   check(road.world[11]==1&&road.world[12]==1,"Legacy owner stencil");
  }
  auto assets=root/"build/core/world-client/assets.dwa";Scene scene(root/"build/core/world-client/world.dwv",assets);uint64_t initial=scene.logicalHash();
  check(scene.assets[14]!=0&&scene.assets[15]==1,"Production package uses indexed BVH");
  {
   Scene original(root/"build/core/source-scene/current/world.dwv",root/"build/core/source-scene/current/assets.dwa");
   check(original.logicalHash()==initial&&original.world==scene.world,"All render cells, owner masks and occupancy unchanged");
   check(original.assets[7]==scene.assets[7]&&std::equal(original.assets.begin()+original.assets[3],original.assets.begin()+original.assets[3]+original.assets[7]*24,scene.assets.begin()+scene.assets[3]),"Every original triangle payload and priority preserved");
   auto pixels=[](const Scene& s){uint64_t end=0;for(uint32_t i=0;i<s.assets[8];++i){size_t t=s.assets[4]+i*4;end=std::max(end,uint64_t(s.assets[t])+uint64_t(s.assets[t+1])*s.assets[t+2]);}return end;};
   check(original.assets[8]==scene.assets[8]&&pixels(original)==pixels(scene)&&std::equal(original.assets.begin()+original.assets[5],original.assets.begin()+original.assets[5]+pixels(original),scene.assets.begin()+scene.assets[5]),"Texture pixels unchanged");
  }
  {Scene legacy(root/"build/core/render-study/baseline/world.dwv",assets);
   Scene old(root/"build/core/render-study/baseline/world.dwv",root/"build/core/render-study/baseline/assets.dwa");
   check(legacy.logicalHash()==old.logicalHash(),"Stage 3/4 world loads with stable material IDs and new renderer assets");
   for(uint32_t i=0;i<old.materials;i++)check(legacy.materialName(i)==old.materialName(i),"Legacy material names retain numeric identity");
  }
  auto encoded=read_file(assets,256*1024*1024);
  for(int test=0;test<4;++test){
   auto bad=encoded;auto word=[&](size_t at,uint32_t value){std::memcpy(bad.data()+at,&value,4);};
   if(test==0)word(32+14*4,1);
   if(test==1)word(32+size_t(scene.assets[14])*4,scene.assets[7]);
   if(test==2)word(32+(size_t(scene.assets[14])+1)*4,scene.assets[scene.assets[14]]);
   if(test==3)word(32+15*4,2);
   uint64_t hash=dw::checksum(bad.data()+32,bad.size()-32);std::memcpy(bad.data()+24,&hash,8);
   auto path=out/("invalid-index-"+std::to_string(test)+".dwa");
   {std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<const char*>(bad.data()),std::streamsize(bad.size()));}
   bool rejected=false;try{Scene invalid(root/"build/core/world-client/world.dwv",path);}catch(const std::exception&){rejected=true;}check(rejected,"Malformed indexed hierarchy rejected after valid checksum");
  }

  if(scene.assets[10]){auto at=scene.assets[9];int x=std::bit_cast<int32_t>(scene.assets[at]),y=std::bit_cast<int32_t>(scene.assets[at+1]),z=std::bit_cast<int32_t>(scene.assets[at+2]);auto mid=scene.cell(x,y,z);auto before=scene.world;check(scene.edit(x,y,z,0),"Remove context variant");scene.pump(true);check(scene.edit(x,y,z,mid),"Restore context variant");scene.pump(true);check(scene.world==before,"Restoring same state also restores exact source shape");}
  check(!scene.edit(-33,150,0,1),"Reject absent section");check(!scene.edit(0,150,0,scene.materials),"Reject unknown material");
  std::vector<std::array<int,4>> restore;
  for(int x:{-32,-17,-16,-1,0,15,16,31,63})for(int y:{48,63,64,159}){
   int z=x;auto old=scene.cell(x,y,z);restore.push_back({x,y,z,int(old)});check(old<scene.materials,"Boundary cell exists");
   uint32_t next=old==0?1:0;check(scene.edit(x,y,z,next),"Edit signed section boundary");
  }
  scene.pump();auto first=restore.front();check(scene.edit(first[0],first[1],first[2],uint32_t(first[3])),"Advance revision while task pending");
  scene.pump(true);check(scene.staleBatches>=1,"Outdated result rejected");check(scene.activeJobs()==0,"All task handles released");
  auto saved=out/"edited.dwv";scene.save(saved);auto before=read_file(saved,256*1024*1024);
  bool refused=false;try{scene.save(saved);}catch(const std::exception&){refused=true;}check(refused,"Existing save cannot be overwritten");check(read_file(saved,256*1024*1024)==before,"Refused overwrite preserved bytes");
  {Scene reopened(saved,assets);check(reopened.logicalHash()==scene.logicalHash(),"All logical cells survive save");check(reopened.world==scene.world,"Incremental hierarchy equals fresh complete rebuild");}
  for(auto p:restore)scene.edit(p[0],p[1],p[2],uint32_t(p[3]));scene.pump(true);
  check(scene.logicalHash()==initial,"Undo all signed-boundary edits restores exact logical world");
  std::cout<<"CORE_SCENE_CHECKS_PASS "<<checks<<" checks\n";return 0;
 }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}
