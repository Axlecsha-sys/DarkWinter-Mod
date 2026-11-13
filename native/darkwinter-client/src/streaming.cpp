#include "streaming.hpp"
#include <cstring>
#include <numeric>
#include <set>
#include <thread>
namespace dw::client {
static void need(bool test,const char* message){if(!test)throw std::runtime_error(message);}
// Section publication is implemented by the WKE runtime Scene.
void Scene::prepareNative(int diameter){
 need(settled()&&diameter>=6&&diameter<=13,"Native window resize state");
 // The source capture may choose a bevelled corner as a material's first template.
 // Native flat terraces need the original un-bevelled 0.5+0.125 slab cap, not that repeated corner.
 for(uint32_t material=0;material<materials;material++)if(materialName(material).starts_with("darkwinter:test_snowy_grass_slab[")){
  size_t desc=assets[1]+material*8;uint32_t begin=assets[desc],end=assets[desc+1],first=triangle_starts[material],count=assets[desc+2];
  auto read=[&](size_t at){return V3{std::bit_cast<float>(assets[at]),std::bit_cast<float>(assets[at+1]),std::bit_cast<float>(assets[at+2])};};
  auto write=[&](size_t at,V3 p){assets[at]=std::bit_cast<uint32_t>(p.x);assets[at+1]=std::bit_cast<uint32_t>(p.y);assets[at+2]=std::bit_cast<uint32_t>(p.z);};
  // Rebuild the closed cap and its trim from the captured material's own textures.
  // A lowered corner can reach exactly y=0.5, so thresholding vertices leaves a hole.
  std::array<uint32_t,24> top{},side{},bottom{};bool haveTop=false,haveSide=false,haveBottom=false;
  for(uint32_t j=0;j<count;j++){size_t t=assets[3]+size_t(first+j)*24;V3 a=read(t),b=a+read(t+4),c=a+read(t+8),n=read(t+20);
   if(n.y>.5f&&!haveTop){std::copy_n(assets.begin()+t,24,top.begin());haveTop=true;}
   if(n.y<-.5f&&!haveBottom){std::copy_n(assets.begin()+t,24,bottom.begin());haveBottom=true;}
   if(std::abs(n.y)<.01f&&std::min({a.y,b.y,c.y})<.0001f&&std::max({a.y,b.y,c.y})<=.5001f&&!haveSide){std::copy_n(assets.begin()+t,24,side.begin());haveSide=true;}
  }
  need(haveTop&&haveSide&&haveBottom&&count>=20,"Native flat slab source templates");uint32_t emitted=0;
  auto quad=[&](std::array<V3,4> p,const std::array<uint32_t,24>& style,float uvHeight){
   std::array<std::array<float,2>,4> uv{{{0,uvHeight},{0,0},{1,0},{1,uvHeight}}};
   for(auto ix:{std::array<int,3>{0,1,2},std::array<int,3>{0,2,3}}){size_t t=assets[3]+size_t(first+emitted++)*24;std::copy(style.begin(),style.end(),assets.begin()+t);
    V3 a=p[ix[0]],e1=p[ix[1]]-a,e2=p[ix[2]]-a;write(t,a);write(t+4,e1);write(t+8,e2);write(t+20,normalized(cross(e1,e2)));
    for(int axis=0;axis<2;axis++){assets[t+12+axis]=std::bit_cast<uint32_t>(uv[ix[0]][axis]);assets[t+14+axis]=std::bit_cast<uint32_t>(uv[ix[1]][axis]-uv[ix[0]][axis]);assets[t+16+axis]=std::bit_cast<uint32_t>(uv[ix[2]][axis]-uv[ix[0]][axis]);}
   }
  };
  quad({V3{0,.625f,0},V3{0,.625f,1},V3{1,.625f,1},V3{1,.625f,0}},top,1);
  quad({V3{0,0,1},V3{0,0,0},V3{1,0,0},V3{1,0,1}},bottom,1);
  for(int band=0;band<2;band++){float lo=band?.5f:0,hi=band?.625f:.5f;const auto& style=band?top:side;float uv=band?.125f:1;
   quad({V3{0,lo,0},V3{0,hi,0},V3{1,hi,0},V3{1,lo,0}},style,uv);
   quad({V3{1,lo,0},V3{1,hi,0},V3{1,hi,1},V3{1,lo,1}},style,uv);
   quad({V3{1,lo,1},V3{1,hi,1},V3{0,hi,1},V3{0,lo,1}},style,uv);
   quad({V3{0,lo,1},V3{0,hi,1},V3{0,hi,0},V3{0,lo,0}},style,uv);
  }
  // Keep descriptor/index ownership stable; surplus triangles become zero-area.
  for(uint32_t j=emitted;j<count;j++){size_t t=assets[3]+size_t(first+j)*24;write(t,{});write(t+4,{});write(t+8,{});write(t+20,{});}
  if(begin==UINT32_MAX)continue;
  for(uint32_t node=end;node-->begin;){size_t at=assets[2]+size_t(node)*12;V3 lo{1e9f,1e9f,1e9f},hi{-1e9f,-1e9f,-1e9f};auto include=[&](V3 p){lo={std::min(lo.x,p.x),std::min(lo.y,p.y),std::min(lo.z,p.z)};hi={std::max(hi.x,p.x),std::max(hi.y,p.y),std::max(hi.z,p.z)};};
   if(assets[at+7]){for(uint32_t j=0;j<assets[at+7];j++){uint32_t ref=assets[at+3]+j;if(assets[14])ref=assets[assets[14]+ref];size_t t=assets[3]+size_t(ref)*24;V3 a=read(t);include(a);include(a+read(t+4));include(a+read(t+8));}lo=lo-V3{1e-6f,1e-6f,1e-6f};hi=hi+V3{1e-6f,1e-6f,1e-6f};}
   else{uint32_t left=node+1,right=assets[assets[2]+size_t(left)*12+8];need(right<end,"Native slab BVH child");include(read(assets[2]+size_t(left)*12));include(read(assets[2]+size_t(left)*12+4));include(read(assets[2]+size_t(right)*12));include(read(assets[2]+size_t(right)*12+4));}
   write(at,lo);write(at+4,hi);
  }
 }
 prepareNativeLogs();
 int ny=int(world[5]),y0=std::bit_cast<int32_t>(world[1]),cx=std::bit_cast<int32_t>(world[0])+int(world[4])/2,cz=std::bit_cast<int32_t>(world[2])+int(world[6])/2;
 int x0=std::clamp(cx-diameter/2,-2048,2048-diameter),z0=std::clamp(cz-diameter/2,-2048,2048-diameter);uint32_t radius=world[11],words=world[12];
 auto* c=static_cast<Core*>(owner);{std::lock_guard lock(c->mutex);c->sections.clear();}slots.clear();keys.clear();sourceVariants.clear();instances.clear();changed.clear();++epoch;
 sections=uint32_t(diameter*diameter*ny);world.assign(16+sections,0);world[0]=std::bit_cast<uint32_t>(x0);world[1]=std::bit_cast<uint32_t>(y0);world[2]=std::bit_cast<uint32_t>(z0);world[3]=sections;world[4]=world[6]=uint32_t(diameter);world[5]=uint32_t(ny);world[8]=16;world[7]=uint32_t(world.size());world.resize(world.size()+size_t(sections)*4096);world[9]=uint32_t(world.size());if(tracingMasks)world.resize(world.size()+size_t(sections)*147);world[10]=uint32_t(world.size());if(tracingMasks)world.resize(world.size()+size_t(sections)*4096*words);world[11]=radius;world[12]=words;world[13]=1;
 SectionCells empty{};uint32_t slot=0;
 for(int y=y0;y<y0+ny;y++)for(int z=z0;z<z0+diameter;z++)for(int x=x0;x<x0+diameter;x++){Key k{x,y,z};need(c->put(k,1,empty.data(),4096)==DW_OK,"Native empty section allocation");slots.emplace(k,slot);keys.push_back(k);world[16+slot]=slot;++slot;}
 pending=metadataDirty=false;
}
struct StreamingWorld::Plan {int x,z;uint64_t revision;std::vector<GeneratedColumn> columns;std::vector<uint64_t> tickets;};
StreamingWorld::StreamingWorld(Scene& s,uint64_t seed):scene(s),generator(s,seed),jobs(std::clamp(std::thread::hardware_concurrency(),1u,4u),s.world[4]*s.world[6]){
 scene.nativeNaturalLeader=[this](int x,int y,int z,uint32_t material){return generator.naturalLeader(x,y,z,material);};
 scene.onEdit=[this](int x,int y,int z,uint32_t value){need(edits.size()<1048576||edits.contains({x,y,z}),"World edit budget reached");edits[{x,y,z}]=value;};
}
StreamingWorld::~StreamingWorld(){scene.onEdit={};scene.nativeNaturalLeader={};jobs.stop();plan.reset();}
void StreamingWorld::start(int x,int z){
 auto p=std::make_unique<Plan>();p->x=x;p->z=z;p->revision=++serial;
 int nx=int(scene.world[4]),ny=int(scene.world[5]),nz=int(scene.world[6]),loY=std::bit_cast<int32_t>(scene.world[1]);
 int oldX=std::bit_cast<int32_t>(scene.world[0]),oldZ=std::bit_cast<int32_t>(scene.world[2]);
 for(int cz=z;cz<z+nz;cz++)for(int cx=x;cx<x+nx;cx++)if(initial||cx<oldX||cx>=oldX+nx||cz<oldZ||cz>=oldZ+nz){p->columns.push_back({cx,cz,{}});}
 p->tickets.reserve(p->columns.size());plan=std::move(p);Plan* out=plan.get();
 try {for(size_t i=0;i<out->columns.size();i++){
  int cx=out->columns[i].x,cz=out->columns[i].z;std::vector<std::pair<std::array<int,3>,uint32_t>> patch;
  for(auto it=edits.lower_bound({cx*16,INT32_MIN,INT32_MIN});it!=edits.end()&&it->first[0]<cx*16+16;it++)if(it->first[2]>=cz*16&&it->first[2]<cz*16+16)patch.push_back(*it);
  uint64_t ticket=0;need(jobs.submit([this,out,i,cx,cz,loY,ny,patch=std::move(patch)](const std::atomic<bool>& cancelled){
   auto column=generator.generate(cx,cz,loY,ny,&cancelled);if(cancelled)return Outcome{DW_CANCELLED,{}};
   for(const auto& [p,m]:patch)if(p[1]>=loY*16&&p[1]<(loY+ny)*16)column.sections[size_t(p[1]/16-loY)].second[size_t(p[0]-cx*16)+16*size_t(p[2]-cz*16)+256*size_t(p[1]%16)]=m;
   out->columns[i]=std::move(column);return Outcome{};
  },0,ticket)==DW_OK,"Generation queue full");out->tickets.push_back(ticket);
 }}catch(...){jobs.stop();plan.reset();throw;}
 progress=0;
}
bool StreamingWorld::tick(V3 position){
 need(std::isfinite(position.x)&&std::isfinite(position.z),"Streaming position invalid");
 int nx=int(scene.world[4]),nz=int(scene.world[6]);
 requestedX=std::clamp(int(std::floor(position.x/16))-nx/2,-2048,2048-nx);requestedZ=std::clamp(int(std::floor(position.z/16))-nz/2,-2048,2048-nz);
 if(plan){
  size_t done=0;for(auto t:plan->tickets)if(jobs.poll(t)!=DW_PENDING)++done;
  progress=plan->tickets.empty()?1.f:float(done)/float(plan->tickets.size());
  if(done<plan->tickets.size()||!scene.settled())return false;
  for(auto t:plan->tickets){need(jobs.poll(t)==DW_OK,"Generation failed");need(jobs.release(t)==DW_OK,"Generation ticket release");}
  if(plan->x!=requestedX||plan->z!=requestedZ){++discardedPlans;plan.reset();}
  else{
   generatedColumns+=plan->columns.size();scene.installColumns(plan->columns,plan->x,plan->z);plan.reset();initial=false;++shifts;return true;
  }
 }
 if(initial||std::bit_cast<int32_t>(scene.world[0])!=requestedX||std::bit_cast<int32_t>(scene.world[2])!=requestedZ)start(requestedX,requestedZ);
 return false;
}
void StreamingWorld::bootstrap(V3 p,const std::function<void(float)>& status,const std::atomic<bool>* cancelled){
 bool installed=false;while(!installed){if(cancelled&&cancelled->load())throw std::runtime_error("Loading cancelled");installed=tick(p);if(status)status(progress*.8f);std::this_thread::yield();}
 scene.pump(true);if(status)status(1);
}
void StreamingWorld::save(const std::filesystem::path& file)const{
 need(!std::filesystem::exists(file),"Cannot overwrite native world journal");std::vector<uint8_t> b;
 auto add=[&](auto value){const auto* p=reinterpret_cast<const uint8_t*>(&value);b.insert(b.end(),p,p+sizeof(value));};
 for(char c:std::string("DWGEN001"))b.push_back(uint8_t(c));add(generator.storedVersion());add(generator.worldSeed());add(scene.paletteIdentity);add(uint32_t(edits.size()));
 for(const auto& [p,m]:edits){for(auto v:p)add(int32_t(v));add(m);}add(dw::checksum(b.data(),b.size()));
 auto tmp=file;tmp+=".tmp";{std::ofstream f(tmp,std::ios::binary);f.write(reinterpret_cast<const char*>(b.data()),std::streamsize(b.size()));need(bool(f),"Native journal write");}std::filesystem::rename(tmp,file);
}
void StreamingWorld::restore(const std::filesystem::path& file){
 auto b=read_file(file,20*1024*1024);need(b.size()>=40&&std::memcmp(b.data(),"DWGEN001",8)==0,"Native journal header");size_t at=8;
 auto u32=[&](){need(at+4<=b.size(),"Journal bounds");uint32_t v;std::memcpy(&v,b.data()+at,4);at+=4;return v;};
 auto u64=[&](){need(at+8<=b.size(),"Journal bounds");uint64_t v;std::memcpy(&v,b.data()+at,8);at+=8;return v;};
 uint32_t version=u32();need(version>=1&&version<=Generator::version&&u64()==generator.worldSeed()&&u64()==scene.paletteIdentity,"Native generator identity");uint32_t count=u32();need(count<=1048576&&b.size()==40+size_t(count)*16,"Journal count");
 std::map<std::array<int,3>,uint32_t> next;
 for(uint32_t i=0;i<count;i++){int x=std::bit_cast<int32_t>(u32()),y=std::bit_cast<int32_t>(u32()),z=std::bit_cast<int32_t>(u32());uint32_t m=u32();need(x>=-32768&&x<32768&&z>=-32768&&z<32768&&y>=48&&y<160&&m<scene.materials,"Journal edit bounds");need(next.emplace(std::array<int,3>{x,y,z},m).second,"Duplicate journal edit");}
 uint64_t expected=u64();need(expected==dw::checksum(b.data(),b.size()-8),"Journal integrity");generator.useVersion(version);edits=std::move(next);initial=true;
}
}
