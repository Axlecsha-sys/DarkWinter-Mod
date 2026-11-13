#pragma once
#include "../../core/src/world.hpp"
#include <bit>
#include <cmath>
#include <chrono>
#include <fstream>
#include <limits>
#include <span>
#include <functional>

namespace dw::client {
struct V3 {float x=0,y=0,z=0;V3 operator+(V3 b)const{return{x+b.x,y+b.y,z+b.z};}V3 operator-(V3 b)const{return{x-b.x,y-b.y,z-b.z};}V3 operator*(float s)const{return{x*s,y*s,z*s};}};
inline float dot(V3 a,V3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
inline V3 cross(V3 a,V3 b){return{a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
inline V3 normalized(V3 a){return a*(1/std::sqrt(dot(a,a)));}
struct Camera {V3 position{5.5f,83.5f,-7};float yaw=0,pitch=-.18f;};
struct alignas(16) Params {float origin[4],forward[4],right[4],up[4];uint32_t settings[4];float actor[4]{},pose[4]{},avatar[4]{};};
static_assert(sizeof(Params)==128);
Params parameters(const Camera& c,uint32_t w,uint32_t h,bool record);
V3 direction(const Params& p,uint32_t x,uint32_t y);
std::vector<uint8_t> read_file(const std::filesystem::path& p,size_t maximum);
struct Hit {uint32_t material=0xffffffff;float distance=std::numeric_limits<float>::infinity();V3 cell{},normal{};bool ambiguous=false;uint32_t triangle=UINT32_MAX;};
struct Instance {V3 origin;uint32_t shape;};
// Section-local render components. The hot shape/cell streams do not carry three
// repeated world coordinates, core ownership, strings or per-cell heap objects.
class InstanceTable {
public:
    struct Section {
        Key key{};
        std::vector<uint16_t> cells;
        std::vector<uint32_t> shapes;
        Instance at(size_t index)const {
            uint32_t cell=cells[index];
            return {{float(key.x*16+int(cell&15)),float(key.y*16+int(cell>>8)),
                     float(key.z*16+int((cell>>4)&15))},shapes[index]};
        }
    };
private:
    std::vector<Section> sections;
    size_t count=0;
public:
    void clear(){sections.clear();count=0;}
    void reset(size_t size){clear();sections.resize(size);}
    size_t size()const{return count;}
    bool empty()const{return count==0;}
    size_t sectionCount()const{return sections.size();}
    const Section& sectionAt(size_t slot)const{return sections[slot];}
    void replace(size_t slot,Key key,std::span<const uint32_t> source,
                 const std::vector<uint32_t>& assets) {
        auto& section=sections[slot];count-=section.shapes.size();section.key=key;
        section.cells.clear();section.shapes.clear();
        for(uint32_t cell=0;cell<source.size();++cell) {
            uint32_t shape=source[cell];
            if(assets[assets[1]+size_t(shape)*8]==UINT32_MAX)continue;
            section.cells.push_back(uint16_t(cell));section.shapes.push_back(shape);
        }
        count+=section.shapes.size();
    }
    // Oracle/debug traversal preserves the old slot/cell order without rebuilding
    // a second flattened array whenever a single section changes.
    class Iterator {
        const InstanceTable* table=nullptr;
        size_t slot=0,index=0;
        void skip(){while(slot<table->sections.size()&&index==table->sections[slot].shapes.size()){++slot;index=0;}}
    public:
        Iterator(const InstanceTable* value,size_t section):table(value),slot(section){skip();}
        Instance operator*()const{return table->sections[slot].at(index);}
        Iterator& operator++(){++index;skip();return *this;}
        bool operator==(const Iterator& other)const{return table==other.table&&slot==other.slot&&index==other.index;}
    };
    Iterator begin()const{return {this,0};}
    Iterator end()const{return {this,sections.size()};}
};
// CPU section publication is independent of the host terrain generator.
struct SceneColumn {
 int x=0,z=0;
 std::vector<std::pair<Key,std::array<uint32_t,4096>>> sections;
};
class Scene {
    void* owner=nullptr;
    struct Batch;
    std::unique_ptr<Batch> batch;
    std::map<Key,uint32_t> slots;
    std::map<size_t,std::pair<uint32_t,uint32_t>> sourceVariants;
    std::vector<Key> keys;
    std::vector<uint32_t> changed;
    uint64_t epoch=0;
    bool pending=false,metadataDirty=false,tracingMasks=true;
    // Legacy context fallback; new packages supply exact taper/snow/crown keys.
    std::vector<uint8_t> nativeLogRadius,nativeLogGround,nativeGroundSnow;
    std::vector<std::array<std::array<uint32_t,3>,6>> nativeLogShapes;
    std::vector<std::vector<std::pair<uint32_t,uint32_t>>> nativeLogContexts;
    void prepareNativeLogs();void adaptNativeLogs();
    void rebuildInstances();
    void rebuildInstances(std::span<const uint32_t> slots);
    void rebuildMasks(std::vector<uint32_t>& data,uint32_t slot)const;
    size_t location(int x,int y,int z)const;
public:
    std::vector<uint32_t> world,assets,triangle_starts;
    InstanceTable instances;
    uint64_t paletteIdentity=0,world_hash=0;uint32_t sections=0,materials=0,workers=0,peak_workers=0;
    Scene(const std::filesystem::path& snapshot,const std::filesystem::path& shapes,bool variants=true,bool tracing=true);
    ~Scene();
    Scene(const Scene&)=delete;Scene& operator=(const Scene&)=delete;
    std::function<void(int,int,int,uint32_t)> onEdit;
    // Pure generator query: distinguish original living tips from player-cut stumps.
    std::function<bool(int,int,int,uint32_t)> nativeNaturalLeader;
    bool settled()const;
    void prepareNative(int diameter=13);
    void installColumns(const std::vector<SceneColumn>& columns,int loX,int loZ);
    uint64_t edits=0,staleBatches=0,committedBatches=0;uint32_t activeJobs()const;
    std::vector<uint32_t> pump(bool flush=false);
    bool edit(int x,int y,int z,uint32_t material);
    uint32_t cell(int x,int y,int z)const;
    std::string materialName(uint32_t id)const;
    void save(const std::filesystem::path& path);
    uint64_t logicalHash()const;
    std::string diagnoseRay(V3 origin,V3 ray,float target,uint32_t material)const;
    static constexpr float surfaceTieEpsilon=.00005f;
    // Default: canonical nearest hit. A filter independently checks one material;
    // callers must compare it to the unchanged baseline before accepting a tie.
    Hit reference(V3 origin,V3 ray,uint32_t onlyMaterial=UINT32_MAX,float maximumDistance=std::numeric_limits<float>::infinity())const;
    uint32_t original(uint32_t shape)const{return assets[assets[1]+shape*8+3];}
};
}
