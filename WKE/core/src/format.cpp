// DWV v1 is deterministic little-endian, bounded and checksummed. It is a geometry snapshot.
#include "world.hpp"
#include <chrono>
#include <fstream>
#include <set>
#include <system_error>

namespace dw {
namespace {
constexpr uint64_t MAX_FILE=256ull*1024*1024;
void u32(std::vector<uint8_t>& bytes,uint32_t value){for(int i=0;i<4;++i)bytes.push_back(uint8_t(value>>(8*i)));}
void u64(std::vector<uint8_t>& bytes,uint64_t value){for(int i=0;i<8;++i)bytes.push_back(uint8_t(value>>(8*i)));}
struct Reader {
    const std::vector<uint8_t>& data;size_t at=0;
    void need(size_t bytes) const {if(at>data.size()||bytes>data.size()-at)throw std::runtime_error("truncated DWV");}
    uint32_t u32(){need(4);uint32_t n=0;for(int i=0;i<4;++i)n|=uint32_t(data[at++])<<(8*i);return n;}
    uint64_t u64(){need(8);uint64_t n=0;for(int i=0;i<8;++i)n|=uint64_t(data[at++])<<(8*i);return n;}
    std::string text(size_t length){need(length);std::string s(reinterpret_cast<const char*>(data.data()+at),length);at+=length;return s;}
};
}
int Core::save(const std::filesystem::path& path) {
    std::vector<Material> palette;std::vector<std::pair<Key,std::shared_ptr<const Snapshot>>> snapshots;
    {
        std::lock_guard lock(mutex);
        palette=materials;snapshots.reserve(sections.size());
        for(auto& pair:sections)snapshots.emplace_back(pair.first,pair.second.snapshot);
    }
    std::vector<uint8_t> payload;payload.reserve(snapshots.size()*(24+DW_CELLS*4)+4096);
    uint32_t material_count=0;
    for(size_t id=0;id<palette.size();++id)if(!palette[id].name.empty()) {
        ++material_count;u32(payload,uint32_t(id));u32(payload,palette[id].flags);
        u32(payload,uint32_t(palette[id].name.size()));payload.insert(payload.end(),palette[id].name.begin(),palette[id].name.end());
    }
    for(auto& pair:snapshots) {
        u32(payload,uint32_t(pair.first.x));u32(payload,uint32_t(pair.first.y));u32(payload,uint32_t(pair.first.z));
        u64(payload,pair.second->revision);for(auto id:pair.second->cells)u32(payload,id);
    }
    if(payload.size()>MAX_FILE-40)return DW_LIMIT;
    std::vector<uint8_t> header={'D','W','V','O','X','E','L',0};
    u32(header,1);u32(header,DW_SIDE);u32(header,material_count);u32(header,uint32_t(snapshots.size()));
    u64(header,payload.size());u64(header,checksum(payload.data(),payload.size()));
    std::filesystem::path staging;
    try {
        if(path.filename().empty()||std::filesystem::exists(path))return DW_IO;
        auto parent=path.parent_path();if(parent.empty())parent=".";
        std::filesystem::create_directories(parent);
        static std::atomic<uint64_t> sequence{0};
        for(int attempt=0;attempt<32;++attempt) {
            staging=parent/(".dwcore-stage-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count())+"-"+std::to_string(sequence.fetch_add(1)));
            if(std::filesystem::create_directory(staging))break;
            staging.clear();
        }
        if(staging.empty())return DW_IO;
        auto temporary=staging/"world.dwv";
        {
            std::ofstream output(temporary,std::ios::binary|std::ios::trunc);
            output.write(reinterpret_cast<const char*>(header.data()),std::streamsize(header.size()));
            output.write(reinterpret_cast<const char*>(payload.data()),std::streamsize(payload.size()));
            output.flush();if(!output)throw std::runtime_error("DWV write failed");
        }
        // Link only a fully written file, atomically and without replacing an existing destination.
        // Unsupported filesystems fail explicitly; no partial-output or overwrite fallback.
        std::filesystem::create_hard_link(temporary,path);
        std::error_code cleanup;std::filesystem::remove(temporary,cleanup);std::filesystem::remove(staging,cleanup);return DW_OK;
    }catch(...) {
        if(!staging.empty()){std::error_code error;std::filesystem::remove(staging/"world.dwv",error);std::filesystem::remove(staging,error);}
        return DW_IO;
    }
}
int Core::load(const std::filesystem::path& path) {
    std::vector<uint8_t> data;
    try {
        std::ifstream input(path,std::ios::binary|std::ios::ate);if(!input)return DW_IO;
        auto size=input.tellg();if(size<40||uint64_t(size)>MAX_FILE)return DW_CORRUPT;
        data.resize(size_t(size));input.seekg(0);input.read(reinterpret_cast<char*>(data.data()),std::streamsize(data.size()));
        if(!input)return DW_IO;
    }catch(...){return DW_IO;}
    try {
        Reader reader{data};
        if(reader.text(8)!=std::string("DWVOXEL\0",8)||reader.u32()!=1||reader.u32()!=DW_SIDE)return DW_CORRUPT;
        uint32_t material_count=reader.u32(),section_count=reader.u32();
        uint64_t bytes=reader.u64(),expected=reader.u64();
        if(material_count<1||material_count>65536||section_count>max_sections||bytes!=data.size()-40
                ||checksum(data.data()+40,data.size()-40)!=expected)return DW_CORRUPT;
        std::vector<Material> palette;uint64_t names=0;
        std::set<std::string> unique_names;
        for(uint32_t i=0;i<material_count;++i) {
            uint32_t id=reader.u32(),flags=reader.u32(),length=reader.u32();
            if(id>65535||flags>DW_MATERIAL_AIR||length==0||length>4096||names+length>8*1024*1024)return DW_CORRUPT;
            auto name=reader.text(length);
            if(name.find('\0')!=std::string::npos||!unique_names.insert(name).second)return DW_CORRUPT;
            if(palette.size()<=id)palette.resize(id+1);
            if(!palette[id].name.empty())return DW_CORRUPT;
            palette[id]={flags,std::move(name)};names+=length;
        }
        if(palette.empty()||palette[0].name!="minecraft:air"||palette[0].flags!=DW_MATERIAL_AIR)return DW_CORRUPT;
        std::map<Key,Entry> incoming;
        for(uint32_t i=0;i<section_count;++i) {
            Key key{int32_t(reader.u32()),int32_t(reader.u32()),int32_t(reader.u32())};uint64_t revision=reader.u64();
            if(revision==0||incoming.contains(key))return DW_CORRUPT;
            std::array<uint32_t,DW_CELLS> cells;
            for(auto& id:cells){id=reader.u32();if(id>=palette.size()||palette[id].name.empty())return DW_CORRUPT;}
            incoming.emplace(key,Entry{std::make_shared<Snapshot>(revision,cells.data(),memory),{}});
        }
        if(reader.at!=data.size())return DW_CORRUPT;
        std::lock_guard lock(mutex);
        if(!sections.empty()||materials.size()!=1)return DW_BUSY;
        sections=std::move(incoming);materials=std::move(palette);material_bytes=names;air_flags.reset();return DW_OK;
    }catch(...){return DW_CORRUPT;}
}
}
