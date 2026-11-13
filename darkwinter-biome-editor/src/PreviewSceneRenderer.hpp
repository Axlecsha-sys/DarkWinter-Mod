// Retains native mesh buffers and textures across local camera updates; frame frameSlots own in-flight resources.
#pragma once
#include "SceneBundle.hpp"
#include "PreviewSceneMath.hpp"
#include "../shaders/PreviewSceneShaderCode.hpp"
#include "../shaders/PreviewShaderCode.hpp"
#include <QVulkanWindow>
#include <QVulkanFunctions>
#include <QJsonArray>
#include <array>
#include <functional>
#include <memory>
#include <cstring>

class PreviewSceneRenderer final {
    static constexpr int Slots=3;
    struct Context {
        VkDevice device=VK_NULL_HANDLE;
        QVulkanDeviceFunctions *df=nullptr;
        VkPhysicalDeviceMemoryProperties memory{};
        quint64 bytes=0,bufferAllocations=0,imageAllocations=0;
    };
    struct Buffer {
        std::shared_ptr<Context> context;
        VkBuffer buffer=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;
        VkDeviceSize bytes=0;void *mapped=nullptr;bool coherent=false;
        ~Buffer(){if(!context||!context->df)return;auto *f=context->df;auto d=context->device;
            if(mapped)f->vkUnmapMemory(d,memory);if(buffer)f->vkDestroyBuffer(d,buffer,nullptr);
            if(memory){f->vkFreeMemory(d,memory,nullptr);context->bytes-=bytes;}}
    };
    struct Texture {
        std::shared_ptr<Context> context;
        VkImage image=VK_NULL_HANDLE;VkImageView view=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;
        VkDeviceSize bytes=0;QSize size;bool uploaded=false;quint64 epoch=0;
        ~Texture(){if(!context||!context->df)return;auto *f=context->df;auto d=context->device;
            if(view)f->vkDestroyImageView(d,view,nullptr);if(image)f->vkDestroyImage(d,image,nullptr);
            if(memory){f->vkFreeMemory(d,memory,nullptr);context->bytes-=bytes;}}
    };
    struct Geometry {
        std::shared_ptr<Buffer> vertices,indices;
        std::shared_ptr<Texture> atlas;
        QVector<ScenePart> parts;
        std::array<double,3> origin{};
        quint64 scene=0,serial=0,resource=0;quint32 indexCount=0;
        double diagonal=128,cutY=320;
    };
    struct Slot {
        std::shared_ptr<Geometry> geometry;
        std::shared_ptr<Buffer> upload,lightStaging;
        std::shared_ptr<Texture> lightmap;
        quint64 lightSerial=0;
        VkImageView boundAtlas=VK_NULL_HANDLE,boundLight=VK_NULL_HANDLE;
    };
public:
    struct alignas(16) Push {
        float matrix[16]{},fogColor[4]{},viewDepth[4]{},options[4]{},state[4]{};
    };
    static_assert(sizeof(Push)==128);
    struct alignas(16) Brush {float ring[4]{},accent[4]{.40f,.84f,.96f,.85f},state[4]{};};
    static_assert(sizeof(Brush)==48);
private:
    QVulkanWindow *window=nullptr;
    std::shared_ptr<Context> context;
    std::function<void(QString)> diagnostic;
    VkDescriptorSetLayout descriptorLayout=VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool=VK_NULL_HANDLE;
    std::array<VkDescriptorSet,Slots> sets{};
    VkPipelineLayout pipelineLayout=VK_NULL_HANDLE;
    VkPipelineCache pipelineCache=VK_NULL_HANDLE;
    std::array<VkPipeline,3> pipelines{};
    VkSampler atlasSampler=VK_NULL_HANDLE,lightSampler=VK_NULL_HANDLE;
    std::array<Slot,Slots> frameSlots{};
    std::shared_ptr<Geometry> active;
    std::shared_ptr<Texture> cachedAtlas;
    QVector<const ScenePart*> visible[3];
    quint64 failedSerial=0,uploads=0,lightUploads=0,frames=0,drawCalls=0,triangles=0,sessionEpoch=0;
    int currentSlot=0;
    QString lastError;
    bool check(VkResult result,const char *operation) {
        if(result==VK_SUCCESS)return true;
        const auto text=QStringLiteral("Vulkan сцена: %1, код %2.").arg(QString::fromLatin1(operation)).arg(int(result));
        if(lastError!=text){lastError=text;if(diagnostic)diagnostic(text);}return false;
    }
    std::optional<uint32_t> type(uint32_t bits,VkMemoryPropertyFlags flags) const {
        for(uint32_t i=0;i<context->memory.memoryTypeCount;++i)
            if((bits&(1u<<i))&&(context->memory.memoryTypes[i].propertyFlags&flags)==flags)return i;
        return {};
    }
    std::shared_ptr<Buffer> buffer(VkDeviceSize size,VkBufferUsageFlags usage,bool host=false) {
        if(!size)return {};
        auto value=std::make_shared<Buffer>();value->context=context;auto *f=context->df;const auto d=context->device;
        VkBufferCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;info.size=size;info.usage=usage;info.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        if(!check(f->vkCreateBuffer(d,&info,nullptr,&value->buffer),"mesh buffer"))return {};
        ++context->bufferAllocations;
        VkMemoryRequirements req{};f->vkGetBufferMemoryRequirements(d,value->buffer,&req);
        auto index=type(req.memoryTypeBits,host?VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT:VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        value->coherent=host&&index.has_value();
        if(!index)index=type(req.memoryTypeBits,host?VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT:0);
        if(!index)return {};
        VkMemoryAllocateInfo allocation{};allocation.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;allocation.allocationSize=req.size;allocation.memoryTypeIndex=*index;
        if(!check(f->vkAllocateMemory(d,&allocation,nullptr,&value->memory),"mesh memory"))return {};
        value->bytes=req.size;context->bytes+=req.size;
        if(!check(f->vkBindBufferMemory(d,value->buffer,value->memory,0),"mesh binding"))return {};
        if(host&&!check(f->vkMapMemory(d,value->memory,0,VK_WHOLE_SIZE,0,&value->mapped),"staging mapping"))return {};
        return value;
    }
    bool flush(const std::shared_ptr<Buffer> &value) {
        if(value->coherent)return true;
        VkMappedMemoryRange range{};range.sType=VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;range.memory=value->memory;range.size=VK_WHOLE_SIZE;
        return check(context->df->vkFlushMappedMemoryRanges(context->device,1,&range),"staging flush");
    }
    std::shared_ptr<Texture> texture(QSize size) {
        if(size.width()<1||size.height()<1||uint32_t(size.width())>window->physicalDeviceProperties()->limits.maxImageDimension2D
                ||uint32_t(size.height())>window->physicalDeviceProperties()->limits.maxImageDimension2D)return {};
        auto value=std::make_shared<Texture>();value->context=context;value->size=size;
        auto *f=context->df;auto d=context->device;
        VkImageCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;info.imageType=VK_IMAGE_TYPE_2D;info.format=VK_FORMAT_R8G8B8A8_UNORM;
        info.extent={uint32_t(size.width()),uint32_t(size.height()),1};info.mipLevels=1;info.arrayLayers=1;
        info.samples=VK_SAMPLE_COUNT_1_BIT;info.tiling=VK_IMAGE_TILING_OPTIMAL;info.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
        if(!check(f->vkCreateImage(d,&info,nullptr,&value->image),"scene image"))return {};
        ++context->imageAllocations;
        VkMemoryRequirements req{};f->vkGetImageMemoryRequirements(d,value->image,&req);
        auto index=type(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);if(!index)index=type(req.memoryTypeBits,0);if(!index)return {};
        VkMemoryAllocateInfo allocation{};allocation.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;allocation.allocationSize=req.size;allocation.memoryTypeIndex=*index;
        if(!check(f->vkAllocateMemory(d,&allocation,nullptr,&value->memory),"scene image memory"))return {};
        value->bytes=req.size;context->bytes+=req.size;
        if(!check(f->vkBindImageMemory(d,value->image,value->memory,0),"scene image binding"))return {};
        VkImageViewCreateInfo view{};view.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;view.image=value->image;view.viewType=VK_IMAGE_VIEW_TYPE_2D;
        view.format=VK_FORMAT_R8G8B8A8_UNORM;view.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        if(!check(f->vkCreateImageView(d,&view,nullptr,&value->view),"scene image view"))return {};
        return value;
    }
    void copyTexture(VkCommandBuffer commands,const std::shared_ptr<Buffer> &source,VkDeviceSize offset,const std::shared_ptr<Texture> &target,VkPipelineStageFlags consumer) {
        auto *f=context->df;
        VkImageMemoryBarrier barrier{};barrier.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask=target->uploaded?VK_ACCESS_SHADER_READ_BIT:0;barrier.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.oldLayout=target->uploaded?VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;barrier.srcQueueFamilyIndex=barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        barrier.image=target->image;barrier.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        f->vkCmdPipelineBarrier(commands,target->uploaded?consumer:VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
        VkBufferImageCopy copy{};copy.bufferOffset=offset;copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        copy.imageExtent={uint32_t(target->size.width()),uint32_t(target->size.height()),1};
        f->vkCmdCopyBufferToImage(commands,source->buffer,target->image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
        barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        barrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;barrier.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        f->vkCmdPipelineBarrier(commands,VK_PIPELINE_STAGE_TRANSFER_BIT,consumer,0,0,nullptr,0,nullptr,1,&barrier);target->uploaded=true;
    }
    bool upload(VkCommandBuffer commands,const SceneBundle &scene,quint64 serial,Slot &slot) {
        auto result=std::make_shared<Geometry>();result->scene=scene.sceneRevision;result->serial=serial;result->resource=scene.resourceEpoch;
        result->parts=scene.parts;result->origin=scene.origin;result->indexCount=scene.indexCount;result->cutY=scene.cutY;
        for(auto &list:visible)list.reserve(scene.parts.size());
        result->diagonal=PreviewSceneMath::diagonal(scene.parts);
        if(!scene.indexCount){active=result;++uploads;return true;}
        const VkDeviceSize vertices=VkDeviceSize(scene.vertices.size()),indices=VkDeviceSize(scene.indices.size());
        result->vertices=buffer(vertices,VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        result->indices=buffer(indices,VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        const bool newAtlas=!cachedAtlas||cachedAtlas->epoch!=scene.resourceEpoch||cachedAtlas->size!=scene.atlasSize;
        result->atlas=newAtlas?texture(scene.atlasSize):cachedAtlas;
        if(!result->vertices||!result->indices||!result->atlas)return false;
        const VkDeviceSize atlasBytes=newAtlas?VkDeviceSize(scene.atlas.size()):0,total=vertices+indices+atlasBytes;
        auto staging=buffer(total,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,true);if(!staging)return false;
        auto *bytes=static_cast<char*>(staging->mapped);
        std::memcpy(bytes,scene.vertices.constData(),size_t(vertices));std::memcpy(bytes+vertices,scene.indices.constData(),size_t(indices));
        if(newAtlas)std::memcpy(bytes+vertices+indices,scene.atlas.constData(),size_t(atlasBytes));
        if(!flush(staging))return false;
        VkBufferCopy copy{0,0,vertices};context->df->vkCmdCopyBuffer(commands,staging->buffer,result->vertices->buffer,1,&copy);
        copy={vertices,0,indices};context->df->vkCmdCopyBuffer(commands,staging->buffer,result->indices->buffer,1,&copy);
        VkBufferMemoryBarrier barriers[2]{};
        for(int i=0;i<2;++i){auto &b=barriers[i];b.sType=VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
            b.dstAccessMask=i?VK_ACCESS_INDEX_READ_BIT:VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT;b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.buffer=i?result->indices->buffer:result->vertices->buffer;b.size=VK_WHOLE_SIZE;}
        context->df->vkCmdPipelineBarrier(commands,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,0,0,nullptr,2,barriers,0,nullptr);
        if(newAtlas){copyTexture(commands,staging,vertices+indices,result->atlas,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);result->atlas->epoch=scene.resourceEpoch;cachedAtlas=result->atlas;}
        slot.upload=staging;active=result;++uploads;return true;
    }
    bool light(VkCommandBuffer commands,Slot &slot,const QByteArray &pixels,QSize size,quint64 serial) {
        if(slot.lightSerial==serial&&slot.lightmap&&slot.lightmap->size==size)return true;
        if(size!=QSize(16,16)||pixels.size()!=1024)return false;
        if(!slot.lightmap)slot.lightmap=texture(size);
        if(!slot.lightStaging)slot.lightStaging=buffer(1024,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,true);
        if(!slot.lightmap||!slot.lightStaging)return false;
        std::memcpy(slot.lightStaging->mapped,pixels.constData(),1024);if(!flush(slot.lightStaging))return false;
        copyTexture(commands,slot.lightStaging,0,slot.lightmap,VK_PIPELINE_STAGE_VERTEX_SHADER_BIT);
        slot.lightSerial=serial;++lightUploads;return true;
    }
    bool pipeline(int index,const uint32_t *vertex,size_t vertexBytes,const uint32_t *fragment,size_t fragmentBytes,bool translucent,bool overlay) {
        auto *f=context->df;auto d=context->device;VkShaderModule modules[2]{};
        VkShaderModuleCreateInfo shader{};shader.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;shader.codeSize=vertexBytes;shader.pCode=vertex;
        if(!check(f->vkCreateShaderModule(d,&shader,nullptr,&modules[0]),"scene vertex shader"))return false;
        shader.codeSize=fragmentBytes;shader.pCode=fragment;
        if(!check(f->vkCreateShaderModule(d,&shader,nullptr,&modules[1]),"scene fragment shader")){f->vkDestroyShaderModule(d,modules[0],nullptr);return false;}
        VkPipelineShaderStageCreateInfo stages[2]{};
        for(int i=0;i<2;++i){stages[i].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;stages[i].stage=i?VK_SHADER_STAGE_FRAGMENT_BIT:VK_SHADER_STAGE_VERTEX_BIT;stages[i].module=modules[i];stages[i].pName="main";}
        VkVertexInputBindingDescription binding{0,32,VK_VERTEX_INPUT_RATE_VERTEX};
        VkVertexInputAttributeDescription attributes[]={{0,0,VK_FORMAT_R32G32B32_SFLOAT,0},{1,0,VK_FORMAT_R8G8B8A8_UNORM,12},
            {2,0,VK_FORMAT_R32G32_SFLOAT,16},{3,0,VK_FORMAT_R32_UINT,24}};
        VkPipelineVertexInputStateCreateInfo input{};input.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        if(!overlay){input.vertexBindingDescriptionCount=1;input.pVertexBindingDescriptions=&binding;input.vertexAttributeDescriptionCount=4;input.pVertexAttributeDescriptions=attributes;}
        VkPipelineInputAssemblyStateCreateInfo assembly{};assembly.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;assembly.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo viewport{};viewport.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;viewport.viewportCount=viewport.scissorCount=1;
        VkPipelineRasterizationStateCreateInfo raster{};raster.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;raster.polygonMode=VK_POLYGON_MODE_FILL;raster.cullMode=VK_CULL_MODE_NONE;raster.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;raster.lineWidth=1;
        VkPipelineMultisampleStateCreateInfo samples{};samples.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;samples.rasterizationSamples=window->sampleCountFlagBits();
        VkPipelineDepthStencilStateCreateInfo depth{};depth.sType=VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;depth.depthTestEnable=!overlay;depth.depthWriteEnable=!overlay&&!translucent;depth.depthCompareOp=VK_COMPARE_OP_LESS_OR_EQUAL;
        VkPipelineColorBlendAttachmentState blend{};blend.colorWriteMask=15;blend.blendEnable=translucent||overlay;
        blend.srcColorBlendFactor=VK_BLEND_FACTOR_SRC_ALPHA;blend.dstColorBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;blend.colorBlendOp=VK_BLEND_OP_ADD;
        blend.srcAlphaBlendFactor=VK_BLEND_FACTOR_ONE;blend.dstAlphaBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;blend.alphaBlendOp=VK_BLEND_OP_ADD;
        VkPipelineColorBlendStateCreateInfo blending{};blending.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;blending.attachmentCount=1;blending.pAttachments=&blend;
        VkDynamicState states[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};VkPipelineDynamicStateCreateInfo dynamic{};dynamic.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;dynamic.dynamicStateCount=2;dynamic.pDynamicStates=states;
        VkGraphicsPipelineCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;info.stageCount=2;info.pStages=stages;
        info.pVertexInputState=&input;info.pInputAssemblyState=&assembly;info.pViewportState=&viewport;info.pRasterizationState=&raster;
        info.pMultisampleState=&samples;info.pDepthStencilState=&depth;info.pColorBlendState=&blending;info.pDynamicState=&dynamic;
        info.layout=pipelineLayout;info.renderPass=window->defaultRenderPass();
        const auto result=f->vkCreateGraphicsPipelines(d,pipelineCache,1,&info,nullptr,&pipelines[size_t(index)]);
        for(auto module:modules)f->vkDestroyShaderModule(d,module,nullptr);return check(result,"retained scene pipeline");
    }
public:
    bool initialize(QVulkanWindow *owner,std::function<void(QString)> report) {
        window=owner;diagnostic=std::move(report);context=std::make_shared<Context>();context->device=owner->device();
        context->df=owner->vulkanInstance()->deviceFunctions(context->device);
        owner->vulkanInstance()->functions()->vkGetPhysicalDeviceMemoryProperties(owner->physicalDevice(),&context->memory);
        auto *f=context->df;auto d=context->device;
        VkDescriptorSetLayoutBinding bindings[2]{};
        for(int i=0;i<2;++i){bindings[i].binding=uint32_t(i);bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;bindings[i].descriptorCount=1;
            bindings[i].stageFlags=i?VK_SHADER_STAGE_VERTEX_BIT:VK_SHADER_STAGE_FRAGMENT_BIT;}
        VkDescriptorSetLayoutCreateInfo layout{};layout.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;layout.bindingCount=2;layout.pBindings=bindings;
        if(!check(f->vkCreateDescriptorSetLayout(d,&layout,nullptr,&descriptorLayout),"scene descriptor layout"))return false;
        VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,Slots*2};
        VkDescriptorPoolCreateInfo pool{};pool.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;pool.maxSets=Slots;pool.poolSizeCount=1;pool.pPoolSizes=&poolSize;
        if(!check(f->vkCreateDescriptorPool(d,&pool,nullptr,&descriptorPool),"scene descriptor pool"))return false;
        std::array<VkDescriptorSetLayout,Slots> layouts{};layouts.fill(descriptorLayout);
        VkDescriptorSetAllocateInfo allocate{};allocate.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;allocate.descriptorPool=descriptorPool;allocate.descriptorSetCount=Slots;allocate.pSetLayouts=layouts.data();
        if(!check(f->vkAllocateDescriptorSets(d,&allocate,sets.data()),"scene descriptor sets"))return false;
        VkPushConstantRange push{VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,128};
        VkPipelineLayoutCreateInfo pipeline{};pipeline.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;pipeline.setLayoutCount=1;pipeline.pSetLayouts=&descriptorLayout;pipeline.pushConstantRangeCount=1;pipeline.pPushConstantRanges=&push;
        if(!check(f->vkCreatePipelineLayout(d,&pipeline,nullptr,&pipelineLayout),"scene pipeline layout"))return false;
        VkPipelineCacheCreateInfo cache{};cache.sType=VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
        if(!check(f->vkCreatePipelineCache(d,&cache,nullptr,&pipelineCache),"scene pipeline cache"))return false;
        VkSamplerCreateInfo sampler{};sampler.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;sampler.magFilter=sampler.minFilter=VK_FILTER_NEAREST;
        sampler.maxAnisotropy=1;sampler.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;sampler.addressModeU=sampler.addressModeV=sampler.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        if(!check(f->vkCreateSampler(d,&sampler,nullptr,&atlasSampler),"native atlas sampler"))return false;
        sampler.magFilter=sampler.minFilter=VK_FILTER_LINEAR;
        return check(f->vkCreateSampler(d,&sampler,nullptr,&lightSampler),"native lightmap sampler");
    }
    bool initializeSwapchain() {
        releaseSwapchain();
        using namespace PreviewSceneShaderCode;
        return pipeline(0,Vertex.data(),Vertex.size()*4,Fragment.data(),Fragment.size()*4,false,false)
            &&pipeline(1,Vertex.data(),Vertex.size()*4,Fragment.data(),Fragment.size()*4,true,false)
            &&pipeline(2,PreviewShaderCode::Vertex.data(),PreviewShaderCode::Vertex.size()*4,OverlayFragment.data(),OverlayFragment.size()*4,true,true);
    }
    void releaseSwapchain() {
        if(!context||!context->df)return;
        for(auto &pipeline:pipelines){if(pipeline)context->df->vkDestroyPipeline(context->device,pipeline,nullptr);pipeline=VK_NULL_HANDLE;}
    }
    void clear(){active.reset();cachedAtlas.reset();failedSerial=0;}
    void release() {
        if(!context||!context->df)return;
        releaseSwapchain();active.reset();cachedAtlas.reset();for(auto &slot:frameSlots)slot=Slot{};
        auto *f=context->df;auto d=context->device;
        if(atlasSampler)f->vkDestroySampler(d,atlasSampler,nullptr);if(lightSampler)f->vkDestroySampler(d,lightSampler,nullptr);
        if(pipelineCache)f->vkDestroyPipelineCache(d,pipelineCache,nullptr);if(pipelineLayout)f->vkDestroyPipelineLayout(d,pipelineLayout,nullptr);
        if(descriptorPool)f->vkDestroyDescriptorPool(d,descriptorPool,nullptr);if(descriptorLayout)f->vkDestroyDescriptorSetLayout(d,descriptorLayout,nullptr);
        atlasSampler=lightSampler=VK_NULL_HANDLE;pipelineCache=VK_NULL_HANDLE;pipelineLayout=VK_NULL_HANDLE;
        descriptorPool=VK_NULL_HANDLE;descriptorLayout=VK_NULL_HANDLE;sets.fill(VK_NULL_HANDLE);context->df=nullptr;
    }
    bool prepare(VkCommandBuffer commands,const std::shared_ptr<const SceneBundle> &scene,quint64 serial,
            const std::shared_ptr<const SceneLighting> &lighting,quint64 lightSerial,quint64 epoch) {
        currentSlot=window->currentFrame();
        if(!context||!context->df||currentSlot<0||currentSlot>=Slots||window->concurrentFrameCount()>Slots)return false;
        auto &slot=frameSlots[size_t(currentSlot)];slot.geometry.reset();slot.upload.reset();
        if(epoch!=sessionEpoch){
            clear();sessionEpoch=epoch;
            for(auto &value:frameSlots){value.boundAtlas=value.boundLight=VK_NULL_HANDLE;value.lightSerial=0;}
        }
        if(!scene){clear();return false;}
        if((!active||active->serial!=serial)&&serial!=failedSerial){
            if(!upload(commands,*scene,serial,slot)){failedSerial=serial;if(diagnostic)diagnostic(QStringLiteral("Vulkan: не удалось разместить нативную сцену в памяти видеокарты."));}
        }
        if(!active)return false;
        slot.geometry=active;
        if(!active->indexCount)return active->serial==serial;
        const bool matching=lighting&&lighting->sceneRevision==active->scene&&lighting->resourceEpoch==active->resource;
        const auto &pixels=matching?lighting->lightmap:scene->lightmap;const auto size=matching?lighting->lightmapSize:scene->lightmapSize;
        if(!light(commands,slot,pixels,size,lightSerial))return false;
        if(slot.boundAtlas!=active->atlas->view||slot.boundLight!=slot.lightmap->view){
            VkDescriptorImageInfo images[2]={{atlasSampler,active->atlas->view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
                {lightSampler,slot.lightmap->view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}};
            VkWriteDescriptorSet writes[2]{};
            for(int i=0;i<2;++i){auto &value=writes[i];value.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;value.dstSet=sets[size_t(currentSlot)];value.dstBinding=uint32_t(i);
                value.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;value.descriptorCount=1;value.pImageInfo=&images[i];}
            context->df->vkUpdateDescriptorSets(context->device,2,writes,0,nullptr);slot.boundAtlas=images[0].imageView;slot.boundLight=images[1].imageView;
        }
        return active->serial==serial;
    }
    quint64 serial()const{return active?active->serial:0;}
    quint64 sceneRevision()const{return active?active->scene:0;}
    std::array<double,3> origin()const{return active?active->origin:std::array<double,3>{};}
    double sceneDiagonal()const{return active?active->diagonal:128;}
    double capturedCutY()const{return active?active->cutY:320;}
    bool draw(VkCommandBuffer commands,QSize extent,const PreviewMath::Camera &camera,const QJsonObject &environment,double cutY,bool srgb,const Brush &brush) {
        if(!active||!pipelines[0])return false;
        auto *f=context->df;VkViewport viewport{0,0,float(extent.width()),float(extent.height()),0,1};VkRect2D scissor{{0,0},{uint32_t(extent.width()),uint32_t(extent.height())}};
        f->vkCmdSetViewport(commands,0,1,&viewport);f->vkCmdSetScissor(commands,0,1,&scissor);
        const auto transform=PreviewSceneMath::view(camera,active->origin,extent,active->diagonal);
        Push push;std::copy(transform.matrix.begin(),transform.matrix.end(),push.matrix);
        const auto sky=background(environment);std::copy(sky.begin(),sky.end(),push.fogColor);
        for(int i=0;i<3;++i)push.viewDepth[i]=float(transform.forward[i]);push.viewDepth[3]=float(transform.eyeDot);
        push.options[0]=float(transform.distance+active->diagonal*.2);push.options[1]=float(transform.distance+active->diagonal*1.1+1);
        push.options[2]=float(std::min(cutY,active->cutY)+1-active->origin[1]);push.state[0]=srgb?1.f:0.f;push.state[1]=environment.value("effects").toBool()?1.f:0.f;
        drawCalls=triangles=0;
        for(auto &list:visible)list.clear();
        for(const auto &part:active->parts)if(part.layer>=0&&part.layer<=2&&PreviewSceneMath::visible(part.bounds,transform))visible[part.layer].push_back(&part);
        std::sort(visible[2].begin(),visible[2].end(),[&](const ScenePart *a,const ScenePart *b){return PreviewSceneMath::depth(a->bounds,transform)>PreviewSceneMath::depth(b->bounds,transform);});
        if(active->indexCount){
            VkDeviceSize offset=0;f->vkCmdBindVertexBuffers(commands,0,1,&active->vertices->buffer,&offset);f->vkCmdBindIndexBuffer(commands,active->indices->buffer,0,VK_INDEX_TYPE_UINT32);
            f->vkCmdBindDescriptorSets(commands,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineLayout,0,1,&sets[size_t(currentSlot)],0,nullptr);
            for(int layer=0;layer<3;++layer){if(visible[layer].isEmpty())continue;
                f->vkCmdBindPipeline(commands,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelines[layer==2?1:0]);push.options[3]=float(layer);
                f->vkCmdPushConstants(commands,pipelineLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(push),&push);
                for(const auto *part:visible[layer]){f->vkCmdDrawIndexed(commands,part->indexCount,1,part->firstIndex,0,0);++drawCalls;triangles+=part->indexCount/3;}
            }
        }
        if(brush.state[0]>.5f&&pipelines[2]){
            f->vkCmdBindPipeline(commands,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelines[2]);
            f->vkCmdPushConstants(commands,pipelineLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(brush),&brush);
            f->vkCmdDraw(commands,3,1,0,0);
        }
        ++frames;return true;
    }
    static std::array<float,4> background(const QJsonObject &environment) {
        const double t=environment.value("time").toDouble(6000),day=std::clamp(std::cos((t-6000)/24000*2*PreviewMath::Pi)*1.3+.15,0.0,1.0);
        const std::array<float,3> night{.018f,.029f,.065f},sun{.48f,.62f,.80f};
        std::array<float,4> result{};for(int i=0;i<3;++i)result[i]=float(night[i]*(1-day)+sun[i]*day);result[3]=1;return result;
    }
    struct Statistics {
        quint64 localSceneFrames=0,sceneGpuAllocatedBytes=0,sceneUploads=0,sceneBufferAllocations=0,sceneImageAllocations=0,lightingUploads=0,sceneDrawCalls=0,sceneTriangles=0;
        bool sceneUploadQueued=false;int sceneRetainedGenerations=0,framesInFlight=0;
        QJsonObject json()const{return {{"localSceneFrames",double(localSceneFrames)},{"sceneGpuAllocatedBytes",double(sceneGpuAllocatedBytes)},
            {"sceneUploadQueued",sceneUploadQueued},{"sceneRetainedGenerations",sceneRetainedGenerations},{"sceneUploads",double(sceneUploads)},
            {"sceneBufferAllocations",double(sceneBufferAllocations)},{"sceneImageAllocations",double(sceneImageAllocations)},
            {"lightingUploads",double(lightingUploads)},{"sceneDrawCalls",double(sceneDrawCalls)},{"sceneTriangles",double(sceneTriangles)},{"framesInFlight",framesInFlight}};}
    };
    Statistics statistics(quint64 requestedSerial)const {
        std::array<const Geometry*,Slots+1> values{};int count=0;
        const auto add=[&](const std::shared_ptr<Geometry> &value){if(value&&std::find(values.begin(),values.begin()+count,value.get())==values.begin()+count)values[size_t(count++)]=value.get();};
        add(active);for(const auto &slot:frameSlots)add(slot.geometry);
        Statistics s;s.localSceneFrames=frames;s.sceneGpuAllocatedBytes=context?context->bytes:0;
        s.sceneUploadQueued=bool(requestedSerial&&serial()!=requestedSerial);s.sceneRetainedGenerations=count;s.sceneUploads=uploads;
        s.sceneBufferAllocations=context?context->bufferAllocations:0;s.sceneImageAllocations=context?context->imageAllocations:0;
        s.lightingUploads=lightUploads;s.sceneDrawCalls=drawCalls;s.sceneTriangles=triangles;s.framesInFlight=window?window->concurrentFrameCount():0;return s;
    }
};
