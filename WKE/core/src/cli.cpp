// Autonomous verification and preparation benchmark; this executable does not start a JVM.
#include "world.hpp"
#include <chrono>
#include <cstring>
#include <iostream>
#include <numeric>

namespace {
struct Owner {void* value=nullptr;~Owner(){if(value)dw_core_destroy(value);}};
void require(int value){if(value!=DW_OK)throw std::runtime_error("core result "+std::to_string(value));}
void wait(void* core,uint64_t ticket) {
    auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(20);
    for(;;){int r=dw_core_poll(core,ticket);if(r!=DW_PENDING){require(r);return;}
        if(std::chrono::steady_clock::now()>deadline)throw std::runtime_error("core deadline");std::this_thread::yield();}
}
void load(Owner& core,uint32_t workers,const char* path) {
    require(dw_core_create(workers,4096,4096,&core.value));
    require(dw_core_load(core.value,reinterpret_cast<const uint8_t*>(path),uint32_t(std::strlen(path))));
}
struct Section {int32_t x,y,z;uint64_t revision;std::array<uint32_t,DW_CELLS> cells;};
std::vector<Section> sections(void* core) {
    auto count=dw_core_stat(core,DW_SECTIONS);std::vector<int32_t> xyz(count*3);std::vector<uint64_t> rev(count);
    require(dw_core_copy_index(core,xyz.data(),rev.data(),uint32_t(count)));
    std::vector<Section> out(count);
    for(size_t i=0;i<count;++i) {
        out[i].x=xyz[i*3];out[i].y=xyz[i*3+1];out[i].z=xyz[i*3+2];
        require(dw_core_copy_section(core,out[i].x,out[i].y,out[i].z,out[i].cells.data(),DW_CELLS,&out[i].revision));
    }
    return out;
}
uint64_t prepare(void* core,const std::vector<Section>& list) {
    std::vector<uint64_t> tickets;tickets.reserve(list.size());
    for(auto& s:list){uint64_t id;require(dw_core_prepare(core,s.x,s.y,s.z,s.revision,0,&id));tickets.push_back(id);}
    for(auto id:tickets){wait(core,id);require(dw_core_release(core,id));}
    uint64_t result=0;
    for(auto& s:list) {
        std::array<uint8_t,DW_PREPARED_BYTES> bytes{};
        require(dw_core_copy_prepared(core,s.x,s.y,s.z,s.revision,bytes.data(),bytes.size()));
        result^=dw::checksum(bytes.data(),bytes.size())+uint64_t(uint32_t(s.x))*31+uint64_t(uint32_t(s.z));
    }
    return result;
}
}
int main(int argc,char** argv) {
    try {
        if(argc!=3)throw std::runtime_error("usage: dw_core_tool inspect|benchmark world.dwv");
        if(std::string(argv[1])=="inspect") {
            Owner core;load(core,1,argv[2]);auto list=sections(core.value);auto hash=prepare(core.value,list);
            std::cout<<"{\"completed\":true,\"sections\":"<<list.size()<<",\"materials\":"<<dw_core_stat(core.value,DW_MATERIALS)
                <<",\"occupancyHash\":\""<<std::hex<<hash<<std::dec<<"\",\"jvm\":false}\n";return 0;
        }
        if(std::string(argv[1])!="benchmark")throw std::runtime_error("unknown command");
        uint64_t expected=0;bool first=true;std::cout<<"{\"completed\":true,\"jvm\":false,\"runs\":[";
        for(unsigned workers:{1u,2u,4u,8u}) {
            Owner core;load(core,workers,argv[2]);auto list=sections(core.value);
            if(list.empty())throw std::runtime_error("empty benchmark world");
            std::vector<double> times;
            for(int iteration=0;iteration<8;++iteration) {
                auto start=std::chrono::steady_clock::now();
                for(auto& s:list){++s.revision;require(dw_core_put(core.value,s.x,s.y,s.z,s.revision,s.cells.data(),DW_CELLS));}
                auto hash=prepare(core.value,list);
                double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
                if(first&&iteration==0)expected=hash;if(hash!=expected)throw std::runtime_error("worker-count output mismatch");
                if(iteration>0)times.push_back(ms);
            }
            std::sort(times.begin(),times.end());
            if(!first)std::cout<<",";first=false;
            std::cout<<"{\"workers\":"<<workers<<",\"sections\":"<<list.size()<<",\"medianMs\":"<<times[times.size()/2]
                <<",\"minMs\":"<<times.front()<<",\"maxMs\":"<<times.back()<<",\"peakRunning\":"<<dw_core_stat(core.value,DW_PEAK_RUNNING)
                <<",\"snapshotLogicalBytes\":"<<dw_core_stat(core.value,DW_SNAPSHOT_BYTES)<<"}";
        }
        std::cout<<"],\"scope\":\"put, queue, prepare, publish and result readback; excludes file import/load and process startup; not FPS\"}\n";return 0;
    }catch(const std::exception& e){std::cerr<<"CORE_TOOL_FAILED: "<<e.what()<<"\n";return 1;}
}
