#include "scene.hpp"
#include <cmath>
#include <cstring>
#include <thread>
#include <sstream>
#include <iomanip>

namespace dw::client {
static void require(bool test,const char* reason){if(!test)throw std::runtime_error(reason);}

void Scene::installColumns(const std::vector<SceneColumn>& columns,int loX,int loZ){
 require(settled(),"Streaming cannot replace sections while preparation is live");
 int nx=int(world[4]),ny=int(world[5]),nz=int(world[6]),loY=std::bit_cast<int32_t>(world[1]);
 int oldX=std::bit_cast<int32_t>(world[0]),oldZ=std::bit_cast<int32_t>(world[2]);
 require(loX>=-2048&&loX+nx<=2048&&loZ>=-2048&&loZ+nz<=2048,"Native world coordinate limit");
 auto* c=static_cast<Core*>(owner);std::vector<uint32_t> free;
 for(auto it=slots.begin();it!=slots.end();){const Key& k=it->first;
  if(k.x<loX||k.x>=loX+nx||k.z<loZ||k.z>=loZ+nz){free.push_back(it->second);{std::lock_guard lock(c->mutex);c->sections.erase(k);}it=slots.erase(it);}else ++it;
 }
 sourceVariants.clear();++epoch;
 for(const auto& col:columns){
  require(col.x>=loX&&col.x<loX+nx&&col.z>=loZ&&col.z<loZ+nz&&col.sections.size()==size_t(ny),"Generated column ownership");
  for(const auto& [key,cells]:col.sections){
   require(key.x==col.x&&key.z==col.z&&key.y>=loY&&key.y<loY+ny,"Generated section ownership");
   uint32_t slot;auto it=slots.find(key);
   if(it==slots.end()){require(!free.empty(),"Streaming slot budget");slot=free.back();free.pop_back();slots[key]=slot;keys[slot]=key;}else slot=it->second;
   uint64_t revision=1;{std::lock_guard lock(c->mutex);auto old=c->sections.find(key);if(old!=c->sections.end())revision=old->second.snapshot->revision+1;}
   require(c->put(key,revision,cells.data(),4096)==DW_OK,"Generated section publication");
   std::copy(cells.begin(),cells.end(),world.begin()+world[7]+size_t(slot)*4096);
   for(int y=key.y-1;y<=key.y+1;y++)for(int z=key.z-1;z<=key.z+1;z++)for(int x=key.x-1;x<=key.x+1;x++){auto s=slots.find({x,y,z});if(s!=slots.end())changed.push_back(s->second);}
  }
 }
 require(free.empty()&&slots.size()==sections,"Resident section budget mismatch");
 world[0]=std::bit_cast<uint32_t>(loX);world[2]=std::bit_cast<uint32_t>(loZ);std::fill(world.begin()+16,world.begin()+world[7],UINT32_MAX);
 for(const auto& [k,slot]:slots){size_t index=size_t(k.x-loX)+size_t(nx)*(size_t(k.z-loZ)+size_t(nz)*size_t(k.y-loY));world[16+index]=slot;
  if((loX>oldX&&k.x==loX)||(loX<oldX&&k.x==loX+nx-1)||(loZ>oldZ&&k.z==loZ)||(loZ<oldZ&&k.z==loZ+nz-1))changed.push_back(slot);
 }
 adaptNativeLogs();metadataDirty=pending=true;
}

std::vector<uint8_t> read_file(const std::filesystem::path& p,size_t maximum){
    std::ifstream f(p,std::ios::binary|std::ios::ate);if(!f)throw std::runtime_error("Cannot open input file");
    auto length=f.tellg();require(length>=0&&uint64_t(length)<=maximum,"Input file limit");
    std::vector<uint8_t> b(static_cast<size_t>(length));f.seekg(0);if(!f.read(reinterpret_cast<char*>(b.data()),b.size()))throw std::runtime_error("Input read failed");return b;
}
static void core_ok(int r){if(r!=DW_OK)throw std::runtime_error("Core error "+std::to_string(r));}
static uint32_t u32(const std::vector<uint8_t>& b,size_t p){uint32_t v;std::memcpy(&v,b.data()+p,4);return v;}
static uint64_t u64(const std::vector<uint8_t>& b,size_t p){uint64_t v;std::memcpy(&v,b.data()+p,8);return v;}
static float fp(uint32_t x){return std::bit_cast<float>(x);}
static V3 v3(const std::vector<uint32_t>& v,size_t p){return{fp(v[p]),fp(v[p+1]),fp(v[p+2])};}
Scene::Scene(const std::filesystem::path& snapshot,const std::filesystem::path& shapes,bool variants,bool tracing):tracingMasks(tracing){
    try {
        auto bytes=read_file(snapshot,256*1024*1024);require(bytes.size()>=40&&u32(bytes,16)<=65536,"World palette header");world_hash=checksum(bytes.data(),bytes.size());
        auto a=read_file(shapes,256*1024*1024);require(a.size()>=96,"Asset minimum size");
        uint32_t version=u32(a,8);size_t paletteEnd=40;
        for(uint32_t i=0;i<u32(bytes,16);++i){require(paletteEnd+12<=bytes.size(),"Palette bounds");paletteEnd+=12+u32(bytes,paletteEnd+8);require(paletteEnd<=bytes.size(),"Palette name bounds");}
        uint64_t identity=version==2?checksum(bytes.data()+40,paletteEnd-40):world_hash;paletteIdentity=checksum(bytes.data()+40,paletteEnd-40);
        require(a.size()>=96&&std::memcmp(a.data(),"DWASSET\0",8)==0&&(version==1||version==2),"Asset header");
        require(uint64_t(u32(a,12))*4==a.size()-32&&u64(a,16)==identity&&u64(a,24)==checksum(a.data()+32,a.size()-32),"Asset identity/integrity");
        assets.resize((a.size()-32)/4);std::memcpy(assets.data(),a.data()+32,a.size()-32);
        auto range=[&](uint32_t at,uint64_t count){require(at>=16&&uint64_t(at)+count<=assets.size(),"Asset table bounds");};
        require(assets[0]>0&&assets[0]<=65536&&assets[11]<=assets[0],"Shape count");
        range(assets[1],uint64_t(assets[0])*8);range(assets[2],uint64_t(assets[6])*12);
        range(assets[3],uint64_t(assets[7])*24);range(assets[4],uint64_t(assets[8])*4);
        range(assets[9],uint64_t(assets[10])*5);
        require((assets[14]==0&&assets[15]==0)||(version==2&&assets[14]!=0&&assets[15]==1),"Indexed BVH feature marker");
        if(assets[14])range(assets[14],assets[7]);
        std::vector<uint8_t> references(assets[14]?assets[7]:0,0);
        triangle_starts.resize(assets[0]);uint64_t total=0;
        for(uint32_t i=0;i<assets[0];++i){
            size_t d=assets[1]+i*8;triangle_starts[i]=uint32_t(total);total+=assets[d+2];
            require(total<=assets[7]&&assets[d+3]<assets[11],"Shape triangle/material bounds");
            if(assets[14])for(uint64_t j=triangle_starts[i];j<total;++j){
                uint32_t ref=assets[assets[14]+size_t(j)];
                require(ref>=triangle_starts[i]&&ref<total,"Indexed BVH reference ownership");
                require(references[ref]++==0,"Indexed BVH duplicate triangle");
            }
            if(assets[d]!=UINT32_MAX){
                require(assets[d]<assets[d+1]&&assets[d+1]<=assets[6],"Shape root bounds");
                for(uint32_t j=assets[d];j<assets[d+1];++j){
                    size_t n=assets[2]+j*12;
                    require(assets[n+8]>j&&assets[n+8]<=assets[d+1],"BVH escapes its shape");
                    if(assets[n+7])require(assets[n+3]>=triangle_starts[i]&&uint64_t(assets[n+3])+assets[n+7]<=total,"BVH leaf escapes its shape");
                }
            }else require(assets[d+2]==0,"Empty shape has triangles");
        }
        require(total==assets[7],"Unowned triangles");
        for(uint32_t i=0;i<assets[6];++i){
            size_t n=assets[2]+i*12;require(assets[n+8]>i&&assets[n+8]<=assets[6]&&assets[n+7]<=8,"BVH escape/leaf");
            require(uint64_t(assets[n+3])+assets[n+7]<=assets[7],"BVH leaf bounds");
            auto lo=v3(assets,n),hi=v3(assets,n+4);
            require(std::isfinite(lo.x)&&std::isfinite(lo.y)&&std::isfinite(lo.z)&&std::isfinite(hi.x)&&std::isfinite(hi.y)&&std::isfinite(hi.z)&&lo.x<=hi.x&&lo.y<=hi.y&&lo.z<=hi.z,"BVH finite bounds");
        }
        for(uint32_t i=0;i<assets[8];++i){size_t t=assets[4]+i*4;require(assets[t+1]>0&&assets[t+1]<=4096&&assets[t+2]>0&&assets[t+2]<=4096,"Texture extent");range(assets[5],uint64_t(assets[t])+uint64_t(assets[t+1])*assets[t+2]);}
        for(uint32_t i=0;i<assets[7];++i){
            size_t t=assets[3]+i*24;require(assets[t+3]<assets[8],"Triangle texture");
            for(uint32_t j=0;j<24;++j)if(j!=3&&j!=7&&j!=11)require(std::isfinite(fp(assets[t+j])),"Triangle finite data");
        }
        core_ok(dw_core_create(std::clamp(std::thread::hardware_concurrency(),1u,4u),4096,4096,&owner));
        auto path=snapshot.u8string();core_ok(dw_core_load(owner,reinterpret_cast<const uint8_t*>(path.data()),uint32_t(path.size())));
        auto* core=static_cast<Core*>(owner);materials=uint32_t(dw_core_stat(owner,DW_MATERIALS));sections=uint32_t(dw_core_stat(owner,DW_SECTIONS));
        require(sections>0&&materials==assets[11],"Scene palette/section mismatch");
        std::vector<uint64_t> tickets; tickets.reserve(sections);
        if(tracingMasks)for(auto& [key,e]:core->sections){uint64_t t=0;core_ok(dw_core_prepare(owner,key.x,key.y,key.z,e.snapshot->revision,0,&t));tickets.push_back(t);}
        auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(10);
        for(auto t:tickets){int r;while((r=dw_core_poll(owner,t))==DW_PENDING){require(std::chrono::steady_clock::now()<deadline,"Native preparation timeout");std::this_thread::yield();}core_ok(r);core_ok(dw_core_release(owner,t));}
        workers=uint32_t(dw_core_stat(owner,DW_WORKERS));peak_workers=uint32_t(dw_core_stat(owner,DW_PEAK_RUNNING));
        Key lo=core->sections.begin()->first,hi=lo;
        for(auto& [k,e]:core->sections){require(k.x>=-2048&&k.x<2048&&k.y>=-2048&&k.y<2048&&k.z>=-2048&&k.z<2048,"Viewer coordinate limit");lo={std::min(lo.x,k.x),std::min(lo.y,k.y),std::min(lo.z,k.z)};hi={std::max(hi.x,k.x),std::max(hi.y,k.y),std::max(hi.z,k.z)};}
        uint64_t dx=int64_t(hi.x)-lo.x+1,dy=int64_t(hi.y)-lo.y+1,dz=int64_t(hi.z)-lo.z+1;
        require(dx<=1024&&dy<=1024&&dz<=1024&&dx*dy*dz<=1048576,"Dense section-index budget");
        world.resize(16+size_t(dx*dy*dz),UINT32_MAX);
        std::fill_n(world.begin(),16,0);world[0]=std::bit_cast<uint32_t>(lo.x);world[1]=std::bit_cast<uint32_t>(lo.y);world[2]=std::bit_cast<uint32_t>(lo.z);
        world[3]=sections;world[4]=uint32_t(dx);world[5]=uint32_t(dy);world[6]=uint32_t(dz);world[8]=16;
        world[7]=uint32_t(world.size());world.resize(world.size()+size_t(sections)*4096,0);
        world[9]=uint32_t(world.size());if(tracingMasks)world.resize(world.size()+size_t(sections)*147,0);
        world[11]=version==2?assets[12]:1;world[12]=version==2?assets[13]:1;
        require(world[11]>=1&&world[11]<=4&&world[12]==((2*world[11]+1)*(2*world[11]+1)*(2*world[11]+1)+31)/32,"Owner stencil bounds");
        world[10]=uint32_t(world.size());if(tracingMasks)world.resize(world.size()+size_t(sections)*4096*world[12],0);
        uint32_t slot=0;
        for(auto& [k,e]:core->sections){
            slots[k]=slot;keys.push_back(k);
            size_t g=size_t(k.x-lo.x)+dx*(size_t(k.z-lo.z)+dz*size_t(k.y-lo.y));world[16+g]=slot;
            std::copy(e.snapshot->cells.begin(),e.snapshot->cells.end(),world.begin()+world[7]+size_t(slot)*4096);
            if(tracingMasks)std::memcpy(world.data()+world[9]+size_t(slot)*147,e.prepared->occupancy.data(),DW_PREPARED_BYTES);
            ++slot;
        }
        auto locate=[&](int32_t x,int32_t y,int32_t z)->size_t{
            auto floor16=[](int32_t x){int32_t q=x/16;return q-(x<0&&x%16!=0);};
            int32_t sx=floor16(x),sy=floor16(y),sz=floor16(z);
            require(sx>=lo.x&&sy>=lo.y&&sz>=lo.z&&sx<=hi.x&&sy<=hi.y&&sz<=hi.z,"Override outside scene");
            size_t g=size_t(sx-lo.x)+dx*(size_t(sz-lo.z)+dz*size_t(sy-lo.y));auto s=world[16+g];require(s!=UINT32_MAX,"Override missing section");
            return world[7]+size_t(s)*4096+uint32_t(x-sx*16)+16*uint32_t(z-sz*16)+256*uint32_t(y-sy*16);
        };
        for(uint32_t i=0;variants&&i<assets[10];++i){
            size_t o=assets[9]+i*5;uint32_t from=assets[o+3],to=assets[o+4];require(from<materials&&to<assets[0]&&original(to)==from,"Override shape ownership");
            size_t dst=locate(std::bit_cast<int32_t>(assets[o]),std::bit_cast<int32_t>(assets[o+1]),std::bit_cast<int32_t>(assets[o+2]));sourceVariants[dst]={from,to};
            if(version==1)require(world[dst]==from,"Override source mismatch");if(world[dst]==from)world[dst]=to;
        }
        std::vector<uint64_t> maskTickets;
        if(tracingMasks)for(uint32_t s=0;s<sections;++s){uint64_t ticket;core_ok(core->jobs.submit([this,s](const std::atomic<bool>& cancelled){if(cancelled)return Outcome{DW_CANCELLED,{}};rebuildMasks(world,s);return Outcome{};},0,ticket));maskTickets.push_back(ticket);}
        for(auto ticket:maskTickets){int result;while((result=core->jobs.poll(ticket))==DW_PENDING)std::this_thread::yield();core_ok(result);core_ok(core->jobs.release(ticket));}
        rebuildInstances();
    }catch(...){if(owner){dw_core_destroy(owner);owner=nullptr;}throw;}
}
Params parameters(const Camera& c,uint32_t w,uint32_t h,bool record){
    V3 f{std::sin(c.yaw)*std::cos(c.pitch),std::sin(c.pitch),std::cos(c.yaw)*std::cos(c.pitch)};
    V3 r{std::cos(c.yaw),0,-std::sin(c.yaw)},u=cross(f,r);
    return{{c.position.x,c.position.y,c.position.z,.700207538f},{f.x,f.y,f.z,float(w)/float(h)},
           {r.x,r.y,r.z,float(w)},{u.x,u.y,u.z,float(h)},{uint32_t(record),w,h,1024}};
}
V3 direction(const Params& p,uint32_t x,uint32_t y){
    float a=(2*(float(x)+.5f)/p.right[3]-1)*p.forward[3]*p.origin[3],b=(1-2*(float(y)+.5f)/p.up[3])*p.origin[3];
    return normalized(V3{p.forward[0],p.forward[1],p.forward[2]}+V3{p.right[0],p.right[1],p.right[2]}*a+V3{p.up[0],p.up[1],p.up[2]}*b);
}
static bool box(V3 o,V3 d,V3 lo,V3 hi,float limit){
    float near=0,far=limit;float oo[]={o.x,o.y,o.z},dd[]={d.x,d.y,d.z},ll[]={lo.x,lo.y,lo.z},hh[]={hi.x,hi.y,hi.z};
    for(int i=0;i<3;++i){if(std::abs(dd[i])<1e-20f){if(oo[i]<ll[i]||oo[i]>hh[i])return false;}else{float a=(ll[i]-oo[i])/dd[i],b=(hh[i]-oo[i])/dd[i];near=std::max(near,std::min(a,b));far=std::min(far,std::max(a,b));}}
    return far>=near;
}
std::string Scene::diagnoseRay(V3 origin,V3 ray,float target,uint32_t material)const{std::ostringstream out;out<<std::setprecision(12)<<"{\"origin\":["<<origin.x<<','<<origin.y<<','<<origin.z<<"],\"ray\":["<<ray.x<<','<<ray.y<<','<<ray.z<<"],\"candidates\":[";int found=0;for(const auto& instance:instances){uint32_t shape=instance.shape;if(original(shape)!=material)continue;V3 o=origin-instance.origin;size_t root=assets[2]+assets[assets[1]+shape*8]*12;if(!box(o,ray,v3(assets,root),v3(assets,root+4),target+.02f))continue;uint32_t count=assets[assets[1]+shape*8+2];for(uint32_t i=0;i<count;i++){size_t t=assets[3]+size_t(triangle_starts[shape]+i)*24;V3 p=v3(assets,t),e1=v3(assets,t+4),e2=v3(assets,t+8),q=cross(ray,e2),v=o-p;float det=dot(e1,q);if(std::abs(det)<1e-9f)continue;float inv=1/det,u=dot(v,q)*inv;V3 crossV=cross(v,e1);float b=dot(ray,crossV)*inv,distance=dot(e2,crossV)*inv;if(u<-.001f||b<-.001f||u+b>1.001f||std::abs(distance-target)>.02f)continue;float tx=fp(assets[t+12])+fp(assets[t+14])*u+fp(assets[t+16])*b,ty=fp(assets[t+13])+fp(assets[t+15])*u+fp(assets[t+17])*b;size_t td=assets[4]+assets[t+3]*4;uint32_t w=assets[td+1],h=assets[td+2];float sx=std::clamp(tx,0.f,.999999f)*w,sy=std::clamp(ty,0.f,.999999f)*h;uint32_t alpha=assets[assets[5]+assets[td]+uint32_t(sx)+w*uint32_t(sy)]>>24;V3 sourceNormal=v3(assets,t+20);if(found++)out<<',';out<<"{\"shape\":"<<shape<<",\"triangle\":"<<t<<",\"instanceOrigin\":["<<instance.origin.x<<','<<instance.origin.y<<','<<instance.origin.z<<"],\"p\":["<<p.x<<','<<p.y<<','<<p.z<<"],\"e1\":["<<e1.x<<','<<e1.y<<','<<e1.z<<"],\"e2\":["<<e2.x<<','<<e2.y<<','<<e2.z<<"],\"sourceNormal\":["<<sourceNormal.x<<','<<sourceNormal.y<<','<<sourceNormal.z<<"],\"distance\":"<<distance<<",\"u\":"<<u<<",\"v\":"<<b<<",\"sx\":"<<sx<<",\"sy\":"<<sy<<",\"alpha\":"<<alpha<<",\"flags\":"<<assets[t+7]<<",\"tint\":"<<assets[t+11]<<'}';if(found==12)break;}if(found==12)break;}out<<"]}";return out.str();}
Hit Scene::reference(V3 origin,V3 ray,uint32_t onlyMaterial,float maximumDistance)const{
    Hit best;best.distance=maximumDistance;float uncertain=std::numeric_limits<float>::infinity();
    for(size_t slot=0;slot<instances.sectionCount();++slot){
        const auto& section=instances.sectionAt(slot);
        // Bounded gameplay rays visit only intersecting section bounds. The
        // four-cell halo includes every supported overhanging source shape.
        // Unbounded independent oracle calls retain their original cell order.
        if(std::isfinite(maximumDistance)){
            V3 lo{float(section.key.x*16-4),float(section.key.y*16-4),float(section.key.z*16-4)};
            if(!box(origin,ray,lo,lo+V3{24,24,24},maximumDistance))continue;
        }
        for(size_t index=0;index<section.shapes.size();++index){
        auto instance=section.at(index);
        uint32_t shape=instance.shape;
        if(onlyMaterial!=UINT32_MAX&&original(shape)!=onlyMaterial)continue;
        size_t root=assets[2]+assets[assets[1]+shape*8]*12;
        V3 o=origin-instance.origin;if(!box(o,ray,v3(assets,root),v3(assets,root+4),best.distance+surfaceTieEpsilon))continue;uint32_t count=assets[assets[1]+shape*8+2];
        for(uint32_t i=0;i<count;++i){
            size_t t=assets[3]+size_t(triangle_starts[shape]+i)*24;
            V3 p=v3(assets,t),e1=v3(assets,t+4),e2=v3(assets,t+8),q=cross(ray,e2);float det=dot(e1,q);
            if(std::abs(det)<1e-9f)continue;float inv=1/det;V3 s=o-p;float u=dot(s,q)*inv;if(u<-.002f||u>1.002f)continue;
            V3 r=cross(s,e1);float v=dot(ray,r)*inv;if(v<-.002f||u+v>1.002f)continue;float distance=dot(e2,r)*inv;
            // A float ray grazing a silhouette is not a numerically stable oracle sample.
            // Convert a world-space roundoff envelope through triangle altitudes, including
            // just-outside candidates; the renderer's hit rules and normal checks stay strict.
            // An interior barycentric point can still lie inside this world-space
            // envelope on a thin triangle. Do not gate it by a fixed barycentric ratio.
            if(distance>1e-4f){V3 areaVector=cross(e1,e2);float area=std::sqrt(dot(areaVector,areaVector));float rounding=8*std::numeric_limits<float>::epsilon()*(std::sqrt(dot(origin,origin))+std::sqrt(dot(o,o))+std::abs(distance)+1);float a=std::abs(u)*area/std::max(std::sqrt(dot(e2,e2)),1e-12f),b=std::abs(v)*area/std::max(std::sqrt(dot(e1,e1)),1e-12f);V3 edge=e1-e2;float c=std::abs(1-u-v)*area/std::max(std::sqrt(dot(edge,edge)),1e-12f);if(std::min({a,b,c})<=rounding)uncertain=std::min(uncertain,distance);}
            if(u<0||u>1||v<0||u+v>1)continue;
            if(distance>1e-4f&&(distance<best.distance-surfaceTieEpsilon||(std::abs(distance-best.distance)<=surfaceTieEpsilon&&original(shape)<best.material))){
                if(assets[t+7]&8u){
                    float tx=fp(assets[t+12])+fp(assets[t+14])*u+fp(assets[t+16])*v;
                    float ty=fp(assets[t+13])+fp(assets[t+15])*u+fp(assets[t+17])*v;
                    size_t td=assets[4]+assets[t+3]*4;uint32_t w=assets[td+1],h=assets[td+2];
                    float sx=std::clamp(tx,0.f,.999999f)*w,sy=std::clamp(ty,0.f,.999999f)*h;
                    if(std::abs(sx-std::round(sx))<.002f||std::abs(sy-std::round(sy))<.002f)uncertain=std::min(uncertain,distance);
                    uint32_t x=uint32_t(sx),y=uint32_t(sy);
                    if((assets[assets[5]+assets[td]+x+w*y]>>24)<128)continue;
                }
                V3 n=v3(assets,t+20);if(dot(n,ray)>0)n=n*-1;
                best={original(shape),distance,instance.origin,n};
                best.triangle=uint32_t(t);
            }
        }
        }
    }best.ambiguous=std::isfinite(uncertain)&&uncertain<=best.distance+.002f;return best;
}
}

namespace dw::client {
struct Scene::Batch {
 uint64_t version;std::shared_ptr<std::vector<uint32_t>> source;
 std::vector<uint64_t> tickets;std::vector<uint32_t> slots;
};
Scene::~Scene(){if(owner){auto* c=static_cast<Core*>(owner);c->jobs.stop();batch.reset();dw_core_destroy(owner);}}
size_t Scene::location(int x,int y,int z)const{
 auto f=[](int v){int q=v/16;return q-(v<0&&v%16!=0);};Key k{f(x),f(y),f(z)};
 auto it=slots.find(k);if(it==slots.end())return SIZE_MAX;
 return world[7]+size_t(it->second)*4096+uint32_t(x-k.x*16)+16*uint32_t(z-k.z*16)+256*uint32_t(y-k.y*16);
}
uint32_t Scene::cell(int x,int y,int z)const{size_t at=location(x,y,z);return at==SIZE_MAX?UINT32_MAX:original(world[at]);}
std::string Scene::materialName(uint32_t id)const{return id<materials?static_cast<Core*>(owner)->materials[id].name:"";}
void Scene::prepareNativeLogs(){
 nativeLogRadius.assign(materials,0);nativeLogGround.assign(materials,0);
 nativeGroundSnow.assign(materials,0);nativeLogShapes.assign(materials,{});
 nativeLogContexts.assign(materials,{});
 auto vertex=[&](size_t at){return v3(assets,at);};
 for(uint32_t m=0;m<materials;++m){
  auto name=materialName(m);
  // These terrain types support a root at the next whole-cell boundary.
  // Bottom slabs and shallow snow do not: their visible top is below that boundary.
  nativeLogGround[m]=name=="minecraft:stone"||name=="minecraft:dirt"
   ||name.starts_with("minecraft:grass_block[")||name=="minecraft:snow_block";
  if(name=="minecraft:snow_block")nativeGroundSnow[m]=8;
  else if(name.starts_with("minecraft:snow[")){
   auto at=name.find("layers=");if(at!=std::string::npos&&at+7<name.size()
    &&name[at+7]>='1'&&name[at+7]<='8')nativeGroundSnow[m]=uint8_t(name[at+7]-'0');
  }else if(name.starts_with("darkwinter:test_snowy_grass_slab[")
    &&name.find("type=bottom")!=std::string::npos
    &&name.find("snowy=true")!=std::string::npos
    &&name.find("waterlogged=true")==std::string::npos)nativeGroundSnow[m]=5;
  if(!name.starts_with("darkwinter:test_spruce_log[")||name.find("axis=y")==std::string::npos)continue;
  for(int r=1;r<=5;++r)if(name.find("radius="+std::to_string(r))!=std::string::npos)nativeLogRadius[m]=uint8_t(r);
  if(!nativeLogRadius[m])continue;
  uint32_t bark=UINT32_MAX,count=assets[assets[1]+m*8+2];
  for(uint32_t j=0;j<count;++j){
   size_t t=assets[3]+size_t(triangle_starts[m]+j)*24;
   V3 a=vertex(t),b=a+vertex(t+4),c=a+vertex(t+8);
   if(std::abs(vertex(t+20).y)<.1f&&std::max({a.y,b.y,c.y})-std::min({a.y,b.y,c.y})>.5f){bark=assets[t+3];break;}
  }
  require(bark!=UINT32_MAX,"Native log bark template");
  std::array<std::array<float,3>,6> scores;
  for(auto& row:scores)row.fill(std::numeric_limits<float>::infinity());
  for(auto& row:nativeLogShapes[m])row.fill(UINT32_MAX);
  for(uint32_t shape=0;shape<assets[0];++shape){
   size_t d=assets[1]+size_t(shape)*8;
   if(assets[d+3]!=m||assets[d]==UINT32_MAX)continue;
   if(assets[d+6]&0x80000000u)nativeLogContexts[m].emplace_back(assets[d+6],shape);
   // Packer metadata identifies authored wood ends and root-snow geometry.
   // This remains render-only: core materials/save IDs are unchanged.
   uint32_t endgrain=assets[d+4]?assets[d+4]-1:UINT32_MAX,classification=assets[d+5];
   bool collar=classification==1,bottomCap=false,crown=false;float topRadius=0;
   for(uint32_t j=0;j<assets[d+2];++j){
    size_t t=assets[3]+size_t(triangle_starts[shape]+j)*24;
    V3 a=vertex(t),b=a+vertex(t+4),c=a+vertex(t+8),n=vertex(t+20);
    float low=std::min({a.y,b.y,c.y}),high=std::max({a.y,b.y,c.y});
    bool wood=assets[t+3]==bark||assets[t+3]==endgrain;
    if(!wood){
     // Legacy packages have no classification. Only a flat, upward ground rim
     // counts as a collar; a crown's sloping needle cards cannot qualify.
     if(!classification&&n.y>.99f&&low>.0001f&&high<=1.f&&high-low<.0001f)collar=true;
     if(high>1.f)crown=true;
     continue;
    }
    if(n.y<-.9f&&high<.0001f)bottomCap=true;
    for(auto p:{a,b,c})if(p.y>.9999f&&p.y<1.0001f)
     topRadius=std::max({topRadius,std::abs(p.x-.5f),std::abs(p.z-.5f)});
   }
   for(int above=0;above<=5;++above)for(int context=0;context<3;++context){
    if(collar!=(context==2))continue; // Never substitute a snowy root for a hanging cut.
    float expected=above?(above==1?.065f:above*.06f):nativeLogRadius[m]==1?.025f:nativeLogRadius[m]*.06f*.84f;
    float score=std::abs(topRadius-expected)*1000+(bottomCap!=(context!=0)?20.f:0.f);
    if(above==0&&nativeLogRadius[m]==1&&!crown)score+=10;
    if(score<scores[above][context]){scores[above][context]=score;nativeLogShapes[m][above][context]=shape;}
   }
  }
  std::sort(nativeLogContexts[m].begin(),nativeLogContexts[m].end());
  for(int above=0;above<=5;++above){
   auto& row=nativeLogShapes[m][above];
   // Old packages may lack clean bottom-capped variants. A bare shape is still
   // preferable to inventing snow in mid-air. New packages export closed cuts.
   if(row[0]==UINT32_MAX)row[0]=row[1];
   if(row[1]==UINT32_MAX)row[1]=row[0];
   require(row[1]!=UINT32_MAX,"Native log requires a bare shape");
   if(row[2]==UINT32_MAX)row[2]=row[1];
  }
 }
}
void Scene::adaptNativeLogs(){
 if(nativeLogRadius.empty()||changed.empty())return;
 // Geometry keys contain actual join height and participating cardinal edges.
 // No snow context is inferred from a diagonal neighbour or an arbitrary capture.
 std::sort(changed.begin(),changed.end());changed.erase(std::unique(changed.begin(),changed.end()),changed.end());
 constexpr int neighbors[4][2]{{0,-1},{1,0},{0,1},{-1,0}};
 for(uint32_t slot:changed)if(slot<sections){auto key=keys[slot];
  for(uint32_t i=0;i<4096;++i){
   size_t at=world[7]+size_t(slot)*4096+i;uint32_t m=original(world[at]);
   if(m>=nativeLogRadius.size()||!nativeLogRadius[m])continue;
   int x=key.x*16+int(i&15),y=key.y*16+int(i>>8),z=key.z*16+int((i>>4)&15);
   uint32_t up=cell(x,y+1,z),down=cell(x,y-1,z);
   uint32_t above=up<nativeLogRadius.size()?nativeLogRadius[up]:0;
   bool closedBottom=!(down<nativeLogRadius.size()&&nativeLogRadius[down]);
   bool crown=above==0&&(nativeNaturalLeader?nativeNaturalLeader(x,y,z,m):nativeLogRadius[m]==1);
   uint32_t layers=0,sides=0;
   if(closedBottom&&down<nativeLogGround.size()&&nativeLogGround[down]){
    uint32_t lowest=8;
    for(uint32_t side=0;side<4;++side){
     uint32_t cover=cell(x+neighbors[side][0],y,z+neighbors[side][1]);
     uint32_t height=cover<nativeGroundSnow.size()?nativeGroundSnow[cover]:0;
     if(height){lowest=std::min(lowest,height);sides|=1u<<side;}
    }
    if(sides)layers=lowest;
   }
   const auto& contexts=nativeLogContexts[m];
   if(!contexts.empty()){
    auto choose=[&](uint32_t context){
     auto found=std::lower_bound(contexts.begin(),contexts.end(),context,
      [](const auto& item,uint32_t value){return item.first<value;});
     return found!=contexts.end()&&found->first==context?found->second:UINT32_MAX;
    };
    uint32_t base=0x80000000u|above|(closedBottom?8u:0u)|(crown?4096u:0u);
    uint32_t selected=choose(base|(layers<<4)|(sides<<8));
    // A mismatched/older package may lack one snow context. Fall back to bare
    // wood, never to an unrelated snow height that could cross the trunk.
    if(selected==UINT32_MAX)selected=choose(base);
    if(selected==UINT32_MAX&&crown)selected=choose(base&~4096u);
    if(selected!=UINT32_MAX){world[at]=selected;continue;}
   }
   world[at]=nativeLogShapes[m][above][closedBottom?1:0];
  }
 }
}
void Scene::rebuildInstances(){
 instances.reset(sections);
 for(uint32_t slot=0;slot<sections;++slot)
  instances.replace(slot,keys[slot],std::span(world.data()+world[7]+size_t(slot)*4096,4096),assets);
}
void Scene::rebuildInstances(std::span<const uint32_t> dirty){
 if(instances.sectionCount()!=sections){rebuildInstances();return;}
 for(uint32_t slot:dirty)if(slot<sections)
  instances.replace(slot,keys[slot],std::span(world.data()+world[7]+size_t(slot)*4096,4096),assets);
}
void Scene::rebuildMasks(std::vector<uint32_t>& data,uint32_t slot)const{
 auto k=keys[slot];auto* occupancy=reinterpret_cast<uint8_t*>(data.data()+data[9]+size_t(slot)*147);std::fill_n(occupancy,588,0);
 uint32_t words=data[12];int radius=int(data[11]),side=radius*2+1;
 auto* masks=data.data()+data[10]+size_t(slot)*4096*words;std::fill_n(masks,4096*words,0u);
 // Stamp actual shape bounds into the touched render cells. Solid cubes cost one stamp.
 for(int sz=k.z-1;sz<=k.z+1;++sz)for(int sy=k.y-1;sy<=k.y+1;++sy)for(int sx=k.x-1;sx<=k.x+1;++sx){
  auto it=slots.find({sx,sy,sz});if(it==slots.end())continue;
  for(int i=0;i<4096;++i){
   int x=sx*16+(i&15),y=sy*16+(i>>8),z=sz*16+((i>>4)&15);auto shape=data[data[7]+size_t(it->second)*4096+i];auto desc=assets[1]+shape*8;
   if(assets[desc]==UINT32_MAX)continue;auto root=assets[2]+assets[desc]*12;auto lo=v3(assets,root),hi=v3(assets,root+4);
   int x0=std::max(k.x*16,x+int(std::floor(lo.x+.00001f))),x1=std::min(k.x*16+15,x+int(std::floor(hi.x-.00001f)));
   int y0=std::max(k.y*16,y+int(std::floor(lo.y+.00001f))),y1=std::min(k.y*16+15,y+int(std::floor(hi.y-.00001f)));
   int z0=std::max(k.z*16,z+int(std::floor(lo.z+.00001f))),z1=std::min(k.z*16+15,z+int(std::floor(hi.z-.00001f)));
   for(int cy=y0;cy<=y1;++cy)for(int cz=z0;cz<=z1;++cz)for(int cx=x0;cx<=x1;++cx){
    int dx=x-cx,dy=y-cy,dz=z-cz;require(std::abs(dx)<=radius&&std::abs(dy)<=radius&&std::abs(dz)<=radius,"Owner shape bound exceeds declared stencil");
    int bit=(dx+radius)+side*(dz+radius)+side*side*(dy+radius),index=(cx-k.x*16)+16*(cz-k.z*16)+256*(cy-k.y*16);
    masks[index*words+bit/32]|=1u<<(bit%32);
   }
  }
 }
 constexpr int offsets[]={0,512,576,584,585};
 for(int i=0;i<4096;++i){bool any=false;for(uint32_t j=0;j<words;++j)any|=masks[i*words+j]!=0;
  if(any)for(int level=0;level<5;++level){int n=16>>level,j=((i&15)>>level)+n*(((i>>4)&15)>>level)+n*n*((i>>8)>>level);occupancy[offsets[level]+(j>>3)]|=uint8_t(1u<<(j&7));}
 }
}
bool Scene::edit(int x,int y,int z,uint32_t material){
 size_t at=location(x,y,z);if(at==SIZE_MAX||material>=materials)return false;
 auto* c=static_cast<Core*>(owner);auto f=[](int v){int q=v/16;return q-(v<0&&v%16!=0);};Key key{f(x),f(y),f(z)};
 std::shared_ptr<const Snapshot> old;{std::lock_guard lock(c->mutex);old=c->sections.at(key).snapshot;}
 if(old->cells[(x-key.x*16)+16*(z-key.z*16)+256*(y-key.y*16)]==material)return false;
 auto cells=old->cells;cells[(x-key.x*16)+16*(z-key.z*16)+256*(y-key.y*16)]=material;
 core_ok(c->put(key,old->revision+1,cells.data(),4096));world[at]=material;auto variant=sourceVariants.find(at);if(variant!=sourceVariants.end()&&variant->second.first==material)world[at]=variant->second.second;++epoch;++edits;pending=true;
 // Include neighboring sections for overhanging models and hierarchy occupancy.
 int r=int(world[11]);for(int sy=f(y-r);sy<=f(y+r);++sy)for(int sz=f(z-r);sz<=f(z+r);++sz)for(int sx=f(x-r);sx<=f(x+r);++sx){auto it=slots.find({sx,sy,sz});if(it!=slots.end())changed.push_back(it->second);}
 adaptNativeLogs();
 if(onEdit)onEdit(x,y,z,material);
 return true;
}
uint32_t Scene::activeJobs()const{return batch?uint32_t(batch->tickets.size()):0;}
std::vector<uint32_t> Scene::pump(bool flush){
 std::vector<uint32_t> committed;auto* core=static_cast<Core*>(owner);
 if(!tracingMasks){
  // Raster geometry and collision consume cells/instances, not ray-traversal
  // occupancy. Publish the dirty components on their owning thread, without
  // duplicating the entire resident window or scheduling unused mask jobs.
  if(!pending)return committed;
  std::sort(changed.begin(),changed.end());changed.erase(std::unique(changed.begin(),changed.end()),changed.end());
  committed=std::move(changed);changed.clear();rebuildInstances(committed);
  pending=false;++committedBatches;
  if(metadataDirty){committed.push_back(UINT32_MAX);metadataDirty=false;}
  return committed;
 }
 do{
  if(batch){
   bool ready=true;for(auto t:batch->tickets)if(core->jobs.poll(t)==DW_PENDING)ready=false;
   if(ready){
    for(auto t:batch->tickets){core_ok(core->jobs.poll(t));core_ok(core->jobs.release(t));}
    if(batch->version==epoch){
     for(auto s:batch->slots){std::copy_n(batch->source->data()+world[7]+size_t(s)*4096,4096,world.data()+world[7]+size_t(s)*4096);std::copy_n(batch->source->data()+world[9]+size_t(s)*147,147,world.data()+world[9]+size_t(s)*147);std::copy_n(batch->source->data()+world[10]+size_t(s)*4096*world[12],4096*world[12],world.data()+world[10]+size_t(s)*4096*world[12]);}
     committed=batch->slots;++committedBatches;changed.clear();rebuildInstances(committed);
    }else ++staleBatches;
    batch.reset();
   }
  }
  if(!batch&&pending){
   std::sort(changed.begin(),changed.end());changed.erase(std::unique(changed.begin(),changed.end()),changed.end());
   batch=std::make_unique<Batch>();batch->version=epoch;batch->source=std::make_shared<std::vector<uint32_t>>(world);batch->slots=changed;pending=false;
   for(auto slot:batch->slots){uint64_t ticket;auto source=batch->source;core_ok(core->jobs.submit([this,source,slot](const std::atomic<bool>& cancelled){if(cancelled)return Outcome{DW_CANCELLED,{}};rebuildMasks(*source,slot);return Outcome{};},0,ticket));batch->tickets.push_back(ticket);}
  }
  if(flush&&batch)std::this_thread::yield();
 }while(flush&&(batch||pending));
 if(!committed.empty()&&metadataDirty){committed.push_back(UINT32_MAX);metadataDirty=false;}
 return committed;
}
bool Scene::settled()const{return !batch&&!pending;}
void Scene::save(const std::filesystem::path& path){auto p=path.u8string();core_ok(dw_core_save(owner,reinterpret_cast<const uint8_t*>(p.data()),uint32_t(p.size())));}
uint64_t Scene::logicalHash()const{
 auto* c=static_cast<Core*>(owner);std::lock_guard lock(c->mutex);uint64_t h=14695981039346656037ull;
 for(auto& [k,e]:c->sections)for(auto v:e.snapshot->cells)for(int j=0;j<4;++j)h=(h^uint8_t(v>>(j*8)))*1099511628211ull;return h;
}
}
