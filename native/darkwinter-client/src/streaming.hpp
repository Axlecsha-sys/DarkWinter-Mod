#pragma once
#include "generation.hpp"
namespace dw::client {
class StreamingWorld {
 Scene& scene;Generator generator;dw::Jobs jobs;
 struct Plan;std::unique_ptr<Plan> plan;
 std::map<std::array<int,3>,uint32_t> edits;
 int requestedX=0,requestedZ=0;bool initial=true;
 uint64_t serial=0;
 void start(int x,int z);
public:
 uint64_t generatedColumns=0,discardedPlans=0,shifts=0;float progress=0;
 StreamingWorld(Scene& s,uint64_t seed);
 ~StreamingWorld();
 bool tick(V3 position); // Returns true only when a complete current window is installed.
 bool busy()const{return bool(plan)||!scene.settled();}
 void bootstrap(V3 position,const std::function<void(float)>& status={},const std::atomic<bool>* cancelled=nullptr);
 void save(const std::filesystem::path& path)const;
 void restore(const std::filesystem::path& path);
 uint64_t seed()const{return generator.worldSeed();}
 size_t editCount()const{return edits.size();}
};
}
