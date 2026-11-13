#include "world.hpp"
#include <cstring>
#include <limits>

namespace dw {
int Core::define(uint32_t id,uint32_t flags,const uint8_t* name,uint32_t bytes) {
    if(id>65535||flags>DW_MATERIAL_AIR||!name||bytes<1||bytes>4096) return DW_INVALID;
    if(std::memchr(name,0,bytes)) return DW_INVALID;
    std::string value(reinterpret_cast<const char*>(name),bytes);
    std::lock_guard lock(mutex);
    if(id<materials.size()&&!materials[id].name.empty())
        return materials[id].name==value&&materials[id].flags==flags?DW_OK:DW_INVALID;
    for(auto& material:materials) if(material.name==value) return DW_INVALID;
    if(material_bytes+bytes>8*1024*1024) return DW_LIMIT;
    if(materials.size()<=id) materials.resize(id+1);
    materials[id]={flags,std::move(value)};material_bytes+=bytes;air_flags.reset();return DW_OK;
}
int Core::put(Key key,uint64_t revision,const uint32_t* input,uint32_t count) {
    if(!input||count!=DW_CELLS||revision==0) return DW_INVALID;
    std::lock_guard lock(mutex);
    auto found=sections.find(key);
    if(found!=sections.end()&&revision<=found->second.snapshot->revision) return DW_STALE;
    if(found==sections.end()&&sections.size()>=max_sections) return DW_LIMIT;
    for(uint32_t i=0;i<DW_CELLS;++i)
        if(input[i]>=materials.size()||materials[input[i]].name.empty()) return DW_INVALID;
    auto snapshot=std::make_shared<Snapshot>(revision,input,memory);
    sections[key]={std::move(snapshot),{}};return DW_OK;
}
int Core::prepare(Key key,uint64_t revision,uint64_t dependency,uint64_t& ticket) {
    std::shared_ptr<const Snapshot> snapshot;
    // A small immutable material-flag table is shared by this task, never by reference to mutable registries.
    std::shared_ptr<const std::vector<uint8_t>> air;
    {
        std::lock_guard lock(mutex);
        auto found=sections.find(key);if(found==sections.end()) return DW_NOT_FOUND;
        snapshot=found->second.snapshot;if(snapshot->revision!=revision) return DW_STALE;
        if(!air_flags) {
            auto flags=std::make_shared<std::vector<uint8_t>>(materials.size());
            for(size_t i=0;i<materials.size();++i) (*flags)[i]=(materials[i].flags&DW_MATERIAL_AIR)!=0;
            air_flags=std::move(flags);
        }
        air=air_flags;
    }
    // No world lock is held while taking the scheduler lock.
    return jobs.submit([this,key,snapshot,air](const std::atomic<bool>& cancel)->Outcome {
        auto result=std::make_shared<Prepared>();result->revision=snapshot->revision;
        auto bit=[&](size_t offset,size_t index){result->occupancy[offset+index/8]|=uint8_t(1u<<(index%8));};
        for(uint32_t y=0;y<16;++y) {
            if(cancel.load(std::memory_order_relaxed)) return {DW_CANCELLED,{}};
            for(uint32_t z=0;z<16;++z) for(uint32_t x=0;x<16;++x) {
                uint32_t index=x+16*z+256*y;
                if((*air)[snapshot->cells[index]]) continue;
                bit(0,index);
                bit(512,(x>>1)+8*(z>>1)+64*(y>>1));
                bit(576,(x>>2)+4*(z>>2)+16*(y>>2));
                bit(584,(x>>3)+2*(z>>3)+4*(y>>3));
                result->occupancy[585]=1;
            }
        }
        return {DW_OK,[this,key,snapshot,result] {
            std::lock_guard lock(mutex);
            auto found=sections.find(key);
            if(found==sections.end()||found->second.snapshot!=snapshot) {++stale;return DW_STALE;}
            found->second.prepared=result;++published;return DW_OK;
        }};
    },dependency,ticket);
}
uint64_t checksum(const uint8_t* data,size_t bytes) {
    uint64_t hash=14695981039346656037ull;
    for(size_t i=0;i<bytes;++i) {hash^=data[i];hash*=1099511628211ull;}
    return hash;
}
}
namespace {
template<class F> int32_t guard(F&& operation) noexcept {
    try{return operation();}catch(const std::invalid_argument&){return DW_INVALID;}catch(...){return DW_INTERNAL;}
}
dw::Core* core(void* value){return static_cast<dw::Core*>(value);}
std::filesystem::path utf8_path(const uint8_t* path,uint32_t bytes) {
    std::u8string text(bytes,char8_t{});std::memcpy(text.data(),path,bytes);return std::filesystem::path(text);
}
bool valid_path(const uint8_t* path,uint32_t bytes) {
    return path&&bytes>0&&bytes<=32768&&!std::memchr(path,0,bytes);
}
}
extern "C" {
uint32_t dw_core_abi_version(){return 1;}
int32_t dw_core_create(uint32_t workers,uint32_t capacity,uint32_t maximum,void** out) {
    if(!out)return DW_INVALID;*out=nullptr;
    if(workers<1||workers>32||capacity<1||capacity>4096||maximum<1||maximum>4096)return DW_INVALID;
    return guard([&]{*out=new dw::Core(workers,capacity,maximum);return DW_OK;});
}
int32_t dw_core_destroy(void* value) {if(!value)return DW_INVALID;return guard([&]{delete core(value);return DW_OK;});}
int32_t dw_core_define_material(void* value,uint32_t id,uint32_t flags,const uint8_t* name,uint32_t bytes) {
    if(!value)return DW_INVALID;return guard([&]{return core(value)->define(id,flags,name,bytes);});
}
int32_t dw_core_put(void* value,int32_t x,int32_t y,int32_t z,uint64_t revision,const uint32_t* cells,uint32_t count) {
    if(!value)return DW_INVALID;return guard([&]{return core(value)->put({x,y,z},revision,cells,count);});
}
int32_t dw_core_prepare(void* value,int32_t x,int32_t y,int32_t z,uint64_t revision,uint64_t dependency,uint64_t* ticket) {
    if(!value||!ticket)return DW_INVALID;*ticket=0;
    return guard([&]{return core(value)->prepare({x,y,z},revision,dependency,*ticket);});
}
int32_t dw_core_poll(void* value,uint64_t ticket) {if(!value)return DW_INVALID;return guard([&]{return core(value)->jobs.poll(ticket);});}
int32_t dw_core_cancel(void* value,uint64_t ticket) {if(!value)return DW_INVALID;return guard([&]{return core(value)->jobs.cancel(ticket);});}
int32_t dw_core_release(void* value,uint64_t ticket) {if(!value)return DW_INVALID;return guard([&]{return core(value)->jobs.release(ticket);});}
int32_t dw_core_copy_section(void* value,int32_t x,int32_t y,int32_t z,uint32_t* out,uint32_t count,uint64_t* revision) {
    if(!value||!out||count<DW_CELLS||!revision)return DW_INVALID;
    return guard([&]{
        auto* c=core(value);std::lock_guard lock(c->mutex);
        auto found=c->sections.find({x,y,z});if(found==c->sections.end())return DW_NOT_FOUND;
        std::copy(found->second.snapshot->cells.begin(),found->second.snapshot->cells.end(),out);
        *revision=found->second.snapshot->revision;return DW_OK;
    });
}
int32_t dw_core_copy_prepared(void* value,int32_t x,int32_t y,int32_t z,uint64_t revision,uint8_t* out,uint32_t bytes) {
    if(!value||!out||bytes<DW_PREPARED_BYTES)return DW_INVALID;
    return guard([&]{
        auto* c=core(value);std::lock_guard lock(c->mutex);
        auto found=c->sections.find({x,y,z});if(found==c->sections.end())return DW_NOT_FOUND;
        if(found->second.snapshot->revision!=revision)return DW_STALE;
        auto prepared=found->second.prepared;
        if(!prepared)return DW_PENDING;if(prepared->revision!=revision)return DW_STALE;
        std::copy(prepared->occupancy.begin(),prepared->occupancy.end(),out);return DW_OK;
    });
}
int32_t dw_core_copy_index(void* value,int32_t* xyz,uint64_t* revisions,uint32_t capacity) {
    if(!value)return DW_INVALID;
    return guard([&]{
        auto* c=core(value);std::lock_guard lock(c->mutex);
        if(capacity<c->sections.size()||(!c->sections.empty()&&(!xyz||!revisions)))return DW_INVALID;
        size_t i=0;for(auto& pair:c->sections) {
            xyz[3*i]=pair.first.x;xyz[3*i+1]=pair.first.y;xyz[3*i+2]=pair.first.z;
            revisions[i++]=pair.second.snapshot->revision;
        }
        return DW_OK;
    });
}
int32_t dw_core_save(void* value,const uint8_t* path,uint32_t bytes) {
    if(!value||!valid_path(path,bytes))return DW_INVALID;
    return guard([&]{return core(value)->save(utf8_path(path,bytes));});
}
int32_t dw_core_load(void* value,const uint8_t* path,uint32_t bytes) {
    if(!value||!valid_path(path,bytes))return DW_INVALID;
    return guard([&]{return core(value)->load(utf8_path(path,bytes));});
}
uint64_t dw_core_stat(void* value,uint32_t field) {
    if(!value)return UINT64_MAX;
    try {
        auto* c=core(value);
        if(field<=DW_PEAK_RUNNING) {
            auto m=c->jobs.metrics();uint64_t values[]={m.workers,m.queued,m.running,m.handles,m.peak};return values[field];
        }
        std::lock_guard lock(c->mutex);
        switch(field) {
            case DW_SECTIONS:return c->sections.size();
            case DW_MATERIALS:{uint64_t n=0;for(auto& m:c->materials)if(!m.name.empty())++n;return n;}
            case DW_SNAPSHOT_BYTES:return c->memory->snapshots.load();
            case DW_PREPARED_PAYLOAD_BYTES:{uint64_t n=0;for(auto& e:c->sections)if(e.second.prepared)n+=sizeof(dw::Prepared);return n;}
            case DW_PUBLISHED:return c->published;
            case DW_STALE_RESULTS:return c->stale;
            default:return UINT64_MAX;
        }
    }catch(...){return UINT64_MAX;}
}
}
