#pragma once
#include "vulkan_api.hpp"
#include "scene.hpp"
#include "raster_scene.hpp"
#include <array>
#include <memory>
#include <utility>
#include <atomic>
#include <vk_mem_alloc.h>
namespace dw::client {
// Host-produced visual data. WKE owns GPU execution; the host owns its calendar,
// weather, artistic profile and temporal-history policy.
struct RenderVisualFrame {
 std::array<float,104> scene{};
 std::array<float,20> projection{};
 std::array<float,16> extras{};
 bool captureHeld=false,allowCapture=false,historyValid=false;
};
static_assert(sizeof(RenderVisualFrame::scene)==416);
static_assert(sizeof(RenderVisualFrame::projection)==80);
static_assert(sizeof(RenderVisualFrame::extras)==64);
struct RenderEnvironment {
 std::array<float,8> raster{1,1,1,0,0,0,0,0};
 V3 sun{0,1,0};
};
class RenderVisualProvider {
public:
 virtual ~RenderVisualProvider()=default;
 virtual RenderEnvironment environment(double seconds,uint64_t seed,int weather,double daySeconds)=0;
 virtual RenderVisualFrame prepare(const Camera&,const Params&,float terrainDistance,
     const std::array<float,7>& video,bool temporalActive,bool firstPerson)=0;
 virtual void commit()=0;
 virtual void invalidate()=0;
};
struct Buffer {VkBuffer handle=VK_NULL_HANDLE;VmaAllocation allocation=nullptr;VkDeviceSize bytes=0;void* mapped=nullptr;};
struct Image {
 VkImage handle=VK_NULL_HANDLE;VmaAllocation allocation=nullptr;VkImageView view=VK_NULL_HANDLE;
 VkFormat format=VK_FORMAT_UNDEFINED;VkExtent2D extent{};uint32_t layers=1,mips=1;VkSampleCountFlagBits samples=VK_SAMPLE_COUNT_1_BIT;
 VkImageAspectFlags aspect=VK_IMAGE_ASPECT_COLOR_BIT;VkImageLayout layout=VK_IMAGE_LAYOUT_UNDEFINED;
};
enum class Target:uint32_t {Raw,Depth,Probe,Shadow,Sky,Cloud,CloudDepth,Resolved,ResolvedDepth,Surface,Volume,Composite,Merged,Film,HandDepth,Output,RawMs,DepthMs,MsColorResolved,Count};
enum class Pass:uint32_t {Background,Celestial,Cloud,Resolve,Ambient,Surface,Volume,Composite,Merge,MergeFused,Film,Overlay,DepthResolve,Count};
constexpr size_t targetCount=size_t(Target::Count),passCount=size_t(Pass::Count);
struct Frame {
 Buffer hits,capture,overlay,instances,indirect,uniform,projection,extras;
 uint64_t uiRevision=UINT64_MAX,geometryRevision=UINT64_MAX;
 std::array<Image,targetCount> targets{};
 std::array<VkFramebuffer,passCount> postFrames{};
 VkFramebuffer worldFrame=VK_NULL_HANDLE,actorFrame=VK_NULL_HANDLE,shadowFrame=VK_NULL_HANDLE,handFrame=VK_NULL_HANDLE;
 VkCommandBuffer command=VK_NULL_HANDLE;VkFence fence=VK_NULL_HANDLE;VkSemaphore acquired=VK_NULL_HANDLE;
 VkDescriptorSet worldSet=VK_NULL_HANDLE;std::array<VkDescriptorSet,passCount> postSets{};
 bool submitted=false,worldSample=true;uint64_t serial=0;
 Image& target(Target t){return targets[size_t(t)];}
};
class Renderer {
 HWND window;const Scene& scene;bool probes,vsync,worldSample=true;
 VkInstance instance=VK_NULL_HANDLE;VkDebugUtilsMessengerEXT messenger=VK_NULL_HANDLE;
 VkSurfaceKHR surface=VK_NULL_HANDLE;VkPhysicalDevice physical=VK_NULL_HANDLE;VkDevice device=VK_NULL_HANDLE;
 VkQueue queue=VK_NULL_HANDLE;uint32_t family=0;
 VmaAllocator allocator=nullptr;VkPipelineCache pipelineCache=VK_NULL_HANDLE;
 std::filesystem::path pipelineCachePath;
 VkSwapchainKHR swapchain=VK_NULL_HANDLE;VkFormat format=VK_FORMAT_UNDEFINED;
 VkExtent2D extent{};std::vector<VkImage> images;std::vector<VkSemaphore> presented;
 VkCommandPool commands=VK_NULL_HANDLE;VkDescriptorSetLayout worldLayout=VK_NULL_HANDLE,postLayout=VK_NULL_HANDLE;
 VkDescriptorPool descriptors=VK_NULL_HANDLE;
 VkPipelineLayout worldPipelineLayout=VK_NULL_HANDLE,postPipelineLayout=VK_NULL_HANDLE;
 VkPipeline worldPipeline=VK_NULL_HANDLE,actorPipeline=VK_NULL_HANDLE,shadowPipeline=VK_NULL_HANDLE,starPipeline=VK_NULL_HANDLE;
 std::array<VkPipeline,passCount> postPipelines{};
 VkRenderPass worldPass=VK_NULL_HANDLE,shadowPass=VK_NULL_HANDLE,colorPass=VK_NULL_HANDLE,colorLoadPass=VK_NULL_HANDLE;
 VkSampleCountFlagBits worldSamples=VK_SAMPLE_COUNT_1_BIT;VkRenderPass depthResolvePass=VK_NULL_HANDLE;
  VkRenderPass colorDepthPass=VK_NULL_HANDLE,colorDepthLoadPass=VK_NULL_HANDLE,handPass=VK_NULL_HANDLE;
 VkSampler nearest=VK_NULL_HANDLE,linear=VK_NULL_HANDLE,worldSampler=VK_NULL_HANDLE;
 bool textureAnisotropy=false;float maximumAnisotropy=1;
 VkQueryPool timestamps=VK_NULL_HANDLE;float timestampPeriod=0;uint32_t timestampBits=0;
 bool indirectEnabled=false;uint32_t maxIndirectCount=1;VkDeviceSize storageBufferLimit=0;
 std::vector<uint32_t> uiPixels;uint64_t uiRevision=0;float uiTime=0;
 std::array<float,7> videoSettings{1,70,80,1,1,1,1};std::array<float,8> climateSettings{1,1,1,0,0,0,0,0};
 Buffer assets,avatarData,triangleRefs,starData;std::vector<uint32_t> avatarWords;
 Image held,sunTexture,moonTexture,worldTextures;RasterScene geometry;
 std::vector<VkDrawIndirectCommand> drawCommands;
 std::unique_ptr<RenderVisualProvider> visuals;Params actorPose{};std::array<Frame,2> frames{};uint64_t sequence=0;
 std::filesystem::path shaderDirectory;
 void buffer(Buffer& out,VkDeviceSize size,VkBufferUsageFlags use,VkMemoryPropertyFlags memory);
 void image(Image& out,VkFormat format,VkExtent2D size,VkImageUsageFlags usage,uint32_t layers=1,uint32_t mips=1,bool array=false,VkSampleCountFlagBits samples=VK_SAMPLE_COUNT_1_BIT);
 bool grow(Buffer& out,VkDeviceSize bytes,VkBufferUsageFlags use,VkMemoryPropertyFlags memory);
 void loadPipelineCache(const VkPhysicalDeviceProperties& properties);void savePipelineCache()noexcept;
 void free(Buffer& b);void free(Image& i);
 void upload(Buffer& out,const std::vector<uint32_t>& data);
 void uploadTexture(Image&,const std::filesystem::path&,uint32_t layers);
 void uploadWorldTextures();
 void createPipelines();void createRenderPasses();void createLayouts();
 VkPipeline graphics(const char* vertex,const char* fragment,VkRenderPass pass,VkPipelineLayout layout,uint32_t colors,bool depth,bool depthWrite,bool blend=false);
 VkFramebuffer framebuffer(VkRenderPass pass,std::initializer_list<Image*> attachments,VkExtent2D size);
 void barrier(VkCommandBuffer cmd,Image& image,VkImageLayout layout);
 void initializeImages();void updateDescriptors(Frame& frame,uint32_t slot);
 void beginPass(VkCommandBuffer cmd,VkRenderPass pass,VkFramebuffer frame,VkExtent2D size,std::span<const VkClearValue> clear);
 void drawGeometry(Frame& frame,const Params& params,bool shadow,uint32_t indirectOffset,uint32_t count);
 void drawActor(Frame& frame,Params params,bool firstPerson,bool shadow=false);
 void post(Frame& frame,Pass pass,Target output,const Params& params);
 void targets();void dropTargets();void cleanup()noexcept;void wait(Frame& f,uint32_t slot);
public:
 std::vector<std::string> oracleDetails;std::string deviceName;bool validationEnabled=false;std::atomic<uint32_t> validationErrors{0};
 uint32_t resizeCount=0,oracleRays=0,oracleFailures=0,oracleAmbiguous=0,oracleEquivalent=0;std::vector<double> gpuMs;std::vector<uint64_t> materialPixels;
 Renderer(HWND w,const Scene& s,const std::filesystem::path& shader,bool record,bool sync,bool validation,
     std::unique_ptr<RenderVisualProvider> visualSource);
 ~Renderer(){cleanup();}
 Renderer(const Renderer&)=delete;Renderer& operator=(const Renderer&)=delete;
 bool draw(const Camera& camera,bool capture,const std::filesystem::path& path,bool verify);
 void resize();void drain();
 void animate(float seconds){uiTime=seconds;}
 void sampleWorld(bool enabled){if(worldSample&&!enabled)visuals->invalidate();worldSample=enabled;}
 void video(float brightness,float fov,float distance,float fog,bool shadows,bool clouds,bool ambient){videoSettings={brightness,fov,distance,fog,shadows?1.f:0.f,clouds?1.f:0.f,ambient?1.f:0.f};}
 void environment(double seconds,uint64_t seed=0,int weather=-1,double daySeconds=-1){
  const auto sample=visuals->environment(seconds,seed,weather,daySeconds);
  climateSettings=sample.raster;
  actorPose.avatar[1]=sample.sun.x;actorPose.avatar[2]=sample.sun.y;actorPose.avatar[3]=sample.sun.z;
 }
 void sync(bool enabled){if(vsync!=enabled){vsync=enabled;resize();}}
 void actor(V3 feet,float bodyYaw,float phase,float gait,float yaw,float pitch,bool visible,bool firstPerson=false){
  actorPose.actor[0]=feet.x;actorPose.actor[1]=feet.y;actorPose.actor[2]=feet.z;actorPose.actor[3]=bodyYaw;
  actorPose.pose[0]=phase;actorPose.pose[1]=gait;actorPose.pose[2]=std::remainder(yaw-bodyYaw,6.2831853f);actorPose.pose[3]=pitch;actorPose.avatar[0]=firstPerson?2.f:visible?1.f:0.f;
 }
 uint64_t patchBytes=0,patchUploads=0;
  uint32_t antialiasingSamples()const{return uint32_t(worldSamples); }
 uint64_t sourceTextureOracleFrames=0,filteredTextureFrames=0;
 void updateWorld(const std::vector<uint32_t>& slots);
 void overlay(const std::vector<uint32_t>& pixels){uiPixels=pixels;++uiRevision;}
 uint32_t width()const{return extent.width;}uint32_t height()const{return extent.height;}
};
void write_png(const std::filesystem::path& path,uint32_t w,uint32_t h,const uint8_t* rgba);
}
