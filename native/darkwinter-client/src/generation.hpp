#pragma once
#include "scene.hpp"
namespace dw::client {
using SectionCells=std::array<uint32_t,4096>;
using GeneratedColumn=SceneColumn;
class Generator {
 std::vector<std::string> names;uint64_t seed;uint32_t generationVersion=4;
 uint32_t stone=1,dirt=1,grass=1,slab=1,snow=0,tuft=0,rock=0;std::array<uint32_t,4> shrubs{},shrubTops{};
 std::array<uint32_t,6> logs{};std::array<std::array<uint32_t,2>,8> boughs{},innerBoughs{};
 double noise(double x,double z,uint64_t salt)const;
 int nativeMinY=48,nativeMaxY=160;
 struct LeaderAnchor {
  int gx=0,gz=0,x=0,z=0,base=0,tall=0;uint32_t version=0;bool present=false;
 };
 // Scene's owner thread queries this only for changed terminal logs. Generation
 // workers use local scratch and never touch this bounded render-context cache.
 mutable std::array<LeaderAnchor,64> leaderAnchors{};
 uint32_t find(std::string_view prefix)const;
 GeneratedColumn generateGrounded(int sx,int sz,int minY,int sectionsY,const std::atomic<bool>* cancel)const;
public:
 static constexpr uint32_t version=4;
 void useVersion(uint32_t value){if(value<1||value>version)throw std::runtime_error("Native generator version");generationVersion=value;}uint32_t storedVersion()const{return generationVersion;}
 Generator(const Scene& scene,uint64_t seed);
 uint64_t hash(int x,int z,uint64_t salt=0)const;
 double height(int x,int z)const;
 GeneratedColumn generate(int sx,int sz,int minY,int sectionsY,const std::atomic<bool>* cancel=nullptr)const;
 // Render-only identity of the unmodified natural leader; core/save IDs stay intact.
 bool naturalLeader(int x,int y,int z,uint32_t material)const;
 uint64_t worldSeed()const{return seed;}
};
}
