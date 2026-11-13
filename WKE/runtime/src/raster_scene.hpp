#pragma once
#include "scene.hpp"
#include <algorithm>
#include <array>
#include <cstddef>
#include <numeric>

namespace dw::client {
// Binding 6: a std430 uvec4. Positions are float bits, not integer coordinates.
struct alignas(16) RasterInstance {
    uint32_t x,y,z,shape;
};
static_assert(sizeof(RasterInstance)==16);

// The first four fields have exactly VkDrawIndirectCommand's layout.
// Bounds include overhanging branches and all other source-model geometry.
struct RasterBatch {
    uint32_t vertexCount=0,instanceCount=0,firstVertex=0,firstInstance=0;
    V3 low{},high{};
    bool buried=false;
};
static_assert(offsetof(RasterBatch,firstInstance)==12);

class RasterScene {
    struct Shape {
        V3 low{},high{};
        uint32_t first=0,count=0,material=0;
        bool solidCube=false;
    };
    struct Pending {
        RasterInstance instance;
        bool buried;
        uint16_t cell;
    };
    const Scene* source=nullptr;
    const uint32_t* sourceAssets=nullptr;
    size_t sourceAssetWords=0;
    std::vector<Shape> shapes;
    struct Section {
        uint32_t first=0,count=0,capacity=0;
        uint64_t revision=0;
        std::vector<RasterBatch> batches;
        V3 low{},high{};
        size_t buried=0;
    };
    struct Range {uint32_t first=0,count=0;uint64_t revision=0;};
    std::vector<Section> sections;
    std::vector<Range> freeRanges,uploadRanges;
    std::vector<Pending> pending;
    std::vector<uint8_t> dirty;
    std::vector<uint32_t> dirtySlots;

    uint32_t allocate(uint32_t count) {
        auto best=freeRanges.end();
        for(auto it=freeRanges.begin();it!=freeRanges.end();++it)
            if(it->count>=count&&(best==freeRanges.end()||it->count<best->count))best=it;
        if(best!=freeRanges.end()) {
            uint32_t first=best->first;best->first+=count;best->count-=count;
            if(!best->count)freeRanges.erase(best);
            return first;
        }
        if(instances.size()+count>UINT32_MAX)throw std::runtime_error("Raster instance arena limit");
        uint32_t first=uint32_t(instances.size());instances.resize(instances.size()+count);return first;
    }
    void release(uint32_t first,uint32_t count) {
        if(!count)return;
        auto it=std::lower_bound(freeRanges.begin(),freeRanges.end(),first,
            [](const Range& range,uint32_t value){return range.first<value;});
        it=freeRanges.insert(it,{first,count,0});
        if(it!=freeRanges.begin()&&(it-1)->first+(it-1)->count==it->first) {
            auto before=it-1;before->count+=it->count;it=freeRanges.erase(it);--it;
        }
        if(it+1!=freeRanges.end()&&it->first+it->count==(it+1)->first) {
            it->count+=(it+1)->count;freeRanges.erase(it+1);
        }
        // Coalescing leaves at most one free suffix. Dropping its logical size
        // keeps later growth from appending beyond an already unused tail.
        if(!freeRanges.empty()
           &&size_t(freeRanges.back().first)+freeRanges.back().count==instances.size()) {
            instances.resize(freeRanges.back().first);freeRanges.pop_back();
        }
    }
    static uint32_t slotAt(const Scene& scene,int sx,int sy,int sz) {
        const auto& w=scene.world;
        int gx=sx-std::bit_cast<int32_t>(w[0]),gy=sy-std::bit_cast<int32_t>(w[1]),
            gz=sz-std::bit_cast<int32_t>(w[2]);
        if(gx<0||gy<0||gz<0||uint32_t(gx)>=w[4]||uint32_t(gy)>=w[5]||uint32_t(gz)>=w[6])
            return UINT32_MAX;
        return w[w[8]+size_t(gx)+size_t(w[4])*(size_t(gz)+size_t(w[6])*size_t(gy))];
    }
    void rebuildSection(const Scene& scene,uint32_t slot) {
        auto& out=sections[slot];const auto& input=scene.instances.sectionAt(slot);
        pending.clear();
        for(size_t i=0;i<input.shapes.size();++i) {
            auto instance=input.at(i);
            if(instance.shape>=shapes.size()||!shapes[instance.shape].count)continue;
            pending.push_back({{std::bit_cast<uint32_t>(instance.origin.x),
                                std::bit_cast<uint32_t>(instance.origin.y),
                                std::bit_cast<uint32_t>(instance.origin.z),instance.shape},
                               buriedCube(scene,instance),input.cells[i]});
        }
        // Explicit cell order replaces stable_sort's temporary heap allocation.
        // It keeps the original section/cell order within each shape/buried group.
        std::sort(pending.begin(),pending.end(),[](const Pending& a,const Pending& b) {
            if(a.instance.shape!=b.instance.shape)return a.instance.shape<b.instance.shape;
            if(a.buried!=b.buried)return a.buried<b.buried;
            return a.cell<b.cell;
        });
        uint32_t count=uint32_t(pending.size());
        if(count>out.capacity) {
            uint32_t capacity=std::bit_ceil(count),first=allocate(capacity);
            release(out.first,out.capacity);out.first=first;out.capacity=capacity;
        }
        out.count=count;out.revision=revision;out.batches.clear();out.buried=0;
        size_t begin=0;bool firstBounds=true;
        while(begin<pending.size()) {
            size_t end=begin+1;uint32_t shape=pending[begin].instance.shape;
            bool buried=pending[begin].buried;
            while(end<pending.size()&&pending[end].instance.shape==shape&&pending[end].buried==buried)++end;
            const auto& mesh=shapes[shape];
            RasterBatch batch{mesh.count*3,uint32_t(end-begin),mesh.first*3,out.first+uint32_t(begin)};
            batch.buried=buried;
            for(size_t i=begin;i<end;++i) {
                const auto& instance=pending[i].instance;
                V3 origin{std::bit_cast<float>(instance.x),std::bit_cast<float>(instance.y),std::bit_cast<float>(instance.z)};
                V3 lo=origin+mesh.low,hi=origin+mesh.high;
                if(i==begin){batch.low=lo;batch.high=hi;}
                else{batch.low=minimum(batch.low,lo);batch.high=maximum(batch.high,hi);}
                instances[out.first+i]=instance;
            }
            if(firstBounds){out.low=batch.low;out.high=batch.high;firstBounds=false;}
            else{out.low=minimum(out.low,batch.low);out.high=maximum(out.high,batch.high);}
            if(buried)out.buried+=end-begin;
            out.batches.push_back(batch);begin=end;
        }
    }
    void collectBatches() {
        batches.clear();uploadRanges.clear();buriedInstances=0;bool firstBounds=true;
        // Slot order, not allocation order, defines equal-material draw priority.
        for(const auto& section:sections) {
            batches.insert(batches.end(),section.batches.begin(),section.batches.end());
            buriedInstances+=section.buried;
            if(!section.count)continue;
            uploadRanges.push_back({section.first,section.count,section.revision});
            if(firstBounds){low=section.low;high=section.high;firstBounds=false;}
            else{low=minimum(low,section.low);high=maximum(high,section.high);}
        }
        if(firstBounds)low=high={};
        std::stable_sort(batches.begin(),batches.end(),[&](const RasterBatch& a,const RasterBatch& b) {
            uint32_t am=shapes[instances[a.firstInstance].shape].material;
            uint32_t bm=shapes[instances[b.firstInstance].shape].material;
            if(am!=bm)return am<bm;
            return a.firstVertex<b.firstVertex;
        });
        std::sort(uploadRanges.begin(),uploadRanges.end(),[](const Range& a,const Range& b){return a.first<b.first;});
    }

    static V3 point(const std::vector<uint32_t>& words,size_t at) {
        return {std::bit_cast<float>(words[at]),std::bit_cast<float>(words[at+1]),
                std::bit_cast<float>(words[at+2])};
    }
    static V3 minimum(V3 a,V3 b) {
        return {std::min(a.x,b.x),std::min(a.y,b.y),std::min(a.z,b.z)};
    }
    static V3 maximum(V3 a,V3 b) {
        return {std::max(a.x,b.x),std::max(a.y,b.y),std::max(a.z,b.z)};
    }
    static int section(int cell) {
        int q=cell/16;
        return q-(cell<0&&cell%16!=0);
    }
    // Prove a closed opaque unit cube, rather than inferring solidity from its AABB.
    // Each face must have the two complementary half-squares. Cutout geometry
    // (including foliage and thin snow variants) is never an occluding neighbor.
    static bool solidCube(const Scene& scene,uint32_t first,uint32_t count) {
        if(count!=12)return false;
        std::array<std::array<int,2>,6> missing{};
        std::array<unsigned,6> faceCount{};
        const auto& a=scene.assets;
        for(uint32_t j=0;j<count;++j) {
            size_t t=a[3]+size_t(first+j)*24;
            if(a[t+7]&8u)return false;
            V3 p=point(a,t);
            std::array<V3,3> vertex{p,p+point(a,t+4),p+point(a,t+8)};
            int corners[3][3]{};
            for(int k=0;k<3;++k) {
                float c[3]{vertex[k].x,vertex[k].y,vertex[k].z};
                for(int axis=0;axis<3;++axis) {
                    if(c[axis]!=0.f&&c[axis]!=1.f)return false;
                    corners[k][axis]=c[axis]==1.f?1:0;
                }
            }
            int axis=-1;
            for(int k=0;k<3;++k)
                if(corners[0][k]==corners[1][k]&&corners[0][k]==corners[2][k]) {
                    if(axis!=-1)return false;
                    axis=k;
                }
            if(axis<0)return false;
            int face=axis*2+corners[0][axis];
            if(faceCount[face]==2)return false;
            unsigned mask=0;
            for(int k=0;k<3;++k)
                mask|=1u<<(corners[k][(axis+1)%3]+2*corners[k][(axis+2)%3]);
            if(std::popcount(mask)!=3)return false;
            int absent=0;
            while(mask&(1u<<absent))++absent;
            missing[face][faceCount[face]++]=absent;
        }
        for(int face=0;face<6;++face)
            if(faceCount[face]!=2||(missing[face][0]^missing[face][1])!=3)return false;
        return true;
    }
    static uint32_t shapeAt(const Scene& scene,int x,int y,int z) {
        const auto& w=scene.world;
        if(w.size()<16)return UINT32_MAX;
        int sx=section(x),sy=section(y),sz=section(z);
        int gx=sx-std::bit_cast<int32_t>(w[0]);
        int gy=sy-std::bit_cast<int32_t>(w[1]);
        int gz=sz-std::bit_cast<int32_t>(w[2]);
        if(gx<0||gy<0||gz<0||uint32_t(gx)>=w[4]||uint32_t(gy)>=w[5]||uint32_t(gz)>=w[6])
            return UINT32_MAX;
        size_t grid=size_t(gx)+size_t(w[4])*(size_t(gz)+size_t(w[6])*size_t(gy));
        uint32_t slot=w[w[8]+grid];
        if(slot==UINT32_MAX)return slot;
        return w[w[7]+size_t(slot)*4096+uint32_t(x-sx*16)
                 +16*uint32_t(z-sz*16)+256*uint32_t(y-sy*16)];
    }
    bool buriedCube(const Scene& scene,const Instance& instance)const {
        if(!shapes[instance.shape].solidCube)return false;
        int x=int(instance.origin.x),y=int(instance.origin.y),z=int(instance.origin.z);
        constexpr int neighbors[6][3]{{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        for(const auto& d:neighbors) {
            uint32_t shape=shapeAt(scene,x+d[0],y+d[1],z+d[2]);
            if(shape>=shapes.size()||!shapes[shape].solidCube)return false;
        }
        return true;
    }
    void prepareShapes(const Scene& scene) {
        if(source==&scene&&sourceAssets==scene.assets.data()&&sourceAssetWords==scene.assets.size())
            return;
        source=&scene;sourceAssets=scene.assets.data();sourceAssetWords=scene.assets.size();
        const auto& a=scene.assets;
        shapes.assign(a[0],{});
        triangleRefs.resize(a[7]);
        std::iota(triangleRefs.begin(),triangleRefs.end(),0u);
        for(uint32_t shape=0;shape<a[0];++shape) {
            size_t d=a[1]+size_t(shape)*8;
            auto& out=shapes[shape];
            out.first=scene.triangle_starts[shape];out.count=a[d+2];out.material=a[d+3];
            if(a[d]==UINT32_MAX)continue;
            size_t root=a[2]+size_t(a[d])*12;
            out.low=point(a,root);out.high=point(a,root+4);
            out.solidCube=solidCube(scene,out.first,out.count);
        }
    }
public:
    // Bind 7 as std430 uint[]. It addresses the original contiguous triangles,
    // independently of whether ray-tracing BVH leaves use an index table.
    std::vector<uint32_t> triangleRefs;
    std::vector<RasterInstance> instances;
    std::vector<RasterBatch> batches;
    V3 low{},high{};
    size_t buriedInstances=0;
    uint64_t revision=0;

    // Call after Scene construction and after committed pump()/stream/edit updates,
    // never per frame. Scene's immutable shape assets are cached for its lifetime.
    // If a future asset hot-reloader mutates the same vector in place, call this first.
    void invalidateAssets() {source=nullptr;sourceAssets=nullptr;sourceAssetWords=0;}

    void rebuild(const Scene& scene) {
        prepareShapes(scene);
        instances.clear();sections.clear();freeRanges.clear();
        sections.resize(scene.instances.sectionCount());pending.reserve(4096);++revision;
        for(uint32_t slot=0;slot<sections.size();++slot)rebuildSection(scene,slot);
        collectBatches();
    }

    void update(const Scene& scene,std::span<const uint32_t> changed) {
        if(source!=&scene||sourceAssets!=scene.assets.data()||sourceAssetWords!=scene.assets.size()
           ||sections.size()!=scene.instances.sectionCount()){rebuild(scene);return;}
        dirty.assign(sections.size(),0);dirtySlots.clear();
        auto add=[&](uint32_t slot) {
            if(slot<sections.size()&&!dirty[slot]){dirty[slot]=1;dirtySlots.push_back(slot);}
        };
        constexpr int neighbors[6][3]{{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        for(uint32_t slot:changed)if(slot<sections.size()) {
            add(slot);const auto& key=scene.instances.sectionAt(slot).key;
            // A cube's buried state also depends on the adjacent section.
            for(const auto& d:neighbors)add(slotAt(scene,key.x+d[0],key.y+d[1],key.z+d[2]));
        }
        if(dirtySlots.empty())return; // UINT32_MAX is only the grid-metadata marker.
        ++revision;
        for(uint32_t slot:dirtySlots)rebuildSection(scene,slot);
        // A section has at most 4096 cells. Rare internal fragmentation must not
        // make long travel grow the GPU arena beyond that physical resident
        // budget. A compact rebuild assigns fresh revisions to every live range.
        if(instances.size()>sections.size()*size_t(4096)){rebuild(scene);return;}
        collectBatches();
    }

    // Per-section versions survive arbitrarily many updates between frame slots;
    // there is no growing dirty journal and no lost update when one slot lags.
    // Callback receives instance indices/counts, not bytes. A newly allocated GPU
    // buffer still needs a complete upload of instances before using this path.
    template<class Copy> void uploadsSince(uint64_t previous,Copy&& copy)const {
        uint32_t first=0,count=0;
        for(const auto& range:uploadRanges)if(previous==UINT64_MAX||range.revision>previous) {
            if(count&&first+count!=range.first){copy(first,count);count=0;}
            if(!count)first=range.first;
            count+=range.count;
        }
        if(count)copy(first,count);
    }

    static bool visible(const RasterBatch& batch,const Params& p) {
        V3 origin{p.origin[0],p.origin[1],p.origin[2]};
        if(batch.buried) {
            // Closed cubes hide these faces from outside. Keep nearby interiors
            // for Spectator/noclip and the near plane; do not erase their geometry.
            constexpr float margin=2.f;
            if(origin.x<batch.low.x-margin||origin.x>batch.high.x+margin
               ||origin.y<batch.low.y-margin||origin.y>batch.high.y+margin
               ||origin.z<batch.low.z-margin||origin.z>batch.high.z+margin)return false;
        }
        V3 center=(batch.low+batch.high)*.5f-origin,extent=(batch.high-batch.low)*.5f;
        V3 forward{p.forward[0],p.forward[1],p.forward[2]};
        V3 right{p.right[0],p.right[1],p.right[2]},up{p.up[0],p.up[1],p.up[2]};
        auto outside=[&](V3 normal,float offset) {
            float radius=std::abs(normal.x)*extent.x+std::abs(normal.y)*extent.y
                        +std::abs(normal.z)*extent.z;
            return dot(normal,center)+radius<offset-.0001f;
        };
        if(outside(forward,.05f))return false;
        float vertical=p.origin[3],horizontal=vertical*p.forward[3];
        return !outside(forward*horizontal+right,0)&&!outside(forward*horizontal-right,0)
            &&!outside(forward*vertical+up,0)&&!outside(forward*vertical-up,0);
    }
};
}
