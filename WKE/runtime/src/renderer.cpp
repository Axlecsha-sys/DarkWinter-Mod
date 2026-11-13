#include "renderer.hpp"
#include "pipeline_cache.hpp"
#include "world_mips.hpp"
#include "../embedded/vulkan_world_commands.hpp"
#include <algorithm>
#include <cstring>
#include <sstream>
#include <cstddef>
#include <numeric>

namespace dw::client {
static void need(bool v,const char* message){if(!v)throw std::runtime_error(message);}
static std::vector<uint32_t> loadAvatar(const std::filesystem::path& file){
    if(!std::filesystem::is_regular_file(file))return std::vector<uint32_t>(16,0);
    auto b=read_file(file,32*1024*1024);
    auto word=[&](size_t at){uint32_t v;need(at+4<=b.size(),"Avatar header bounds");std::memcpy(&v,b.data()+at,4);return v;};
    uint64_t hash=0;need(b.size()>=96&&std::memcmp(b.data(),"DWAVATR\0",8)==0&&word(8)==1,"Avatar header");std::memcpy(&hash,b.data()+24,8);
    need(uint64_t(word(12))*4==b.size()-32&&checksum(b.data()+32,b.size()-32)==hash,"Avatar integrity");
    std::vector<uint32_t> v((b.size()-32)/4);std::memcpy(v.data(),b.data()+32,b.size()-32);
    auto range=[&](uint32_t at,uint64_t count){need(at>=16&&uint64_t(at)+count<=v.size(),"Avatar table bounds");};
    need((v[0]==6||v[0]==7)&&v[5]>0&&v[5]<=4096&&v[6]>0&&v[6]<=4096&&v[7]<=100000&&v[8]<=100000,"Avatar counts");
    range(v[1],uint64_t(v[0])*12);range(v[2],uint64_t(v[8])*12);range(v[3],uint64_t(v[7])*24);range(v[4],uint64_t(v[5])*v[6]);
    for(uint32_t bone=0;bone<v[0];bone++){
        uint32_t d=v[1]+bone*12,begin=v[d+4],end=v[d+5],first=v[d+7],count=v[d+6];
        need(begin<end&&end<=v[8]&&uint64_t(first)+count<=v[7],"Avatar bone bounds");
        for(int k=0;k<3;k++){float p=std::bit_cast<float>(v[d+k]);need(std::isfinite(p)&&std::abs(p)<=3,"Avatar pivot");}
        for(uint32_t i=begin;i<end;i++){
            uint32_t n=v[2]+i*12;
            need(v[n+8]>i&&v[n+8]<=end&&v[n+7]<=8&&(!v[n+7]||(v[n+3]>=first&&uint64_t(v[n+3])+v[n+7]<=uint64_t(first)+count)),"Avatar BVH ownership");
            for(int k=0;k<3;k++){float lo=std::bit_cast<float>(v[n+k]),hi=std::bit_cast<float>(v[n+4+k]);need(std::isfinite(lo)&&std::isfinite(hi)&&lo<=hi&&lo>=-4&&hi<=4,"Avatar BVH bounds");}
        }
    }
    for(uint32_t i=0;i<v[7];i++)for(int k=0;k<24;k++)if(k!=3&&k!=7&&k!=11){float f=std::bit_cast<float>(v[v[3]+i*24+k]);need(std::isfinite(f)&&std::abs(f)<=100,"Avatar triangle data");}
    return v;
}
static VKAPI_ATTR VkBool32 VKAPI_CALL debug(VkDebugUtilsMessageSeverityFlagBitsEXT severity,VkDebugUtilsMessageTypeFlagsEXT,const VkDebugUtilsMessengerCallbackDataEXT* data,void* user){
    if(severity&VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)static_cast<Renderer*>(user)->validationErrors.fetch_add(1);
    OutputDebugStringA(data->pMessage);OutputDebugStringA("\n");return VK_FALSE;
}
void Renderer::buffer(Buffer& b,VkDeviceSize bytes,VkBufferUsageFlags use,VkMemoryPropertyFlags memory){
 if(use&VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)need(bytes<=storageBufferLimit,"Storage buffer exceeds device limit");
 VkBufferCreateInfo c{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};c.size=bytes;c.usage=use;c.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
 VmaAllocationCreateInfo alloc{};alloc.usage=VMA_MEMORY_USAGE_AUTO;alloc.requiredFlags=memory;
 if(memory&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT){
  alloc.flags=VMA_ALLOCATION_CREATE_MAPPED_BIT;
  alloc.flags|=(use&VK_BUFFER_USAGE_TRANSFER_DST_BIT)&&!(use&VK_BUFFER_USAGE_TRANSFER_SRC_BIT)?
   VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT:VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
 }
 VmaAllocationInfo info{};
 vk::check(vmaCreateBuffer(allocator,&c,&alloc,&b.handle,&b.allocation,&info),"VMA create buffer");
 b.bytes=bytes;b.mapped=info.pMappedData;
}
static VkDeviceSize bufferCapacity(VkDeviceSize required){
 // Geometric growth amortizes streaming changes instead of allocating each exact size.
 VkDeviceSize size=64*1024;
 while(size<required){need(size<=std::numeric_limits<VkDeviceSize>::max()/2,"Buffer capacity overflow");size*=2;}
 return size;
}
bool Renderer::grow(Buffer& out,VkDeviceSize bytes,VkBufferUsageFlags use,VkMemoryPropertyFlags memory){
 if(bytes<=out.bytes)return false;
 VkDeviceSize capacity=bufferCapacity(bytes);
 if(use&VK_BUFFER_USAGE_STORAGE_BUFFER_BIT){need(bytes<=storageBufferLimit,"Storage buffer exceeds device limit");capacity=std::min(capacity,storageBufferLimit);}
 Buffer replacement;buffer(replacement,capacity,use,memory);
 free(out);out=replacement;return true;
}
void Renderer::free(Buffer& b){
 if(b.handle)vmaDestroyBuffer(allocator,b.handle,b.allocation);b={};
}
void Renderer::free(Image& i){
 if(i.view)vk::DestroyImageView(device,i.view,nullptr);
 if(i.handle)vmaDestroyImage(allocator,i.handle,i.allocation);i={};
}
void Renderer::image(Image& out,VkFormat fmt,VkExtent2D size,VkImageUsageFlags usage,uint32_t layers,uint32_t mips,bool array,VkSampleCountFlagBits samples){
 out.format=fmt;out.extent=size;out.layers=layers;out.mips=mips;out.samples=samples;
 out.aspect=fmt==VK_FORMAT_D32_SFLOAT?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT;
 VkImageCreateInfo c{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};c.imageType=VK_IMAGE_TYPE_2D;c.format=fmt;
 c.extent={size.width,size.height,1};c.mipLevels=mips;c.arrayLayers=layers;c.samples=samples;c.tiling=VK_IMAGE_TILING_OPTIMAL;
 c.usage=usage;c.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
 VmaAllocationCreateInfo alloc{};alloc.usage=VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;alloc.requiredFlags=VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
 vk::check(vmaCreateImage(allocator,&c,&alloc,&out.handle,&out.allocation,nullptr),"VMA create image");
 VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};view.image=out.handle;view.viewType=layers==1&&!array?VK_IMAGE_VIEW_TYPE_2D:VK_IMAGE_VIEW_TYPE_2D_ARRAY;
 view.format=fmt;view.subresourceRange={out.aspect,0,mips,0,layers};
 vk::check(vk::CreateImageView(device,&view,nullptr,&out.view),"CreateImageView");
}
static std::pair<VkPipelineStageFlags,VkAccessFlags> imageScope(VkImageLayout layout){
 switch(layout){
 case VK_IMAGE_LAYOUT_UNDEFINED:return {VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,0};
 case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL:return {VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT};
 case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL:return {VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT};
 case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:return {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT};
 case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:return {VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT};
 case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:return {VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT};
 default:throw std::runtime_error("Unsupported render-graph image layout");
 }
}
void Renderer::barrier(VkCommandBuffer cmd,Image& image,VkImageLayout layout){
 if(image.layout==layout)return;
 auto source=imageScope(image.layout),dest=imageScope(layout);
 VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.oldLayout=image.layout;b.newLayout=layout;
 b.srcAccessMask=source.second;b.dstAccessMask=dest.second;b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
 b.image=image.handle;b.subresourceRange={image.aspect,0,image.mips,0,image.layers};
 vk::CmdPipelineBarrier(cmd,source.first,dest.first,0,0,nullptr,0,nullptr,1,&b);
 image.layout=layout;
}
void Renderer::upload(Buffer& out,const std::vector<uint32_t>& data){
 Buffer staging;
 try{
  VkDeviceSize bytes=std::max<size_t>(4,data.size()*4);
  buffer(staging,bytes,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  if(data.empty())std::memset(staging.mapped,0,4);else std::memcpy(staging.mapped,data.data(),data.size()*4);
  buffer(out,bytes,VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  auto& f=frames[0];vk::check(vk::WaitForFences(device,1,&f.fence,VK_TRUE,UINT64_MAX),"Upload wait");vk::check(vk::ResetFences(device,1,&f.fence),"Upload fence reset");
  vk::check(vk::ResetCommandBuffer(f.command,0),"Upload command reset");
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  vk::check(vk::BeginCommandBuffer(f.command,&begin),"Upload begin");
  VkBufferCopy copy{0,0,bytes};vk::CmdCopyBuffer(f.command,staging.handle,out.handle,1,&copy);
  VkBufferMemoryBarrier b{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
  b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.buffer=out.handle;b.size=VK_WHOLE_SIZE;
  vk::CmdPipelineBarrier(f.command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,0,nullptr,1,&b,0,nullptr);
  vk::check(vk::EndCommandBuffer(f.command),"Upload end");
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&f.command;
  vk::check(vk::QueueSubmit(queue,1,&submit,f.fence),"Upload submit");vk::check(vk::WaitForFences(device,1,&f.fence,VK_TRUE,UINT64_MAX),"Upload finish");
  free(staging);
 }catch(...){if(device)vk::DeviceWaitIdle(device);free(staging);throw;}
}
void Renderer::uploadTexture(Image& out,const std::filesystem::path& path,uint32_t layers){
 auto data=read_file(path,64*1024*1024);
 need(data.size()>=28&&std::memcmp(data.data(),"DWCEL1\0\0",8)==0,"Celestial texture header");
 uint32_t h[5];std::memcpy(h,data.data()+8,sizeof(h));need(h[4]==8,"Moon phase layer count");
 for(int k=0;k<4;++k)need(h[k]>0&&h[k]<=4096,"Celestial texture dimensions");
 size_t sunBytes=size_t(h[0])*h[1]*4,moonBytes=size_t(h[2])*h[3]*4*8;
 need(data.size()==28+sunBytes+moonBytes,"Celestial texture bounds");
 const uint8_t* source=data.data()+28+(layers==1?0:sunBytes);
 size_t bytes=layers==1?sunBytes:moonBytes;
 VkExtent2D size=layers==1?VkExtent2D{h[0],h[1]}:VkExtent2D{h[2],h[3]};
 Buffer staging;
 try{
  image(out,VK_FORMAT_R8G8B8A8_UNORM,size,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,layers);
  buffer(staging,bytes,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  std::memcpy(staging.mapped,source,bytes);
  auto& f=frames[0];vk::check(vk::WaitForFences(device,1,&f.fence,VK_TRUE,UINT64_MAX),"Texture wait");
  vk::check(vk::ResetFences(device,1,&f.fence),"Texture reset fence");vk::check(vk::ResetCommandBuffer(f.command,0),"Texture reset command");
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;vk::check(vk::BeginCommandBuffer(f.command,&begin),"Texture begin");
  barrier(f.command,out,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  VkBufferImageCopy copy{};copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,layers};copy.imageExtent={size.width,size.height,1};
  vk::CmdCopyBufferToImage(f.command,staging.handle,out.handle,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
  barrier(f.command,out,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  vk::check(vk::EndCommandBuffer(f.command),"Texture end");
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&f.command;
  vk::check(vk::QueueSubmit(queue,1,&submit,f.fence),"Texture submit");vk::check(vk::WaitForFences(device,1,&f.fence,VK_TRUE,UINT64_MAX),"Texture finish");free(staging);
 }catch(...){if(device)vk::DeviceWaitIdle(device);free(staging);throw;}
}
void Renderer::uploadWorldTextures(){
 WorldMipChain chain(scene.assets);
 VkPhysicalDeviceProperties properties{};vk::GetPhysicalDeviceProperties(physical,&properties);
 need(chain.size<=properties.limits.maxImageDimension2D&&chain.layers<=properties.limits.maxImageArrayLayers,"World texture image limits");
 VkFormatProperties formatProperties{};vk::GetPhysicalDeviceFormatProperties(physical,VK_FORMAT_R8G8B8A8_UNORM,&formatProperties);
 need(formatProperties.optimalTilingFeatures&VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT,"World texture linear sampling unavailable");
 Buffer staging;
 try{
  image(worldTextures,VK_FORMAT_R8G8B8A8_UNORM,{chain.size,chain.size},VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,chain.layers,chain.levels,true);
  VkDeviceSize bytes=chain.pixels.size()*sizeof(uint32_t);
  buffer(staging,bytes,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  std::memcpy(staging.mapped,chain.pixels.data(),size_t(bytes));
  std::vector<VkBufferImageCopy> copies;copies.reserve(chain.slices.size());
  for(const auto& slice:chain.slices){
   VkBufferImageCopy copy{};copy.bufferOffset=slice.wordOffset*sizeof(uint32_t);
   copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,slice.level,slice.layer,1};
   copy.imageExtent={slice.size,slice.size,1};copies.push_back(copy);
  }
  auto& frame=frames[0];vk::check(vk::WaitForFences(device,1,&frame.fence,VK_TRUE,UINT64_MAX),"World texture wait");
  vk::check(vk::ResetFences(device,1,&frame.fence),"World texture fence");vk::check(vk::ResetCommandBuffer(frame.command,0),"World texture command");
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  vk::check(vk::BeginCommandBuffer(frame.command,&begin),"World texture begin");
  barrier(frame.command,worldTextures,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  vk::CmdCopyBufferToImage(frame.command,staging.handle,worldTextures.handle,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,uint32_t(copies.size()),copies.data());
  barrier(frame.command,worldTextures,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  vk::check(vk::EndCommandBuffer(frame.command),"World texture end");
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&frame.command;
  vk::check(vk::QueueSubmit(queue,1,&submit,frame.fence),"World texture submit");
  vk::check(vk::WaitForFences(device,1,&frame.fence,VK_TRUE,UINT64_MAX),"World texture upload finish");free(staging);
 }catch(...){if(device)vk::DeviceWaitIdle(device);free(staging);throw;}
}
void Renderer::createLayouts(){
 constexpr VkShaderStageFlags stages=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;
 std::vector<VkDescriptorSetLayoutBinding> b;
 for(uint32_t binding:{1u,4u,5u,6u,7u,8u}){
  VkShaderStageFlags visibility=binding==4?VK_SHADER_STAGE_FRAGMENT_BIT:(binding==6||binding==7)?VK_SHADER_STAGE_VERTEX_BIT:stages;
  b.push_back({binding,binding==8?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1,visibility,nullptr});
 }
 b.push_back({9,VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1,VK_SHADER_STAGE_FRAGMENT_BIT,nullptr});
 VkDescriptorSetLayoutCreateInfo ci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};ci.bindingCount=uint32_t(b.size());ci.pBindings=b.data();
 vk::check(vk::CreateDescriptorSetLayout(device,&ci,nullptr,&worldLayout),"World descriptor layout");
 b.clear();
 for(uint32_t binding=0;binding<=16;++binding){
  auto type=(binding==8||binding==10||binding==12)?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:
    (binding==11||binding==16)?VK_DESCRIPTOR_TYPE_STORAGE_BUFFER:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
  VkShaderStageFlags visibility=binding==16?VK_SHADER_STAGE_VERTEX_BIT:(binding==8||binding==10||binding==12)?stages:VK_SHADER_STAGE_FRAGMENT_BIT;
  b.push_back({binding,type,1,visibility,nullptr});
 }
 ci.bindingCount=uint32_t(b.size());ci.pBindings=b.data();
 vk::check(vk::CreateDescriptorSetLayout(device,&ci,nullptr,&postLayout),"Visual descriptor layout");
 VkPushConstantRange push{stages,0,sizeof(Params)};
 VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pl.setLayoutCount=1;pl.pushConstantRangeCount=1;pl.pPushConstantRanges=&push;
 pl.pSetLayouts=&worldLayout;vk::check(vk::CreatePipelineLayout(device,&pl,nullptr,&worldPipelineLayout),"World pipeline layout");
 pl.pSetLayouts=&postLayout;vk::check(vk::CreatePipelineLayout(device,&pl,nullptr,&postPipelineLayout),"Visual pipeline layout");
 VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};si.magFilter=si.minFilter=VK_FILTER_NEAREST;si.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
 si.addressModeU=si.addressModeV=si.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;si.maxLod=0;
 vk::check(vk::CreateSampler(device,&si,nullptr,&nearest),"Nearest sampler");
 si.magFilter=si.minFilter=VK_FILTER_LINEAR;vk::check(vk::CreateSampler(device,&si,nullptr,&linear),"Film linear sampler");
 si.magFilter=VK_FILTER_NEAREST;si.minFilter=VK_FILTER_LINEAR;si.mipmapMode=VK_SAMPLER_MIPMAP_MODE_LINEAR;
 si.maxLod=float(worldTextures.mips-1);si.anisotropyEnable=textureAnisotropy;si.maxAnisotropy=maximumAnisotropy;
 vk::check(vk::CreateSampler(device,&si,nullptr,&worldSampler),"World mip sampler");
}
void Renderer::createRenderPasses(){
 auto make=[&](VkRenderPass& out,std::vector<VkFormat> colors,bool depth,bool loadColor,bool loadDepth){
  std::vector<VkAttachmentDescription> attachments;
  std::vector<VkAttachmentReference> refs;
  for(auto fmt:colors){
   VkAttachmentDescription a{};a.format=fmt;a.samples=VK_SAMPLE_COUNT_1_BIT;
   a.loadOp=loadColor?VK_ATTACHMENT_LOAD_OP_LOAD:VK_ATTACHMENT_LOAD_OP_CLEAR;a.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
   a.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE;a.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
   a.initialLayout=a.finalLayout=VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
   refs.push_back({uint32_t(attachments.size()),a.initialLayout});attachments.push_back(a);
  }
  VkAttachmentReference depthRef{};
  if(depth){
   VkAttachmentDescription a{};a.format=VK_FORMAT_D32_SFLOAT;a.samples=VK_SAMPLE_COUNT_1_BIT;
   a.loadOp=loadDepth?VK_ATTACHMENT_LOAD_OP_LOAD:VK_ATTACHMENT_LOAD_OP_CLEAR;a.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
   a.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE;a.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
   a.initialLayout=a.finalLayout=VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
   depthRef={uint32_t(attachments.size()),a.initialLayout};attachments.push_back(a);
  }
  VkSubpassDescription sub{};sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;sub.colorAttachmentCount=uint32_t(refs.size());sub.pColorAttachments=refs.data();sub.pDepthStencilAttachment=depth?&depthRef:nullptr;
  // Layout transitions already synchronize shader/transfer users. This dependency
  // covers attachment WAW/LOAD when consecutive render passes keep the same layout.
  VkSubpassDependency dep{};dep.srcSubpass=VK_SUBPASS_EXTERNAL;dep.dstSubpass=0;
  if(!colors.empty()){
   dep.srcStageMask|=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;dep.srcAccessMask|=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
   dep.dstAccessMask|=VK_ACCESS_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
  }
  if(depth){
   dep.srcStageMask|=VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
   dep.srcAccessMask|=VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
   dep.dstAccessMask|=VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
  }
  dep.dstStageMask=dep.srcStageMask;
  VkRenderPassCreateInfo ci{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};ci.attachmentCount=uint32_t(attachments.size());ci.pAttachments=attachments.data();ci.subpassCount=1;ci.pSubpasses=&sub;ci.dependencyCount=1;ci.pDependencies=&dep;
  vk::check(vk::CreateRenderPass(device,&ci,nullptr,&out),"Create render pass");
 };
 if(worldSamples==VK_SAMPLE_COUNT_1_BIT)make(worldPass,{VK_FORMAT_R8G8B8A8_UNORM,VK_FORMAT_R32G32_UINT},true,false,false);
  else {
   // Resolve only color here. Vulkan 1.1 depth is resolved explicitly below;
   // the debug integer probe is deliberately absent from the normal MSAA pass.
   std::array<VkAttachmentDescription,3> a{};
   for(auto& attachment:a){attachment.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE;attachment.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;}
   a[0].format=a[2].format=VK_FORMAT_R8G8B8A8_UNORM;a[1].format=VK_FORMAT_D32_SFLOAT;
   a[0].samples=a[1].samples=worldSamples;a[2].samples=VK_SAMPLE_COUNT_1_BIT;
   a[0].loadOp=a[1].loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR;a[2].loadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE;
   a[0].storeOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;a[1].storeOp=a[2].storeOp=VK_ATTACHMENT_STORE_OP_STORE;
   a[0].initialLayout=a[0].finalLayout=a[2].initialLayout=a[2].finalLayout=VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
   a[1].initialLayout=a[1].finalLayout=VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
   VkAttachmentReference colors[]={{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL},{VK_ATTACHMENT_UNUSED,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL}};
   VkAttachmentReference resolves[]={{2,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL},{VK_ATTACHMENT_UNUSED,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL}};
   VkAttachmentReference depth{1,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
   VkSubpassDescription sub{};sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;sub.colorAttachmentCount=2;sub.pColorAttachments=colors;sub.pResolveAttachments=resolves;sub.pDepthStencilAttachment=&depth;
   VkSubpassDependency dep{};dep.srcSubpass=VK_SUBPASS_EXTERNAL;dep.dstSubpass=0;
   dep.srcStageMask=dep.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT|VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
   dep.srcAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
   dep.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
   VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};info.attachmentCount=uint32_t(a.size());info.pAttachments=a.data();info.subpassCount=1;info.pSubpasses=&sub;info.dependencyCount=1;info.pDependencies=&dep;
   vk::check(vk::CreateRenderPass(device,&info,nullptr,&worldPass),"World MSAA render pass");
  }
  make(depthResolvePass,{VK_FORMAT_R8G8B8A8_UNORM},true,false,false);
 make(shadowPass,{},true,false,false);
 make(colorPass,{VK_FORMAT_R8G8B8A8_UNORM},false,false,false);
 make(colorLoadPass,{VK_FORMAT_R8G8B8A8_UNORM},false,true,false);
 make(colorDepthPass,{VK_FORMAT_R8G8B8A8_UNORM},true,false,false);
 make(colorDepthLoadPass,{VK_FORMAT_R8G8B8A8_UNORM},true,true,true);
 make(handPass,{VK_FORMAT_R8G8B8A8_UNORM},true,true,false);
}
VkPipeline Renderer::graphics(const char* vertex,const char* fragment,VkRenderPass pass,VkPipelineLayout layout,uint32_t colors,bool depth,bool depthWrite,bool blend){
 VkShaderModule modules[2]{};
 try{
  auto module=[&](const char* name,VkShaderModule& out){
   auto code=read_file(shaderDirectory/(std::string(name)+".spv"),8*1024*1024);need(!code.empty()&&code.size()%4==0,"SPIR-V size");
   std::vector<uint32_t> aligned(code.size()/4);std::memcpy(aligned.data(),code.data(),code.size());
   VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};ci.codeSize=code.size();ci.pCode=aligned.data();
   vk::check(vk::CreateShaderModule(device,&ci,nullptr,&out),"Graphics shader module");
  };
  module(vertex,modules[0]);module(fragment,modules[1]);
  VkPipelineShaderStageCreateInfo shaders[2]{};
  for(uint32_t i=0;i<2;++i){shaders[i]={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};shaders[i].stage=i?VK_SHADER_STAGE_FRAGMENT_BIT:VK_SHADER_STAGE_VERTEX_BIT;shaders[i].module=modules[i];shaders[i].pName="main";}
  VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
  VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
  VkPipelineViewportStateCreateInfo viewport{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};viewport.viewportCount=viewport.scissorCount=1;
  VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
  raster.polygonMode=VK_POLYGON_MODE_FILL;raster.cullMode=VK_CULL_MODE_NONE;raster.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;raster.lineWidth=1;
  VkPipelineMultisampleStateCreateInfo samples{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};samples.rasterizationSamples=pass==worldPass?worldSamples:VK_SAMPLE_COUNT_1_BIT;
   samples.alphaToCoverageEnable=pass==worldPass&&worldSamples!=VK_SAMPLE_COUNT_1_BIT;
  VkPipelineDepthStencilStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
  ds.depthTestEnable=depth;ds.depthWriteEnable=depthWrite;ds.depthCompareOp=VK_COMPARE_OP_GREATER;
  std::vector<VkPipelineColorBlendAttachmentState> attachments(colors);
  for(auto& a:attachments){a.colorWriteMask=VK_COLOR_COMPONENT_R_BIT|VK_COLOR_COMPONENT_G_BIT|VK_COLOR_COMPONENT_B_BIT|VK_COLOR_COMPONENT_A_BIT;
   if(blend){a.blendEnable=VK_TRUE;a.srcColorBlendFactor=VK_BLEND_FACTOR_SRC_ALPHA;a.dstColorBlendFactor=VK_BLEND_FACTOR_ONE;a.colorBlendOp=VK_BLEND_OP_ADD;a.srcAlphaBlendFactor=VK_BLEND_FACTOR_ONE;a.dstAlphaBlendFactor=VK_BLEND_FACTOR_ZERO;a.alphaBlendOp=VK_BLEND_OP_ADD;}}
  VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};cb.attachmentCount=colors;cb.pAttachments=attachments.data();
  VkDynamicState dynamic[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
  VkPipelineDynamicStateCreateInfo dyn{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};dyn.dynamicStateCount=2;dyn.pDynamicStates=dynamic;
  VkGraphicsPipelineCreateInfo ci{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
  ci.stageCount=2;ci.pStages=shaders;ci.pVertexInputState=&vi;ci.pInputAssemblyState=&ia;ci.pViewportState=&viewport;ci.pRasterizationState=&raster;
  ci.pMultisampleState=&samples;ci.pDepthStencilState=&ds;ci.pColorBlendState=&cb;ci.pDynamicState=&dyn;ci.layout=layout;ci.renderPass=pass;
  VkPipeline result=VK_NULL_HANDLE;vk::check(vk::CreateGraphicsPipelines(device,pipelineCache,1,&ci,nullptr,&result),"Graphics pipeline");
  for(auto m:modules)vk::DestroyShaderModule(device,m,nullptr);return result;
 }catch(...){for(auto m:modules)if(m)vk::DestroyShaderModule(device,m,nullptr);throw;}
}
void Renderer::loadPipelineCache(const VkPhysicalDeviceProperties& properties){
 std::vector<uint8_t> bytes;
 try{pipelineCachePath=pipeline_disk::path(properties);bytes=pipeline_disk::read(pipelineCachePath,properties);}catch(...){pipelineCachePath.clear();}
 VkPipelineCacheCreateInfo info{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};info.initialDataSize=bytes.size();info.pInitialData=bytes.data();
 VkResult result=vk::CreatePipelineCache(device,&info,nullptr,&pipelineCache);
 if(result!=VK_SUCCESS&&!bytes.empty()){
  info.initialDataSize=0;info.pInitialData=nullptr;
  result=vk::CreatePipelineCache(device,&info,nullptr,&pipelineCache);
 }
 vk::check(result,"Create pipeline cache");
}
void Renderer::savePipelineCache()noexcept{
 if(!device||!pipelineCache||pipelineCachePath.empty())return;
 try{
  for(int attempt=0;attempt<2;++attempt){
   size_t size=0;if(vk::GetPipelineCacheData(device,pipelineCache,&size,nullptr)!=VK_SUCCESS||!size||size>pipeline_disk::maximumBytes)return;
   std::vector<uint8_t> bytes(size);VkResult result=vk::GetPipelineCacheData(device,pipelineCache,&size,bytes.data());
   if(result==VK_INCOMPLETE)continue;
   if(result!=VK_SUCCESS)return;
   bytes.resize(size);pipeline_disk::write(pipelineCachePath,bytes);return;
  }
 }catch(...){}
}
void Renderer::createPipelines(){
 createLayouts();createRenderPasses();
 worldPipeline=graphics("raster.vert","raster.frag",worldPass,worldPipelineLayout,2,true,true);
 actorPipeline=graphics("raster.vert","raster.frag",colorDepthLoadPass,worldPipelineLayout,1,true,true);
 shadowPipeline=graphics("shadow.vert","shadow.frag",shadowPass,worldPipelineLayout,0,true,true);
 auto make=[&](Pass p,const char* fragment,VkRenderPass rp,bool depth=false){postPipelines[size_t(p)]=graphics("visuals/fullscreen.vert",fragment,rp,postPipelineLayout,1,depth,depth);};
 make(Pass::Background,"visuals/weather_sky_background.frag",colorPass);
 postPipelines[size_t(Pass::Celestial)]=graphics("visuals/celestial.vert","visuals/celestial.frag",colorLoadPass,postPipelineLayout,1,false,false,true);
 starPipeline=graphics("visuals/star.vert","visuals/star.frag",colorLoadPass,postPipelineLayout,1,false,false,true);
 make(Pass::Cloud,"visuals/weather_sky_clouds.frag",colorDepthPass,true);
 make(Pass::Resolve,"visuals/cloud_resolve.frag",colorPass);
 make(Pass::Ambient,"visuals/ambient.frag",colorPass);
 make(Pass::Surface,"visuals/surface.frag",colorPass);
 make(Pass::Volume,"visuals/volume.frag",colorPass);
 make(Pass::Composite,"visuals/composite.frag",colorPass);
 make(Pass::Merge,"visuals/sky_merge.frag",colorPass);
 make(Pass::MergeFused,"visuals/sky_merge_fused.frag",colorPass);
 make(Pass::Film,"visuals/film.frag",colorPass);
 make(Pass::Overlay,"visuals/ui_composite.frag",colorPass);
  postPipelines[size_t(Pass::DepthResolve)]=graphics("visuals/fullscreen.vert","depth_resolve.frag",depthResolvePass,postPipelineLayout,1,true,true);
}
VkFramebuffer Renderer::framebuffer(VkRenderPass pass,std::initializer_list<Image*> attachments,VkExtent2D size){
 std::vector<VkImageView> views;for(auto* image:attachments)views.push_back(image->view);
 VkFramebufferCreateInfo ci{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};ci.renderPass=pass;ci.attachmentCount=uint32_t(views.size());ci.pAttachments=views.data();ci.width=size.width;ci.height=size.height;ci.layers=1;
 VkFramebuffer out=VK_NULL_HANDLE;vk::check(vk::CreateFramebuffer(device,&ci,nullptr,&out),"Create framebuffer");return out;
}
void Renderer::beginPass(VkCommandBuffer cmd,VkRenderPass pass,VkFramebuffer frame,VkExtent2D size,std::span<const VkClearValue> clear){
 VkRenderPassBeginInfo begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};begin.renderPass=pass;begin.framebuffer=frame;begin.renderArea={{0,0},size};begin.clearValueCount=uint32_t(clear.size());begin.pClearValues=clear.data();
 vk::CmdBeginRenderPass(cmd,&begin,VK_SUBPASS_CONTENTS_INLINE);
 VkViewport viewport{0,0,float(size.width),float(size.height),0,1};VkRect2D scissor{{0,0},size};vk::CmdSetViewport(cmd,0,1,&viewport);vk::CmdSetScissor(cmd,0,1,&scissor);
}
Renderer::Renderer(HWND w,const Scene& s,const std::filesystem::path& shader,bool record,bool sync,bool validation,
    std::unique_ptr<RenderVisualProvider> visualSource):window(w),scene(s),probes(record),vsync(sync),visuals(std::move(visualSource)),materialPixels(s.materials,0){
    shaderDirectory=shader.parent_path();
    try{
        need(bool(visuals),"A host visual provider is required");
        gpuMs.reserve(65536);vk::global();
        uint32_t n=0;vk::check(vk::EnumerateInstanceExtensionProperties(nullptr,&n,nullptr),"Instance extensions");
        std::vector<VkExtensionProperties> extensions(n);vk::check(vk::EnumerateInstanceExtensionProperties(nullptr,&n,extensions.data()),"Instance extension list");
        auto has=[&](const char* name){return std::any_of(extensions.begin(),extensions.end(),[&](auto& e){return std::strcmp(e.extensionName,name)==0;});};
        need(has(VK_KHR_SURFACE_EXTENSION_NAME)&&has(VK_KHR_WIN32_SURFACE_EXTENSION_NAME),"Win32 Vulkan surface is unavailable");
        std::vector<const char*> enabled{VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_WIN32_SURFACE_EXTENSION_NAME};
        vk::check(vk::EnumerateInstanceLayerProperties(&n,nullptr),"Instance layers");std::vector<VkLayerProperties> layers(n);vk::check(vk::EnumerateInstanceLayerProperties(&n,layers.data()),"Instance layer list");
        validationEnabled=validation&&has(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)&&std::any_of(layers.begin(),layers.end(),[](auto& l){return std::strcmp(l.layerName,"VK_LAYER_KHRONOS_validation")==0;});
        const char* layer="VK_LAYER_KHRONOS_validation";if(validationEnabled)enabled.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="DarkWinter Core";app.applicationVersion=VK_MAKE_VERSION(0,2,0);app.pEngineName="WKE";app.apiVersion=VK_API_VERSION_1_1;
        VkDebugUtilsMessengerCreateInfoEXT debugInfo{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
        debugInfo.messageSeverity=VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;debugInfo.messageType=VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
        debugInfo.pfnUserCallback=debug;debugInfo.pUserData=this;
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ci.pApplicationInfo=&app;ci.enabledExtensionCount=uint32_t(enabled.size());ci.ppEnabledExtensionNames=enabled.data();
        if(validationEnabled){ci.enabledLayerCount=1;ci.ppEnabledLayerNames=&layer;ci.pNext=&debugInfo;}
        vk::check(vk::CreateInstance(&ci,nullptr,&instance),"CreateInstance");vk::instance(instance);
        if(validationEnabled)vk::check(vk::CreateDebugUtilsMessengerEXT(instance,&debugInfo,nullptr,&messenger),"Debug messenger");
        VkWin32SurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR};sci.hinstance=GetModuleHandleW(nullptr);sci.hwnd=window;
        vk::check(vk::CreateWin32SurfaceKHR(instance,&sci,nullptr,&surface),"Win32 surface");
        vk::check(vk::EnumeratePhysicalDevices(instance,&n,nullptr),"Physical devices");std::vector<VkPhysicalDevice> devices(n);vk::check(vk::EnumeratePhysicalDevices(instance,&n,devices.data()),"Physical device list");
        int best=-1;VkPhysicalDeviceProperties properties{};
        for(auto candidate:devices){
            VkPhysicalDeviceProperties p;vk::GetPhysicalDeviceProperties(candidate,&p);if(p.apiVersion<VK_API_VERSION_1_1)continue;
            uint32_t count=0;vk::GetPhysicalDeviceQueueFamilyProperties(candidate,&count,nullptr);std::vector<VkQueueFamilyProperties> families(count);vk::GetPhysicalDeviceQueueFamilyProperties(candidate,&count,families.data());
            uint32_t ec=0;vk::check(vk::EnumerateDeviceExtensionProperties(candidate,nullptr,&ec,nullptr),"Device extensions");std::vector<VkExtensionProperties> ex(ec);vk::check(vk::EnumerateDeviceExtensionProperties(candidate,nullptr,&ec,ex.data()),"Device extension list");
            if(!std::any_of(ex.begin(),ex.end(),[](auto& e){return std::strcmp(e.extensionName,VK_KHR_SWAPCHAIN_EXTENSION_NAME)==0;}))continue;
            for(uint32_t i=0;i<count;++i){VkBool32 present=VK_FALSE;vk::check(vk::GetPhysicalDeviceSurfaceSupportKHR(candidate,i,surface,&present),"Present support");
                if(!present||(families[i].queueFlags&(VK_QUEUE_COMPUTE_BIT|VK_QUEUE_GRAPHICS_BIT))!=(VK_QUEUE_COMPUTE_BIT|VK_QUEUE_GRAPHICS_BIT))continue;
                int score=p.deviceType==VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU?2:1;if(score<=best)continue;
                best=score;physical=candidate;family=i;properties=p;timestampBits=families[i].timestampValidBits;
            }
        }
        need(sizeof(Params)<=properties.limits.maxPushConstantsSize,"Push constant limit");
        need(physical!=VK_NULL_HANDLE,"No Vulkan 1.1 graphics/compute/present device");
        need(scene.assets.size()*4<=properties.limits.maxStorageBufferRange,"Scene exceeds device storage-buffer limits");
        timestampPeriod=properties.limits.timestampPeriod;deviceName=properties.deviceName;storageBufferLimit=properties.limits.maxStorageBufferRange;
        VkFormatProperties fp;vk::GetPhysicalDeviceFormatProperties(physical,VK_FORMAT_R8G8B8A8_UNORM,&fp);
        need((fp.optimalTilingFeatures&(VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|VK_FORMAT_FEATURE_BLIT_SRC_BIT))==(VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|VK_FORMAT_FEATURE_BLIT_SRC_BIT),"RGBA8 render/sample/blit unavailable");
        vk::GetPhysicalDeviceFormatProperties(physical,VK_FORMAT_D32_SFLOAT,&fp);need((fp.optimalTilingFeatures&(VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT))==(VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT),"D32 depth sampling unavailable");
        vk::GetPhysicalDeviceFormatProperties(physical,VK_FORMAT_R32G32_UINT,&fp);need(fp.optimalTilingFeatures&VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT,"Integer world probe attachment unavailable");
        float priority=1;VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qi.queueFamilyIndex=family;qi.queueCount=1;qi.pQueuePriorities=&priority;
        const char* swapExtension=VK_KHR_SWAPCHAIN_EXTENSION_NAME;VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dc.queueCreateInfoCount=1;dc.pQueueCreateInfos=&qi;dc.enabledExtensionCount=1;dc.ppEnabledExtensionNames=&swapExtension;
        VkPhysicalDeviceFeatures available{},features{};vk::GetPhysicalDeviceFeatures(physical,&available);
        indirectEnabled=available.multiDrawIndirect&&available.drawIndirectFirstInstance;
        // Exact oracle captures retain the single-sample source contract. Normal
         // gameplay chooses the highest common supported count up to four.
         if(!probes){
          VkSampleCountFlags counts=properties.limits.framebufferColorSampleCounts&properties.limits.framebufferDepthSampleCounts&properties.limits.sampledImageDepthSampleCounts;
          for(auto entry:{std::pair{VK_FORMAT_R8G8B8A8_UNORM,VkImageUsageFlags(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)},std::pair{VK_FORMAT_D32_SFLOAT,VkImageUsageFlags(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT)}}){
           VkImageFormatProperties support{};VkResult result=vk::GetPhysicalDeviceImageFormatProperties(physical,entry.first,VK_IMAGE_TYPE_2D,VK_IMAGE_TILING_OPTIMAL,entry.second,0,&support);
           counts&=result==VK_SUCCESS?support.sampleCounts:VkSampleCountFlags(VK_SAMPLE_COUNT_1_BIT);
          }
          if(counts&VK_SAMPLE_COUNT_4_BIT)worldSamples=VK_SAMPLE_COUNT_4_BIT;else if(counts&VK_SAMPLE_COUNT_2_BIT)worldSamples=VK_SAMPLE_COUNT_2_BIT;
         }
         textureAnisotropy=available.samplerAnisotropy;maximumAnisotropy=textureAnisotropy?std::min(8.f,properties.limits.maxSamplerAnisotropy):1.f;
        features.samplerAnisotropy=textureAnisotropy;
        features.multiDrawIndirect=indirectEnabled;features.drawIndirectFirstInstance=indirectEnabled;dc.pEnabledFeatures=&features;
        maxIndirectCount=properties.limits.maxDrawIndirectCount;
        vk::check(vk::CreateDevice(physical,&dc,nullptr,&device),"CreateDevice");vk::device(device);vk::GetDeviceQueue(device,family,0,&queue);
        VmaVulkanFunctions functions{};functions.vkGetInstanceProcAddr=vk::GetInstanceProcAddr;functions.vkGetDeviceProcAddr=vk::GetDeviceProcAddr;
        VmaAllocatorCreateInfo memory{};memory.instance=instance;memory.physicalDevice=physical;memory.device=device;
        memory.vulkanApiVersion=VK_API_VERSION_1_1;memory.pVulkanFunctions=&functions;
        vk::check(vmaCreateAllocator(&memory,&allocator),"VMA allocator");
        loadPipelineCache(properties);
        VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pool.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;pool.queueFamilyIndex=family;
        vk::check(vk::CreateCommandPool(device,&pool,nullptr,&commands),"Command pool");
        std::array<VkCommandBuffer,2> cb;VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=commands;ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ca.commandBufferCount=2;
        vk::check(vk::AllocateCommandBuffers(device,&ca,cb.data()),"Command buffers");
        for(uint32_t i=0;i<2;++i){auto& f=frames[i];f.command=cb[i];VkFenceCreateInfo fc{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};fc.flags=VK_FENCE_CREATE_SIGNALED_BIT;vk::check(vk::CreateFence(device,&fc,nullptr,&f.fence),"Frame fence");
            VkSemaphoreCreateInfo sc{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};vk::check(vk::CreateSemaphore(device,&sc,nullptr,&f.acquired),"Acquire semaphore");}
        if(timestampBits){VkQueryPoolCreateInfo q{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};q.queryType=VK_QUERY_TYPE_TIMESTAMP;q.queryCount=4;vk::check(vk::CreateQueryPool(device,&q,nullptr,&timestamps),"Timestamp pool");}

        upload(assets,scene.assets);uploadWorldTextures();
        avatarWords=loadAvatar(shaderDirectory/"avatar.dwp");need(avatarWords.size()*4<=properties.limits.maxStorageBufferRange,"Avatar storage limit");upload(avatarData,avatarWords);
        geometry.rebuild(scene);upload(triangleRefs,geometry.triangleRefs);
        uploadTexture(sunTexture,shaderDirectory/"visuals/celestial.dwc",1);
        uploadTexture(moonTexture,shaderDirectory/"visuals/celestial.dwc",8);
        auto stars=read_file(shaderDirectory/"visuals/stars.dws",1024*1024);
        need(stars.size()>=16&&std::memcmp(stars.data(),"DWSTR1\0\0",8)==0,"Stars header");
        uint32_t starCount=0,stride=0;std::memcpy(&starCount,stars.data()+8,4);std::memcpy(&stride,stars.data()+12,4);
        need(stride==16&&starCount<=9000&&starCount%6==0&&stars.size()==16+size_t(starCount)*stride,"Stars bounds");
        std::vector<uint32_t> starWords(size_t(starCount)*4);std::memcpy(starWords.data(),stars.data()+16,starWords.size()*4);upload(starData,starWords);
        createPipelines();savePipelineCache();targets();
    }catch(...){cleanup();throw;}
}
void Renderer::dropTargets(){
 visuals->invalidate();
 if(descriptors)vk::DestroyDescriptorPool(device,descriptors,nullptr);descriptors=VK_NULL_HANDLE;
 for(auto& f:frames){
  for(auto& fb:f.postFrames){if(fb)vk::DestroyFramebuffer(device,fb,nullptr);fb=VK_NULL_HANDLE;}
  for(auto* fb:{&f.worldFrame,&f.actorFrame,&f.shadowFrame,&f.handFrame}){if(*fb)vk::DestroyFramebuffer(device,*fb,nullptr);*fb=VK_NULL_HANDLE;}
  for(auto& image:f.targets)free(image);
  for(auto* b:{&f.hits,&f.capture,&f.overlay,&f.instances,&f.indirect,&f.uniform,&f.projection,&f.extras})free(*b);
  f.uiRevision=f.geometryRevision=UINT64_MAX;f.worldSet=VK_NULL_HANDLE;f.postSets.fill(VK_NULL_HANDLE);f.submitted=false;
 }
 free(held);
 for(auto s:presented)vk::DestroySemaphore(device,s,nullptr);presented.clear();images.clear();
 if(swapchain)vk::DestroySwapchainKHR(device,swapchain,nullptr);swapchain=VK_NULL_HANDLE;
}
void Renderer::targets(){
    VkSurfaceCapabilitiesKHR caps;vk::check(vk::GetPhysicalDeviceSurfaceCapabilitiesKHR(physical,surface,&caps),"Surface capabilities");
    need(caps.supportedUsageFlags&VK_IMAGE_USAGE_TRANSFER_DST_BIT,"Surface transfer-destination unavailable");
    RECT r;GetClientRect(window,&r);
    extent=caps.currentExtent.width!=UINT32_MAX?caps.currentExtent:VkExtent2D{std::clamp(uint32_t(std::max(1L,r.right)),caps.minImageExtent.width,caps.maxImageExtent.width),std::clamp(uint32_t(std::max(1L,r.bottom)),caps.minImageExtent.height,caps.maxImageExtent.height)};
    need(extent.width&&extent.height&&extent.width<=3840&&extent.height<=2160,"Viewer framebuffer limit is 3840x2160");
    uint32_t count=0;vk::check(vk::GetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&count,nullptr),"Surface formats");std::vector<VkSurfaceFormatKHR> formats(count);vk::check(vk::GetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&count,formats.data()),"Surface format list");
    format=VK_FORMAT_UNDEFINED;VkColorSpaceKHR colorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    for(auto& f:formats)if((f.format==VK_FORMAT_B8G8R8A8_UNORM||f.format==VK_FORMAT_R8G8B8A8_UNORM)&&f.colorSpace==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR){format=f.format;colorSpace=f.colorSpace;break;}
    need(format!=VK_FORMAT_UNDEFINED,"UNORM surface format unavailable");
    VkFormatProperties fp;vk::GetPhysicalDeviceFormatProperties(physical,format,&fp);need(fp.optimalTilingFeatures&VK_FORMAT_FEATURE_BLIT_DST_BIT,"Surface blit-destination unavailable");
    VkPresentModeKHR mode=VK_PRESENT_MODE_FIFO_KHR;
    if(!vsync){vk::check(vk::GetPhysicalDeviceSurfacePresentModesKHR(physical,surface,&count,nullptr),"Present modes");std::vector<VkPresentModeKHR> modes(count);vk::check(vk::GetPhysicalDeviceSurfacePresentModesKHR(physical,surface,&count,modes.data()),"Present mode list");
        if(std::find(modes.begin(),modes.end(),VK_PRESENT_MODE_IMMEDIATE_KHR)!=modes.end())mode=VK_PRESENT_MODE_IMMEDIATE_KHR;else if(std::find(modes.begin(),modes.end(),VK_PRESENT_MODE_MAILBOX_KHR)!=modes.end())mode=VK_PRESENT_MODE_MAILBOX_KHR;}
    VkSwapchainCreateInfoKHR sw{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};sw.surface=surface;sw.minImageCount=caps.minImageCount+1;if(caps.maxImageCount)sw.minImageCount=std::min(sw.minImageCount,caps.maxImageCount);
    sw.imageFormat=format;sw.imageColorSpace=colorSpace;sw.imageExtent=extent;sw.imageArrayLayers=1;sw.imageUsage=VK_IMAGE_USAGE_TRANSFER_DST_BIT;sw.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE;sw.preTransform=caps.currentTransform;
    sw.compositeAlpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if(!(caps.supportedCompositeAlpha&sw.compositeAlpha))sw.compositeAlpha=VkCompositeAlphaFlagBitsKHR(caps.supportedCompositeAlpha&(~caps.supportedCompositeAlpha+1));
    sw.presentMode=mode;sw.clipped=VK_TRUE;vk::check(vk::CreateSwapchainKHR(device,&sw,nullptr,&swapchain),"CreateSwapchain");
    vk::check(vk::GetSwapchainImagesKHR(device,swapchain,&count,nullptr),"Swapchain image count");images.resize(count);vk::check(vk::GetSwapchainImagesKHR(device,swapchain,&count,images.data()),"Swapchain images");
    VkSemaphoreCreateInfo sc{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};presented.resize(count,VK_NULL_HANDLE);for(auto& s:presented)vk::check(vk::CreateSemaphore(device,&sc,nullptr,&s),"Present semaphore");

    constexpr uint32_t sets=uint32_t(2*(passCount+1));
    VkDescriptorPoolSize sizes[]={{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,sets*8},{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,sets*3},{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,sets*12}};
    VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};dp.maxSets=sets;dp.poolSizeCount=3;dp.pPoolSizes=sizes;
    vk::check(vk::CreateDescriptorPool(device,&dp,nullptr,&descriptors),"Descriptor pool");
    std::vector<VkDescriptorSetLayout> layouts;for(uint32_t i=0;i<2;++i){layouts.push_back(worldLayout);for(size_t p=0;p<passCount;++p)layouts.push_back(postLayout);}
    std::vector<VkDescriptorSet> allocated(sets);VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};da.descriptorPool=descriptors;da.descriptorSetCount=sets;da.pSetLayouts=layouts.data();
    vk::check(vk::AllocateDescriptorSets(device,&da,allocated.data()),"Descriptor sets");
    constexpr VkImageUsageFlags colorUse=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    constexpr VkImageUsageFlags depthUse=VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    constexpr VkMemoryPropertyFlags host=VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    image(held,VK_FORMAT_R8G8B8A8_UNORM,extent,colorUse);
    for(uint32_t i=0;i<2;++i){
        auto& f=frames[i];size_t base=size_t(i)*(passCount+1);f.worldSet=allocated[base];
        for(size_t p=0;p<passCount;++p)f.postSets[p]=allocated[base+1+p];
        for(auto t:{Target::Raw,Target::Sky,Target::Resolved,Target::Surface,Target::Composite,Target::Merged,Target::Film,Target::Output})
            image(f.target(t),VK_FORMAT_R8G8B8A8_UNORM,extent,colorUse);
        float cloudScale=std::min(1.f,960.f/float(extent.width));
        VkExtent2D cloudExtent{std::max(1u,uint32_t(std::lround(extent.width*cloudScale))),std::max(1u,uint32_t(std::lround(extent.height*cloudScale)))};
        image(f.target(Target::Cloud),VK_FORMAT_R8G8B8A8_UNORM,cloudExtent,colorUse);
        image(f.target(Target::CloudDepth),VK_FORMAT_D32_SFLOAT,cloudExtent,depthUse);
        image(f.target(Target::Volume),VK_FORMAT_R8G8B8A8_UNORM,{640,360},colorUse);
        image(f.target(Target::Probe),VK_FORMAT_R32G32_UINT,extent,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        for(auto t:{Target::Depth,Target::HandDepth})image(f.target(t),VK_FORMAT_D32_SFLOAT,extent,depthUse);
        image(f.target(Target::Shadow),VK_FORMAT_D32_SFLOAT,{1024,1024},depthUse);
        buffer(f.hits,probes?VkDeviceSize(extent.width)*extent.height*8:8,VK_BUFFER_USAGE_TRANSFER_DST_BIT,host);
        buffer(f.overlay,(16+4+1280*720)*4,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,host);std::memset(f.overlay.mapped,0,size_t(f.overlay.bytes));
        grow(f.instances,std::max<size_t>(16,geometry.instances.size()*sizeof(RasterInstance)),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,host);
        grow(f.indirect,std::max<size_t>(16,geometry.batches.size()*2*sizeof(VkDrawIndirectCommand)),VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,host);
        buffer(f.uniform,416,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,host);buffer(f.projection,80,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,host);buffer(f.extras,64,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,host);
        std::memset(f.uniform.mapped,0,416);std::memset(f.projection.mapped,0,80);std::memset(f.extras.mapped,0,64);
        if(worldSamples==VK_SAMPLE_COUNT_1_BIT)f.worldFrame=framebuffer(worldPass,{&f.target(Target::Raw),&f.target(Target::Probe),&f.target(Target::Depth)},extent);
         else {
          image(f.target(Target::RawMs),VK_FORMAT_R8G8B8A8_UNORM,extent,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,1,1,false,worldSamples);
          image(f.target(Target::DepthMs),VK_FORMAT_D32_SFLOAT,extent,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT,1,1,false,worldSamples);
          image(f.target(Target::MsColorResolved),VK_FORMAT_R8G8B8A8_UNORM,extent,colorUse);
          f.worldFrame=framebuffer(worldPass,{&f.target(Target::RawMs),&f.target(Target::DepthMs),&f.target(Target::MsColorResolved)},extent);
          f.postFrames[size_t(Pass::DepthResolve)]=framebuffer(depthResolvePass,{&f.target(Target::Raw),&f.target(Target::Depth)},extent);
         }
        f.actorFrame=framebuffer(colorDepthLoadPass,{&f.target(Target::Raw),&f.target(Target::Depth)},extent);
        f.shadowFrame=framebuffer(shadowPass,{&f.target(Target::Shadow)},{1024,1024});
        f.handFrame=framebuffer(handPass,{&f.target(Target::Film),&f.target(Target::HandDepth)},extent);
        auto set=[&](Pass p,Target t,VkRenderPass rp){f.postFrames[size_t(p)]=framebuffer(rp,{&f.target(t)},f.target(t).extent);};
        set(Pass::Background,Target::Sky,colorPass);set(Pass::Celestial,Target::Sky,colorLoadPass);
        f.postFrames[size_t(Pass::Cloud)]=framebuffer(colorDepthPass,{&f.target(Target::Cloud),&f.target(Target::CloudDepth)},cloudExtent);
        set(Pass::Resolve,Target::Resolved,colorPass);set(Pass::Ambient,Target::Raw,colorPass);set(Pass::Surface,Target::Surface,colorPass);
        set(Pass::Volume,Target::Volume,colorPass);set(Pass::Composite,Target::Composite,colorPass);
        set(Pass::Merge,Target::Merged,colorPass);set(Pass::MergeFused,Target::Merged,colorPass);set(Pass::Film,Target::Film,colorPass);set(Pass::Overlay,Target::Output,colorPass);
    }
    initializeImages();
    for(uint32_t i=0;i<2;++i)updateDescriptors(frames[i],i);
}
void Renderer::initializeImages(){
 auto& f=frames[0];vk::check(vk::WaitForFences(device,1,&f.fence,VK_TRUE,UINT64_MAX),"Image init wait");
 vk::check(vk::ResetFences(device,1,&f.fence),"Image init fence");vk::check(vk::ResetCommandBuffer(f.command,0),"Image init reset");
 VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;vk::check(vk::BeginCommandBuffer(f.command,&begin),"Image init begin");
 auto clear=[&](Image& target){
  if(!target.handle||target.samples!=VK_SAMPLE_COUNT_1_BIT)return;
  barrier(f.command,target,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  VkImageSubresourceRange range{target.aspect,0,1,0,target.layers};
  if(target.aspect==VK_IMAGE_ASPECT_DEPTH_BIT){VkClearDepthStencilValue value{0,0};vk::CmdClearDepthStencilImage(f.command,target.handle,target.layout,&value,1,&range);}
  else{VkClearColorValue value{};if(target.format==VK_FORMAT_R32G32_UINT){value.uint32[0]=UINT32_MAX;value.uint32[1]=0x7f800000;}vk::CmdClearColorImage(f.command,target.handle,target.layout,&value,1,&range);}
  barrier(f.command,target,target.format==VK_FORMAT_R32G32_UINT?VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
 };
 for(auto& frame:frames)for(auto& t:frame.targets)clear(t);clear(held);
 vk::check(vk::EndCommandBuffer(f.command),"Image init end");
 VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&f.command;vk::check(vk::QueueSubmit(queue,1,&submit,f.fence),"Image init submit");
 vk::check(vk::WaitForFences(device,1,&f.fence,VK_TRUE,UINT64_MAX),"Image init finish");
}
void Renderer::updateDescriptors(Frame& f,uint32_t slot){
 auto writeBuffer=[&](VkDescriptorSet set,uint32_t binding,Buffer& b,bool uniform){
  VkDescriptorBufferInfo info{b.handle,0,b.bytes};VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
  w.dstSet=set;w.dstBinding=binding;w.descriptorCount=1;w.descriptorType=uniform?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;w.pBufferInfo=&info;
  vk::UpdateDescriptorSets(device,1,&w,0,nullptr);
 };
 writeBuffer(f.worldSet,1,assets,false);writeBuffer(f.worldSet,4,f.overlay,false);writeBuffer(f.worldSet,5,avatarData,false);
 writeBuffer(f.worldSet,6,f.instances,false);writeBuffer(f.worldSet,7,triangleRefs,false);writeBuffer(f.worldSet,8,f.uniform,true);
 {
  VkDescriptorImageInfo info{worldSampler,worldTextures.view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
  VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};write.dstSet=f.worldSet;write.dstBinding=9;write.descriptorCount=1;
  write.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;write.pImageInfo=&info;vk::UpdateDescriptorSets(device,1,&write,0,nullptr);
 }
 for(size_t pass=0;pass<passCount;++pass){
  auto set=f.postSets[pass];
  Target input=Target::Raw;
  switch(Pass(pass)){
   case Pass::Ambient:input=Target::Resolved;break;
   case Pass::Surface:input=Target::Raw;break;
   case Pass::Volume:case Pass::Composite:input=Target::Surface;break;
   case Pass::Merge:input=Target::Composite;break;
   case Pass::MergeFused:input=Target::Surface;break;
   case Pass::Film:input=Target::Merged;break;
   case Pass::Overlay:input=Target::Film;break;
   default:break;
  }
  auto imageInfo=[&](uint32_t binding,Image& image,bool bilinear=false){
   VkDescriptorImageInfo info{bilinear?linear:nearest,image.view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
   VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};w.dstSet=set;w.dstBinding=binding;w.descriptorCount=1;w.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;w.pImageInfo=&info;
   vk::UpdateDescriptorSets(device,1,&w,0,nullptr);
  };
  bool film=Pass(pass)==Pass::Film;
  imageInfo(0,Pass(pass)==Pass::DepthResolve&&worldSamples!=VK_SAMPLE_COUNT_1_BIT?f.target(Target::DepthMs):f.target(input),film);imageInfo(1,f.target(Target::Depth));imageInfo(2,Pass(pass)==Pass::DepthResolve&&worldSamples!=VK_SAMPLE_COUNT_1_BIT?f.target(Target::MsColorResolved):f.target(Target::Shadow));imageInfo(3,f.target(Target::Volume));
  imageInfo(4,f.target(Target::Cloud));imageInfo(5,f.target(Target::Sky));imageInfo(6,f.target(Target::Cloud));
  imageInfo(7,frames[1-slot].target(Target::Merged),film);imageInfo(9,held,film);
  imageInfo(13,sunTexture);imageInfo(14,moonTexture);imageInfo(15,f.target(Target::CloudDepth));
  writeBuffer(set,8,f.uniform,true);writeBuffer(set,10,f.projection,true);writeBuffer(set,11,f.overlay,false);writeBuffer(set,12,f.extras,true);writeBuffer(set,16,starData,false);
 }
}
void Renderer::wait(Frame& f,uint32_t slot){
    vk::check(vk::WaitForFences(device,1,&f.fence,VK_TRUE,UINT64_MAX),"Frame wait");
    if(f.submitted&&timestamps&&f.serial>=30){
        uint64_t t[2];vk::check(vk::GetQueryPoolResults(device,timestamps,slot*2,2,sizeof(t),t,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT),"Timestamp result");
        uint64_t mask=timestampBits>=64?UINT64_MAX:((uint64_t(1)<<timestampBits)-1);double ms=double((t[1]-t[0])&mask)*timestampPeriod/1e6;
        if(f.worldSample){if(gpuMs.size()<65536)gpuMs.push_back(ms);else gpuMs[size_t(sequence)%gpuMs.size()]=ms;}
    }
    f.submitted=false;
}
void Renderer::resize(){vk::check(vk::DeviceWaitIdle(device),"Resize idle");for(uint32_t i=0;i<2;++i)wait(frames[i],i);dropTargets();targets();++resizeCount;}
void Renderer::drain(){for(uint32_t i=0;i<2;++i)wait(frames[i],i);vk::check(vk::DeviceWaitIdle(device),"Drain idle");}

void Renderer::updateWorld(const std::vector<uint32_t>& slots){
 if(!slots.empty())geometry.update(scene,slots);
}
void Renderer::drawGeometry(Frame& f,const Params& params,bool shadow,uint32_t indirectOffset,uint32_t count){
 // Both WKE hosts execute world draws through the same allocation-free recorder.
 // This host uses SSBO vertex pulling, so it supplies no vertex/index bindings.
 wke::vulkan::WorldCommandDispatch commands{};
 commands.bind_pipeline=vk::CmdBindPipeline;
 commands.draw=vk::CmdDraw;
 commands.draw_indirect=vk::CmdDrawIndirect;
 WkeVkDrawPacket packet{};
 packet.pipeline=wke::vulkan::handle_bits(shadow?shadowPipeline:worldPipeline);
 bool bindPipeline=true;
 vk::CmdBindDescriptorSets(f.command,VK_PIPELINE_BIND_POINT_GRAPHICS,worldPipelineLayout,0,1,&f.worldSet,0,nullptr);
 vk::CmdPushConstants(f.command,worldPipelineLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(params),&params);
 if(indirectEnabled){
  packet.operation=WKE_VK_DRAW_INDIRECT;
  packet.indirect_buffer=wke::vulkan::handle_bits(f.indirect.handle);
  packet.indirect_stride=sizeof(VkDrawIndirectCommand);
  for(uint32_t start=0;start<count;){
   uint32_t n=std::min(count-start,maxIndirectCount);
   packet.indirect_offset=(VkDeviceSize(indirectOffset)+start)*sizeof(VkDrawIndirectCommand);
   packet.indirect_count=n;
   wke::vulkan::execute_world_command(commands,f.command,packet,bindPipeline);
   bindPipeline=false;start+=n;
  }
 }else{
  packet.operation=WKE_VK_DRAW;
  auto* draws=static_cast<const VkDrawIndirectCommand*>(f.indirect.mapped)+indirectOffset;
  for(uint32_t i=0;i<count;++i){
   const auto& draw=draws[i];
   packet.vertex_count=draw.vertexCount;packet.instance_count=draw.instanceCount;
   packet.first_vertex=draw.firstVertex;packet.first_instance=draw.firstInstance;
   wke::vulkan::execute_world_command(commands,f.command,packet,bindPipeline);
   bindPipeline=false;
  }
 }
}
void Renderer::drawActor(Frame& f,Params params,bool firstPerson,bool shadow){
 if(avatarWords.empty()||avatarWords[0]<6)return;
 if(firstPerson&&avatarWords[0]<7)return;
 params.settings[3]=firstPerson?2:1;
 vk::CmdBindPipeline(f.command,VK_PIPELINE_BIND_POINT_GRAPHICS,shadow?shadowPipeline:actorPipeline);
 vk::CmdBindDescriptorSets(f.command,VK_PIPELINE_BIND_POINT_GRAPHICS,worldPipelineLayout,0,1,&f.worldSet,0,nullptr);
 vk::CmdPushConstants(f.command,worldPipelineLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(params),&params);
 for(uint32_t bone=firstPerson?6:0;bone<(firstPerson?7:6);++bone){
  uint32_t d=avatarWords[1]+bone*12;
  vk::CmdDraw(f.command,avatarWords[d+6]*3,1,avatarWords[d+7]*3,bone);
 }
}
void Renderer::post(Frame& f,Pass pass,Target output,const Params& params){
 Image& target=f.target(output);barrier(f.command,target,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
 VkRenderPass rp=pass==Pass::Cloud?colorDepthPass:pass==Pass::Celestial?colorLoadPass:colorPass;
 VkClearValue clear[2]{};clear[0].color.float32[3]=pass==Pass::Cloud?0.f:1.f;clear[1].depthStencil={0,0};
 if(pass==Pass::Cloud)barrier(f.command,f.target(Target::CloudDepth),VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
 beginPass(f.command,rp,f.postFrames[size_t(pass)],target.extent,{clear,pass==Pass::Cloud?2u:1u});
 vk::CmdBindPipeline(f.command,VK_PIPELINE_BIND_POINT_GRAPHICS,postPipelines[size_t(pass)]);
 vk::CmdBindDescriptorSets(f.command,VK_PIPELINE_BIND_POINT_GRAPHICS,postPipelineLayout,0,1,&f.postSets[size_t(pass)],0,nullptr);
 vk::CmdPushConstants(f.command,postPipelineLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(params),&params);
 if(pass!=Pass::Cloud||videoSettings[5]>.5f)vk::CmdDraw(f.command,pass==Pass::Celestial?12u:3u,1,0,0);
 if(pass==Pass::Celestial){
  vk::CmdBindPipeline(f.command,VK_PIPELINE_BIND_POINT_GRAPHICS,starPipeline);
  vk::CmdDraw(f.command,uint32_t(starData.bytes/16),1,0,0);
 }
 vk::CmdEndRenderPass(f.command);
 barrier(f.command,target,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
 if(pass==Pass::Cloud)barrier(f.command,f.target(Target::CloudDepth),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
}
bool Renderer::draw(const Camera& camera,bool capture,const std::filesystem::path& path,bool verify){
 RECT r;GetClientRect(window,&r);if(r.right<=0||r.bottom<=0||IsIconic(window))return false;
 if(uint32_t(r.right)!=extent.width||uint32_t(r.bottom)!=extent.height)resize();
 uint32_t slot=uint32_t(sequence%2);auto& f=frames[slot];wait(f,slot);
 uint32_t index=0;VkResult acquire=vk::AcquireNextImageKHR(device,swapchain,UINT64_MAX,f.acquired,VK_NULL_HANDLE,&index);
 if(acquire==VK_ERROR_OUT_OF_DATE_KHR){resize();return false;}if(acquire!=VK_SUBOPTIMAL_KHR)vk::check(acquire,"Acquire image");
 bool recreate=acquire==VK_SUBOPTIMAL_KHR,descriptorsDirty=false;
 constexpr VkMemoryPropertyFlags hostMemory=VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
 if(capture&&!f.capture.handle)buffer(f.capture,VkDeviceSize(extent.width)*extent.height*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT,hostMemory);
 if((uiPixels.size()+16)*4>f.overlay.bytes){
  grow(f.overlay,(uiPixels.size()+16)*4,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,hostMemory);f.uiRevision=UINT64_MAX;descriptorsDirty=true;
 }
 if(f.uiRevision!=uiRevision){std::memset(f.overlay.mapped,0,16);if(!uiPixels.empty())std::memcpy(f.overlay.mapped,uiPixels.data(),uiPixels.size()*4);f.uiRevision=uiRevision;}
 static_cast<uint32_t*>(f.overlay.mapped)[3]=std::bit_cast<uint32_t>(uiTime);
 auto* options=static_cast<uint32_t*>(f.overlay.mapped)+std::max<size_t>(4,uiPixels.size());
 std::memcpy(options,videoSettings.data(),videoSettings.size()*sizeof(float));std::memcpy(options+7,climateSettings.data(),climateSettings.size()*sizeof(float));
 if(f.geometryRevision!=geometry.revision){
  VkDeviceSize bytes=std::max<size_t>(16,geometry.instances.size()*sizeof(RasterInstance));
  bool replaced=grow(f.instances,bytes,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,hostMemory);
  descriptorsDirty|=replaced;
  if(replaced||f.geometryRevision==UINT64_MAX){
   if(!geometry.instances.empty()){
    size_t count=geometry.instances.size()*sizeof(RasterInstance);
    std::memcpy(f.instances.mapped,geometry.instances.data(),count);patchBytes+=count;++patchUploads;
   }
  }else geometry.uploadsSince(f.geometryRevision,[&](uint32_t first,uint32_t count){
   size_t offset=size_t(first)*sizeof(RasterInstance),length=size_t(count)*sizeof(RasterInstance);
   std::memcpy(static_cast<uint8_t*>(f.instances.mapped)+offset,geometry.instances.data()+first,length);
   patchBytes+=length;++patchUploads;
  });
  f.geometryRevision=geometry.revision;
 }
 if(descriptorsDirty)updateDescriptors(f,slot);
 auto p=parameters(camera,extent.width,extent.height,probes);p.origin[3]=std::tan(videoSettings[1]*.00872664626f);p.settings[3]=0;
 // The independent geometry oracle compares original source alpha, not hardware
 // filtered coverage. Its explicit point-source mode does not verify mip filtering.
 if(worldSamples!=VK_SAMPLE_COUNT_1_BIT)p.settings[0]|=4u;
  if(verify){p.settings[0]|=2u;++sourceTextureOracleFrames;}else{p.settings[0]&=~2u;++filteredTextureFrames;}
 std::copy_n(actorPose.actor,4,p.actor);std::copy_n(actorPose.pose,4,p.pose);std::copy_n(actorPose.avatar,4,p.avatar);
 auto visual=visuals->prepare(camera,p,videoSettings[2],videoSettings,worldSample,actorPose.avatar[0]>1.5f);
 std::memcpy(f.uniform.mapped,visual.scene.data(),416);std::memcpy(f.projection.mapped,visual.projection.data(),80);std::memcpy(f.extras.mapped,visual.extras.data(),64);
 auto& draws=drawCommands;draws.clear();draws.reserve(geometry.batches.size()*2);
 for(const auto& batch:geometry.batches)if(RasterScene::visible(batch,p))draws.push_back({batch.vertexCount,batch.instanceCount,batch.firstVertex,batch.firstInstance});
 uint32_t visibleCount=uint32_t(draws.size()),shadowOffset=visibleCount;
 auto shadowVisible=[&](const RasterBatch& batch){
  if(batch.buried)return false;
  V3 center=(batch.low+batch.high)*.5f-camera.position,half=(batch.high-batch.low)*.5f;
  const float* m=visual.scene.data()+16;
  for(int row=0;row<3;++row){
   float c=m[row]*center.x+m[row+4]*center.y+m[row+8]*center.z+m[row+12];
   float e=std::abs(m[row])*half.x+std::abs(m[row+4])*half.y+std::abs(m[row+8])*half.z;
   if(c+e<(row==2?0.f:-1.f)||c-e>1.f)return false;
  }
  return true;
 };
 if(visual.scene[52]>.5f)for(const auto& batch:geometry.batches)if(shadowVisible(batch))draws.push_back({batch.vertexCount,batch.instanceCount,batch.firstVertex,batch.firstInstance});
 uint32_t shadowCount=uint32_t(draws.size())-shadowOffset;
 VkDeviceSize drawBytes=std::max<size_t>(16,draws.size()*sizeof(VkDrawIndirectCommand));
 grow(f.indirect,drawBytes,VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,hostMemory);
 if(!draws.empty())std::memcpy(f.indirect.mapped,draws.data(),draws.size()*sizeof(VkDrawIndirectCommand));
 vk::check(vk::ResetFences(device,1,&f.fence),"Reset frame fence");vk::check(vk::ResetCommandBuffer(f.command,0),"Reset frame command");
 VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;vk::check(vk::BeginCommandBuffer(f.command,&begin),"Begin frame");
 if(timestamps){vk::CmdResetQueryPool(f.command,timestamps,slot*2,2);vk::CmdWriteTimestamp(f.command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,timestamps,slot*2);}
 // CPU writes completed before QueueSubmit; HOST_COHERENT data becomes available
 // to the shader/indirect stages through Vulkan's submit host-write dependency.
 barrier(f.command,f.target(Target::Shadow),VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
 VkClearValue shadowClear{};shadowClear.depthStencil={0,0};
 beginPass(f.command,shadowPass,f.shadowFrame,{1024,1024},{&shadowClear,1});
 if(visual.scene[52]>.5f){
  drawGeometry(f,p,true,shadowOffset,shadowCount);
  if(p.avatar[0]>.5f&&p.avatar[0]<1.5f)drawActor(f,p,false,true);
 }
 vk::CmdEndRenderPass(f.command);barrier(f.command,f.target(Target::Shadow),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
 barrier(f.command,f.target(Target::Raw),VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
 barrier(f.command,f.target(Target::Depth),VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
 VkClearValue worldClear[3]{};worldClear[0].color.float32[3]=1;
 uint32_t clearCount=3;
 if(worldSamples==VK_SAMPLE_COUNT_1_BIT){
  barrier(f.command,f.target(Target::Probe),VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
  worldClear[1].color.uint32[0]=UINT32_MAX;worldClear[1].color.uint32[1]=0x7f800000;worldClear[2].depthStencil={0,0};
 }else{
  worldClear[0].color.float32[3]=0;
  barrier(f.command,f.target(Target::MsColorResolved),VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
  barrier(f.command,f.target(Target::RawMs),VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
  barrier(f.command,f.target(Target::DepthMs),VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
  worldClear[1].depthStencil={0,0};clearCount=2;
 }
 beginPass(f.command,worldPass,f.worldFrame,extent,{worldClear,clearCount});drawGeometry(f,p,false,0,visibleCount);vk::CmdEndRenderPass(f.command);
 if(worldSamples!=VK_SAMPLE_COUNT_1_BIT){
  barrier(f.command,f.target(Target::DepthMs),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  barrier(f.command,f.target(Target::MsColorResolved),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  VkClearValue clear[2]{};clear[1].depthStencil={0,0};
  beginPass(f.command,depthResolvePass,f.postFrames[size_t(Pass::DepthResolve)],extent,{clear,2});
  vk::CmdBindPipeline(f.command,VK_PIPELINE_BIND_POINT_GRAPHICS,postPipelines[size_t(Pass::DepthResolve)]);
  auto set=f.postSets[size_t(Pass::DepthResolve)];vk::CmdBindDescriptorSets(f.command,VK_PIPELINE_BIND_POINT_GRAPHICS,postPipelineLayout,0,1,&set,0,nullptr);
  vk::CmdDraw(f.command,3,1,0,0);vk::CmdEndRenderPass(f.command);
 }
 if(p.avatar[0]>.5f&&p.avatar[0]<1.5f){
  beginPass(f.command,colorDepthLoadPass,f.actorFrame,extent,{});drawActor(f,p,false);vk::CmdEndRenderPass(f.command);
 }
 barrier(f.command,f.target(Target::Raw),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
 barrier(f.command,f.target(Target::Depth),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
 post(f,Pass::Background,Target::Sky,p);
 post(f,Pass::Celestial,Target::Sky,p);
 post(f,Pass::Cloud,Target::Cloud,p);
 post(f,Pass::Resolve,Target::Resolved,p);
 post(f,Pass::Ambient,Target::Raw,p);
 post(f,Pass::Surface,Target::Surface,p);
 post(f,Pass::Volume,Target::Volume,p);
 // Same Vulkan/native-background fast path as the mod. The fused source keeps
 // the intermediate RGBA8 rounding before height haze; history remains Merged.
 bool fused=(uint32_t(visual.scene[53])&4u)!=0u&&visual.scene[54]==0.f&&visual.scene[55]<.5f;
 if(fused)post(f,Pass::MergeFused,Target::Merged,p);
 else{post(f,Pass::Composite,Target::Composite,p);post(f,Pass::Merge,Target::Merged,p);}
 post(f,Pass::Film,Target::Film,p);
 // The RAW pre-film image is the history. Neither the hand nor HUD enters it.
 if(visual.allowCapture&&visual.captureHeld){
  auto& source=f.target(Target::Merged);barrier(f.command,source,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);barrier(f.command,held,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  VkImageCopy copy{};copy.srcSubresource=copy.dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};copy.extent={extent.width,extent.height,1};
  vk::CmdCopyImage(f.command,source.handle,source.layout,held.handle,held.layout,1,&copy);
  barrier(f.command,source,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);barrier(f.command,held,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
 }
 if(p.avatar[0]>1.5f){
  barrier(f.command,f.target(Target::Film),VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);barrier(f.command,f.target(Target::HandDepth),VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
  VkClearValue clear[2]{};clear[1].depthStencil={0,0};beginPass(f.command,handPass,f.handFrame,extent,{clear,2});drawActor(f,p,true);vk::CmdEndRenderPass(f.command);
  barrier(f.command,f.target(Target::Film),VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
 }
 post(f,Pass::Overlay,Target::Output,p);
 if(timestamps)vk::CmdWriteTimestamp(f.command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,timestamps,slot*2+1);
 auto& output=f.target(Target::Output);barrier(f.command,output,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
 VkImageMemoryBarrier swap{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};swap.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED;swap.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
 swap.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;swap.srcQueueFamilyIndex=swap.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;swap.image=images[index];swap.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
 vk::CmdPipelineBarrier(f.command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&swap);
 VkImageBlit blit{};blit.srcSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};blit.dstSubresource=blit.srcSubresource;blit.srcOffsets[1]={int32_t(extent.width),int32_t(extent.height),1};blit.dstOffsets[1]=blit.srcOffsets[1];
 vk::CmdBlitImage(f.command,output.handle,output.layout,images[index],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&blit,VK_FILTER_NEAREST);
 if(capture){VkBufferImageCopy copy{};copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};copy.imageExtent={extent.width,extent.height,1};vk::CmdCopyImageToBuffer(f.command,output.handle,output.layout,f.capture.handle,1,&copy);}
 if(probes){
  auto& probe=f.target(Target::Probe);barrier(f.command,probe,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
  VkBufferImageCopy copy{};copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};copy.imageExtent={extent.width,extent.height,1};
  vk::CmdCopyImageToBuffer(f.command,probe.handle,probe.layout,f.hits.handle,1,&copy);
 }
 swap.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;swap.newLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;swap.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;swap.dstAccessMask=0;
 vk::CmdPipelineBarrier(f.command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,nullptr,0,nullptr,1,&swap);
 VkMemoryBarrier host{VK_STRUCTURE_TYPE_MEMORY_BARRIER};host.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;host.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
 if(capture||probes)vk::CmdPipelineBarrier(f.command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&host,0,nullptr,0,nullptr);
 vk::check(vk::EndCommandBuffer(f.command),"End frame");
 VkPipelineStageFlags stage=VK_PIPELINE_STAGE_TRANSFER_BIT;
 VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.waitSemaphoreCount=1;submit.pWaitSemaphores=&f.acquired;submit.pWaitDstStageMask=&stage;submit.commandBufferCount=1;submit.pCommandBuffers=&f.command;submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&presented[index];
 vk::check(vk::QueueSubmit(queue,1,&submit,f.fence),"Submit frame");f.submitted=true;f.serial=sequence;f.worldSample=worldSample;visuals->commit();
 VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};pi.waitSemaphoreCount=1;pi.pWaitSemaphores=&presented[index];pi.swapchainCount=1;pi.pSwapchains=&swapchain;pi.pImageIndices=&index;
 VkResult pr=vk::QueuePresentKHR(queue,&pi);if(pr==VK_ERROR_OUT_OF_DATE_KHR||pr==VK_SUBOPTIMAL_KHR)recreate=true;else vk::check(pr,"Present frame");
    if(capture||verify){
        wait(f,slot);
        if(capture)write_png(path,extent.width,extent.height,static_cast<const uint8_t*>(f.capture.mapped));
        if(capture&&probes){auto dump=path;dump+=".hits";std::ofstream out(dump,std::ios::binary);out.write(static_cast<const char*>(f.hits.mapped),std::streamsize(f.hits.bytes));if(!out)throw std::runtime_error("Probe dump failed");}
        if(verify){
            need(probes,"Verification requires probes");
            const uint32_t* gpu=static_cast<const uint32_t*>(f.hits.mapped);
            auto alphaComparable=[&](const Hit& hit){
                if(hit.triangle==UINT32_MAX||size_t(hit.triangle)+11>=scene.assets.size())return false;
                uint32_t flags=scene.assets[hit.triangle+7];
                // reference() uses raw alpha: no atlas remap or tint-alpha change.
                return (flags&8u)==0u||((flags&3u)==0u&&(scene.assets[hit.triangle+11]>>24)==255u);
            };
            auto matches=[&](const Hit& expected,uint32_t material,float distance,V3 ray){
                bool baselineDistance=expected.material==UINT32_MAX
                    ||std::abs(expected.distance-distance)<=.002f+.00005f*expected.distance;
                if(material==expected.material)return baselineDistance;
                if(expected.ambiguous||!baselineDistance||material>=scene.materials
                   ||expected.material>=scene.materials||!alphaComparable(expected))return false;
                auto alternative=scene.reference(camera.position,ray,material);
                // A tie is checked, not skipped: another CPU-confirmed source hit
                // must lie in the fixed original band and pass the old depth check.
                // This verifies geometric equivalence, not GPU material tie priority.
                if(alternative.material!=material||alternative.ambiguous||!alphaComparable(alternative)
                   ||!std::isfinite(alternative.distance)
                   ||std::abs(alternative.distance-expected.distance)>Scene::surfaceTieEpsilon
                   ||!(std::abs(alternative.distance-distance)<=.002f+.00005f*alternative.distance))return false;
                ++oracleEquivalent;return true;
            };
            std::vector<uint32_t> targeted(scene.materials,0);
            for(uint32_t y=0;y<extent.height;++y)for(uint32_t x=0;x<extent.width;++x){
                size_t at=(size_t(x)+size_t(extent.width)*y)*2;uint32_t id=gpu[at];
                if(id>=scene.materials)continue;
                ++materialPixels[id];
                // Pick interior horizontal pixels, including thin cable silhouettes.
                if(targeted[id]>=3||x==0||x+1==extent.width||gpu[at-2]!=id||gpu[at+2]!=id)continue;
                V3 ray=direction(p,x,y);auto expected=scene.reference(camera.position,ray);
                float distance=std::bit_cast<float>(gpu[at+1]);
                bool match=matches(expected,id,distance,ray);
                ++targeted[id];if(expected.ambiguous){++oracleAmbiguous;continue;}++oracleRays;if(!match){++oracleFailures;if(oracleDetails.size()<32)oracleDetails.push_back("{\"x\":"+std::to_string(x)+",\"y\":"+std::to_string(y)+",\"gpu\":"+std::to_string(id)+",\"expected\":"+std::to_string(expected.material)+",\"gpuDistance\":"+std::to_string(distance)+",\"cpuDistance\":"+std::to_string(expected.distance)+",\"details\":"+scene.diagnoseRay(camera.position,direction(p,x,y),distance,expected.material)+",\"gpuDetails\":"+scene.diagnoseRay(camera.position,direction(p,x,y),distance,gpu[at])+"}");}
            }
            for(uint32_t y=11;y<extent.height;y+=std::max(1u,extent.height/17))for(uint32_t x=7;x<extent.width;x+=std::max(1u,extent.width/23)){
                V3 ray=direction(p,x,y);auto expected=scene.reference(camera.position,ray);size_t at=(size_t(x)+size_t(extent.width)*y)*2;
                float distance=std::bit_cast<float>(gpu[at+1]);bool match=matches(expected,gpu[at],distance,ray);
                if(expected.ambiguous){++oracleAmbiguous;continue;}++oracleRays;if(!match){++oracleFailures;if(oracleDetails.size()<32)oracleDetails.push_back("{\"x\":"+std::to_string(x)+",\"y\":"+std::to_string(y)+",\"gpu\":"+std::to_string(gpu[at])+",\"expected\":"+std::to_string(expected.material)+",\"gpuDistance\":"+std::to_string(distance)+",\"cpuDistance\":"+std::to_string(expected.distance)+",\"details\":"+scene.diagnoseRay(camera.position,direction(p,x,y),distance,expected.material)+",\"gpuDetails\":"+scene.diagnoseRay(camera.position,direction(p,x,y),distance,gpu[at])+"}");}
            }
        }
    }
    ++sequence;if(recreate)resize();return true;
}

void Renderer::cleanup()noexcept{
 if(device){
  if(vk::DeviceWaitIdle)vk::DeviceWaitIdle(device);
  dropTargets();
  for(auto* b:{&assets,&avatarData,&triangleRefs,&starData})free(*b);
  free(sunTexture);free(moonTexture);free(worldTextures);
  for(auto p:postPipelines)if(p)vk::DestroyPipeline(device,p,nullptr);
  for(auto p:{worldPipeline,actorPipeline,shadowPipeline,starPipeline})if(p)vk::DestroyPipeline(device,p,nullptr);
  for(auto l:{worldPipelineLayout,postPipelineLayout})if(l)vk::DestroyPipelineLayout(device,l,nullptr);
  for(auto l:{worldLayout,postLayout})if(l)vk::DestroyDescriptorSetLayout(device,l,nullptr);
  for(auto p:{worldPass,shadowPass,colorPass,colorLoadPass,colorDepthPass,colorDepthLoadPass,handPass,depthResolvePass})if(p)vk::DestroyRenderPass(device,p,nullptr);
  for(auto s:{nearest,linear,worldSampler})if(s)vk::DestroySampler(device,s,nullptr);
  if(timestamps)vk::DestroyQueryPool(device,timestamps,nullptr);
  for(auto& f:frames){if(f.acquired)vk::DestroySemaphore(device,f.acquired,nullptr);if(f.fence)vk::DestroyFence(device,f.fence,nullptr);}
  savePipelineCache();
  if(pipelineCache)vk::DestroyPipelineCache(device,pipelineCache,nullptr);pipelineCache=VK_NULL_HANDLE;
  if(commands)vk::DestroyCommandPool(device,commands,nullptr);
  if(allocator)vmaDestroyAllocator(allocator);allocator=nullptr;
  vk::DestroyDevice(device,nullptr);device=VK_NULL_HANDLE;
 }
 if(surface)vk::DestroySurfaceKHR(instance,surface,nullptr);surface=VK_NULL_HANDLE;
 if(messenger&&vk::DestroyDebugUtilsMessengerEXT)vk::DestroyDebugUtilsMessengerEXT(instance,messenger,nullptr);messenger=VK_NULL_HANDLE;
 if(instance&&vk::DestroyInstance)vk::DestroyInstance(instance,nullptr);instance=VK_NULL_HANDLE;
 if(vk::loader)FreeLibrary(vk::loader);vk::loader=nullptr;
}
static uint32_t crc(std::span<const uint8_t> b){uint32_t v=~0u;for(auto x:b){v^=x;for(int i=0;i<8;++i)v=(v>>1)^(0xedb88320u&uint32_t(-int32_t(v&1)));}return~v;}
static void be(std::vector<uint8_t>& v,uint32_t n){v.push_back(uint8_t(n>>24));v.push_back(uint8_t(n>>16));v.push_back(uint8_t(n>>8));v.push_back(uint8_t(n));}
void write_png(const std::filesystem::path& path,uint32_t w,uint32_t h,const uint8_t* rgba){
    std::vector<uint8_t> output{137,80,78,71,13,10,26,10};
    auto chunk=[&](const char* name,const std::vector<uint8_t>& data){be(output,uint32_t(data.size()));size_t begin=output.size();output.insert(output.end(),name,name+4);output.insert(output.end(),data.begin(),data.end());be(output,crc(std::span(output.data()+begin,4+data.size())));};
    std::vector<uint8_t> ihdr;be(ihdr,w);be(ihdr,h);ihdr.insert(ihdr.end(),{8,6,0,0,0});chunk("IHDR",ihdr);
    std::vector<uint8_t> raw;raw.reserve((size_t(w)*4+1)*h);
    for(uint32_t y=0;y<h;++y){raw.push_back(0);raw.insert(raw.end(),rgba+size_t(y)*w*4,rgba+size_t(y+1)*w*4);}
    std::vector<uint8_t> z{0x78,0x01};uint32_t adlerA=1,adlerB=0;
    for(uint8_t x:raw){adlerA=(adlerA+x)%65521;adlerB=(adlerB+adlerA)%65521;}
    for(size_t at=0;at<raw.size();){uint16_t n=uint16_t(std::min(size_t(65535),raw.size()-at));z.push_back(at+n==raw.size()?1:0);z.push_back(uint8_t(n));z.push_back(uint8_t(n>>8));z.push_back(uint8_t(~n));z.push_back(uint8_t((~n)>>8));z.insert(z.end(),raw.begin()+at,raw.begin()+at+n);at+=n;}
    be(z,(adlerB<<16)|adlerA);chunk("IDAT",z);chunk("IEND",{});
    if(!path.parent_path().empty())std::filesystem::create_directories(path.parent_path());
    std::ofstream f(path,std::ios::binary);if(!f.write(reinterpret_cast<const char*>(output.data()),output.size()))throw std::runtime_error("Screenshot write failed");
}
}
