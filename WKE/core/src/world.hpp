// Immutable section snapshots and copy-on-replace revisions. Mutable ownership stays in Core.
#pragma once
#include "jobs.hpp"
#include <array>
#include <filesystem>
#include <string>
#include <tuple>

namespace dw {
struct Key {
    int32_t x,y,z;
    bool operator<(const Key& b) const {return std::tie(x,y,z)<std::tie(b.x,b.y,b.z);}
};
struct Material {uint32_t flags=0;std::string name;};
struct Memory {std::atomic<uint64_t> snapshots{0};};
struct Snapshot {
    uint64_t revision;
    std::array<uint32_t,DW_CELLS> cells;
    std::shared_ptr<Memory> memory;
    Snapshot(uint64_t r,const uint32_t* input,std::shared_ptr<Memory> m):revision(r),memory(std::move(m)) {
        std::copy_n(input,DW_CELLS,cells.begin());memory->snapshots.fetch_add(sizeof(Snapshot));
    }
    ~Snapshot(){memory->snapshots.fetch_sub(sizeof(Snapshot));}
};
struct Prepared {uint64_t revision=0;std::array<uint8_t,DW_PREPARED_BYTES> occupancy{};};
struct Entry {std::shared_ptr<const Snapshot> snapshot;std::shared_ptr<const Prepared> prepared;};
class Core {
public:
    mutable std::mutex mutex;
    std::map<Key,Entry> sections;
    std::vector<Material> materials{{DW_MATERIAL_AIR,"minecraft:air"}};
    std::shared_ptr<Memory> memory=std::make_shared<Memory>();
    std::shared_ptr<const std::vector<uint8_t>> air_flags;
    uint32_t max_sections;
    uint64_t material_bytes=13,published=0,stale=0;
    Jobs jobs;
    Core(uint32_t workers,uint32_t capacity,uint32_t maximum):max_sections(maximum),jobs(workers,capacity) {
        if(maximum<1||maximum>4096) throw std::invalid_argument("section limit");
    }
    ~Core(){jobs.stop();}
    int define(uint32_t id,uint32_t flags,const uint8_t* name,uint32_t bytes);
    int put(Key key,uint64_t revision,const uint32_t* input,uint32_t count);
    int prepare(Key key,uint64_t revision,uint64_t dependency,uint64_t& ticket);
    int save(const std::filesystem::path& path);
    int load(const std::filesystem::path& path);
};
uint64_t checksum(const uint8_t* data,size_t bytes);
}
