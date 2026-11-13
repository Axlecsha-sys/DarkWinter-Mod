#include "../../src/world.hpp"
#include <chrono>
#include <cstring>
#include <fstream>
#include <iostream>
#include <random>
#include <sstream>

using namespace std::chrono_literals;
namespace {
uint64_t assertions=0;
void check(bool condition,const char* message){++assertions;if(!condition)throw std::runtime_error(message);}
void ok(int result){check(result==DW_OK,"expected DW_OK");}
struct Owner {
    void* value=nullptr;
    Owner(uint32_t workers=4,uint32_t capacity=128,uint32_t sections=128){ok(dw_core_create(workers,capacity,sections,&value));}
    ~Owner(){if(value)dw_core_destroy(value);}
};
void material(void* core,uint32_t id,uint32_t flags,const char* name) {
    ok(dw_core_define_material(core,id,flags,reinterpret_cast<const uint8_t*>(name),uint32_t(std::strlen(name))));
}
int await(void* core,uint64_t ticket) {
    auto end=std::chrono::steady_clock::now()+5s;
    for(;;){int r=dw_core_poll(core,ticket);if(r!=DW_PENDING)return r;
        if(std::chrono::steady_clock::now()>end)throw std::runtime_error("job deadline");std::this_thread::yield();}
}
struct Gate {
    std::mutex mutex;std::condition_variable condition;int entered=0;bool opened=false;
    bool enter(const std::atomic<bool>& cancelled) {
        std::unique_lock lock(mutex);++entered;condition.notify_all();
        while(!opened&&!cancelled.load())condition.wait_for(lock,2ms);
        return !cancelled.load();
    }
    void wait(int count){std::unique_lock lock(mutex);check(condition.wait_for(lock,3s,[&]{return entered>=count;}),"workers did not enter barrier");}
    void open(){std::lock_guard lock(mutex);opened=true;condition.notify_all();}
};
void scheduling() {
    Gate gate;dw::Jobs jobs(3,4);std::atomic<int> committed{0};
    uint64_t a,b,c;
    auto work=[&](const std::atomic<bool>& stop)->dw::Outcome {
        if(!gate.enter(stop))return {DW_CANCELLED,{}};
        return {DW_OK,[&]{++committed;return DW_OK;}};
    };
    ok(jobs.submit(work,0,a));ok(jobs.submit(work,0,b));gate.wait(2);
    ok(jobs.submit([&](const std::atomic<bool>&)->dw::Outcome{return {DW_OK,[&]{++committed;return DW_OK;}};},a,c));
    ok(jobs.cancel(a));gate.open();
    auto until=std::chrono::steady_clock::now()+3s;
    while((jobs.poll(a)==DW_PENDING||jobs.poll(b)==DW_PENDING||jobs.poll(c)==DW_PENDING)
            &&std::chrono::steady_clock::now()<until)std::this_thread::yield();
    check(jobs.poll(a)==DW_CANCELLED,"running cancellation");check(jobs.poll(b)==DW_OK,"independent completion");
    check(jobs.poll(c)==DW_DEPENDENCY,"failed dependency propagation");check(committed==1,"cancelled work must not publish");
    check(jobs.metrics().peak>=2,"native workers must actually overlap");
    ok(jobs.release(a));ok(jobs.release(b));ok(jobs.release(c));check(jobs.metrics().handles==0,"released job records");
    Gate blocked;dw::Jobs bounded(1,2);uint64_t hold,waiting,ready,overflow;
    ok(bounded.submit([&](const std::atomic<bool>& stop)->dw::Outcome{blocked.enter(stop);return {};},0,hold));blocked.wait(1);
    ok(bounded.submit([](const std::atomic<bool>&)->dw::Outcome{return {};},hold,waiting));
    ok(bounded.submit([](const std::atomic<bool>&)->dw::Outcome{return {};},0,ready));
    check(bounded.submit([](const std::atomic<bool>&)->dw::Outcome{return {};},0,overflow)==DW_BUSY,"bounded admission");
    ok(bounded.cancel(waiting));ok(bounded.release(waiting));
    // Repeated cancelled dependent submissions must not accumulate parent links or queue tombstones.
    for(int i=0;i<2000;++i){uint64_t id;ok(bounded.submit([](const std::atomic<bool>&)->dw::Outcome{return {};},hold,id));ok(bounded.cancel(id));ok(bounded.release(id));}
    check(bounded.metrics().queued==1&&bounded.metrics().handles==2,"bounded cancellation churn");
    blocked.open();bounded.stop();
}
void occupancy(const std::array<uint32_t,DW_CELLS>& cells,const std::array<uint8_t,DW_PREPARED_BYTES>& data) {
    size_t offset=0;
    for(int side:{16,8,4,2,1}) {
        int scale=16/side;
        for(int y=0;y<side;++y)for(int z=0;z<side;++z)for(int x=0;x<side;++x) {
            bool any=false;
            for(int dy=0;dy<scale&&!any;++dy)for(int dz=0;dz<scale&&!any;++dz)for(int dx=0;dx<scale;++dx) {
                auto id=cells[(x*scale+dx)+16*(z*scale+dz)+256*(y*scale+dy)];
                if(id==1||id==3){any=true;break;}
            }
            size_t index=x+side*z+side*side*y;
            check(bool(data[offset+index/8]&(1u<<(index%8)))==any,"independent occupancy oracle");
        }
        offset+=(size_t(side)*side*side+7)/8;
    }
    check(offset==DW_PREPARED_BYTES,"hierarchy layout");
}
std::vector<uint8_t> bytes(const std::filesystem::path& path){std::ifstream in(path,std::ios::binary);return {std::istreambuf_iterator<char>(in),{}};}
int save(void* c,const std::filesystem::path& p){auto s=p.u8string();return dw_core_save(c,reinterpret_cast<const uint8_t*>(s.data()),uint32_t(s.size()));}
int load(void* c,const std::filesystem::path& p){auto s=p.u8string();return dw_core_load(c,reinterpret_cast<const uint8_t*>(s.data()),uint32_t(s.size()));}
void worlds(const std::filesystem::path& directory) {
    Owner serial(1),parallel(4);
    for(auto c:{serial.value,parallel.value}) {material(c,1,0,"minecraft:stone");material(c,2,DW_MATERIAL_AIR,"minecraft:cave_air");material(c,3,0,"darkwinter:test[prop=snow]");}
    std::mt19937 random(731997);
    std::vector<std::array<uint32_t,DW_CELLS>> input(64);
    std::vector<uint64_t> first,second;
    for(int section=0;section<64;++section) {
        for(auto& id:input[section])id=section==0?0:section==1?1:section==2?2:random()%4;
        for(auto c:{serial.value,parallel.value}) {
            ok(dw_core_put(c,section-32,-4,INT32_MAX,1,input[section].data(),DW_CELLS));
            uint64_t ticket;ok(dw_core_prepare(c,section-32,-4,INT32_MAX,1,0,&ticket));
            (c==serial.value?first:second).push_back(ticket);
        }
    }
    for(int i=0;i<64;++i) {
        ok(await(serial.value,first[i]));ok(await(parallel.value,second[i]));
        std::array<uint8_t,DW_PREPARED_BYTES> a{},b{};
        ok(dw_core_copy_prepared(serial.value,i-32,-4,INT32_MAX,1,a.data(),a.size()));
        ok(dw_core_copy_prepared(parallel.value,i-32,-4,INT32_MAX,1,b.data(),b.size()));
        check(a==b,"serial/parallel bit parity");occupancy(input[i],b);
        ok(dw_core_release(serial.value,first[i]));ok(dw_core_release(parallel.value,second[i]));
    }
    check(dw_core_stat(parallel.value,DW_HANDLES)==0,"completed records released");
    std::filesystem::create_directories(directory);
    auto file=directory/"roundtrip.dwv",again=directory/"again.dwv",broken=directory/"broken.dwv";
    ok(save(parallel.value,file));auto before=bytes(file);
    check(save(parallel.value,file)==DW_IO&&bytes(file)==before,"exclusive save cannot overwrite");
    Owner loaded(3);ok(load(loaded.value,file));ok(save(loaded.value,again));check(bytes(again)==before,"deterministic roundtrip bytes");
    for(int i=0;i<64;++i) {
        std::array<uint32_t,DW_CELLS> out{};uint64_t revision=0;
        ok(dw_core_copy_section(loaded.value,i-32,-4,INT32_MAX,out.data(),out.size(),&revision));
        check(revision==1&&out==input[i],"loaded voxel states and coordinates");
    }
    before.back()^=1;{std::ofstream out(broken,std::ios::binary);out.write(reinterpret_cast<const char*>(before.data()),before.size());}
    Owner corrupt;check(load(corrupt.value,broken)==DW_CORRUPT,"payload corruption rejected");
    check(dw_core_stat(corrupt.value,DW_SECTIONS)==0,"bad file must not partly replace world");ok(load(corrupt.value,file));
    check(load(corrupt.value,file)==DW_BUSY,"loading cannot replace a live world");
}
void revisions() {
    Owner owner(2,8,8);auto* c=static_cast<dw::Core*>(owner.value);material(c,1,0,"minecraft:stone");
    std::array<uint32_t,DW_CELLS> cells{};cells[4095]=1;ok(dw_core_put(c,0,0,0,1,cells.data(),cells.size()));
    Gate gate;uint64_t hold,stale;
    ok(c->jobs.submit([&](const std::atomic<bool>& cancel)->dw::Outcome{gate.enter(cancel);return {};},0,hold));gate.wait(1);
    ok(dw_core_prepare(c,0,0,0,1,hold,&stale));cells[0]=1;ok(dw_core_put(c,0,0,0,2,cells.data(),cells.size()));gate.open();
    std::array<uint8_t,DW_PREPARED_BYTES> old_bytes{};
    check(dw_core_copy_prepared(c,0,0,0,1,old_bytes.data(),old_bytes.size())==DW_STALE,"old read must not look pending");
    check(await(c,stale)==DW_STALE,"old revision cannot publish");
    ok(dw_core_release(c,hold));ok(dw_core_release(c,stale));
    std::array<uint8_t,DW_PREPARED_BYTES> out{};check(dw_core_copy_prepared(c,0,0,0,2,out.data(),out.size())==DW_PENDING,"no stale prepared bytes exposed");
    uint64_t valid;ok(dw_core_prepare(c,0,0,0,2,0,&valid));ok(await(c,valid));ok(dw_core_release(c,valid));
    ok(dw_core_copy_prepared(c,0,0,0,2,out.data(),out.size()));check(out[0]==1&&(out[511]&128),"section corners retained");
    check(dw_core_put(c,0,0,0,1,cells.data(),cells.size())==DW_STALE,"backward revision rejected");
    check(dw_core_put(c,0,0,0,3,cells.data(),4)==DW_INVALID,"wrong input length rejected");
    cells[0]=65535;check(dw_core_put(c,1,0,0,1,cells.data(),cells.size())==DW_INVALID,"undefined material rejected");
    check(dw_core_prepare(c,0,0,0,2,UINT64_MAX,&valid)==DW_NOT_FOUND,"unknown prerequisite rejected");
    check(dw_core_stat(c,DW_STALE_RESULTS)==1,"stale publication recorded");
}
}
int main(int argc,char** argv) {
    try {
        check(argc==2,"owned output directory argument required");
        check(dw_core_abi_version()==1,"ABI version");
        void* invalid=reinterpret_cast<void*>(1);check(dw_core_create(0,2,2,&invalid)==DW_INVALID&&invalid==nullptr,"invalid creation is transactional");
        scheduling();revisions();worlds(std::filesystem::path(argv[1]));
        std::cout<<"{\"completed\":true,\"assertions\":"<<assertions<<",\"scope\":\"native executor, revision ownership, occupancy and DWV format; no JVM or GPU\"}\n";return 0;
    }catch(const std::exception& e){std::cerr<<"CORE_CHECK_FAILED: "<<e.what()<<"\n";return 1;}
}
