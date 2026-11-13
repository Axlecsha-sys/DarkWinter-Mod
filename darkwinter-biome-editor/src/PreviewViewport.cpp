// Owns the Vulkan image pipeline, bounded upload buffers and editor viewport interaction.
#include "PreviewViewport.hpp"
#include "PreviewViewportMath.hpp"
#include "PreviewSceneRenderer.hpp"
#include "../shaders/PreviewShaderCode.hpp"
#include <QEvent>
#include <QCursor>
#include <QJsonArray>
#include <QMetaObject>
#include <QMouseEvent>
#include <QMutex>
#include <QMutexLocker>
#include <QThread>
#include <QTimer>
#include <QVulkanFunctions>
#include <QWheelEvent>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <limits>
#include <optional>
#include <utility>

namespace {
constexpr int FramesInFlight=3,MaxStrokePoints=2048;
constexpr float Background[4]={.025f,.055f,.085f,1};
struct RenderCounters {
    quint64 frames=0,uploads=0,imageAllocations=0,stagingAllocations=0,currentAllocatedBytes=0,redrawRequests=0,coalescedRedraws=0;
    bool pipelineReady=false;QString deviceName;int surfaceFormat=0;
    PreviewSceneRenderer::Statistics scene;
};
struct Frame {QByteArray pixels;QSize size;quint64 revision=0,serial=0;PreviewMath::Camera camera;QJsonObject options;};
struct PendingFrame {QByteArray pixels;QSize size;quint64 revision=0;QJsonObject camera;};
bool validFrame(const QByteArray &rgba,QSize size,quint64 revision) {
    return PreviewMath::validFrame(size,rgba.size(),revision);
}
bool validBrush(const QString &kind) {
    static const QStringList kinds={"raise","lower","flatten","smooth","mountain","plateau","river","lake","cave","tunnel","set_block","paint","vegetation"};
    return kind.isEmpty()||kinds.contains(kind);
}
struct alignas(16) Overlay {float ring[4]={};float accent[4]={.40f,.84f,.96f,.85f};float state[4]={};};
static_assert(sizeof(Overlay)==48);
}

struct PreviewViewport::Private {
    Frame frame;
    std::shared_ptr<const SceneBundle> scene;
    std::shared_ptr<const SceneLighting> lighting;
    QJsonObject environment;
    quint64 sceneSerial=0,lightSerial=0;
    bool referenceView=false;
    std::shared_ptr<RenderCounters> counters=std::make_shared<RenderCounters>();
    PreviewMath::Camera camera,displayCamera;
    QJsonObject cameraOptions,displayOptions;
    QSize displaySize;
    quint64 nextSerial=0,displayRevision=0,displaySerial=0;
    bool displayValid=false;
    QJsonObject lastPointer;
    QString brush="raise";
    double radius=8,workY=76;
    QPointF pointer{-1,-1},lastDrag;
    std::optional<QPointF> hover;
    Qt::MouseButton drag=Qt::NoButton;
    QVector<QPointF> stroke;
    bool strokeLimitReported=false;
    QMutex pendingMutex;
    std::atomic<quint64> frameEpoch{1};
    std::optional<PendingFrame> pending;
    bool frameQueued=false;
    QTimer updateTimer;
};

class PreviewRenderer final : public QVulkanWindowRenderer {
public:
    explicit PreviewRenderer(PreviewViewport *window):w(window),counters(window->d->counters){}
    ~PreviewRenderer() override {releaseResources();}
    void initResources() override;
    void initSwapChainResources() override;
    void releaseSwapChainResources() override;
    void releaseResources() override;
    void startNextFrame() override;
    void physicalDeviceLost() override {ready=false;report(QStringLiteral("Vulkan: видеокарта стала недоступна."));}
    void logicalDeviceLost() override {ready=false;report(QStringLiteral("Vulkan: графическое устройство потеряно."));}
private:
    struct Staging {VkBuffer buffer=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;VkDeviceSize bytes=0;void *mapped=nullptr;bool coherent=false;};
    PreviewViewport *w;
    std::shared_ptr<RenderCounters> counters;
    QVulkanFunctions *f=nullptr;
    QVulkanDeviceFunctions *df=nullptr;
    VkDevice device=VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    VkDescriptorSetLayout descriptorLayout=VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool=VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet=VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout=VK_NULL_HANDLE;
    VkPipelineCache pipelineCache=VK_NULL_HANDLE;
    VkPipeline pipeline=VK_NULL_HANDLE;
    VkSampler sampler=VK_NULL_HANDLE;
    VkImage image=VK_NULL_HANDLE;
    VkDeviceMemory imageMemory=VK_NULL_HANDLE;
    VkImageView imageView=VK_NULL_HANDLE;
    VkDeviceSize imageAllocationBytes=0;
    std::array<Staging,FramesInFlight> staging{};
    QSize imageSize;
    quint64 uploadedSerial=0;
    bool ready=false,uploaded=false,linearFilter=true;
    QString lastDiagnostic;
    PreviewSceneRenderer sceneRenderer;
    bool sceneReady=false;
    quint64 displayedSceneSerial=0;
    void report(const QString &message){if(lastDiagnostic!=message){lastDiagnostic=message;emit w->diagnostic(message);}}
    bool check(VkResult result,const char *operation) {
        if(result==VK_SUCCESS)return true;
        report(QStringLiteral("Vulkan: %1, код %2.").arg(QString::fromLatin1(operation)).arg(int(result)));return false;
    }
    std::optional<uint32_t> memoryType(uint32_t bits,VkMemoryPropertyFlags flags) const {
        for(uint32_t i=0;i<memoryProperties.memoryTypeCount;i++)
            if((bits&(1u<<i))&&(memoryProperties.memoryTypes[i].propertyFlags&flags)==flags)return i;
        return {};
    }
    bool makeStaging(Staging &slot,VkDeviceSize bytes);
    bool ensureImage(QSize size);
    bool upload(VkCommandBuffer commands,const Frame &frame);
    void destroyImage();
    bool makePipeline();
};

void PreviewRenderer::initResources() {
    device=w->device();f=w->vulkanInstance()->functions();df=w->vulkanInstance()->deviceFunctions(device);
    ready=false;uploadedSerial=0;uploaded=false;w->d->displayValid=false;
    if(!device||!f||!df)return;
    counters->deviceName=QString::fromUtf8(w->physicalDeviceProperties()->deviceName);
    f->vkGetPhysicalDeviceMemoryProperties(w->physicalDevice(),&memoryProperties);
    VkFormatProperties format{};f->vkGetPhysicalDeviceFormatProperties(w->physicalDevice(),VK_FORMAT_R8G8B8A8_UNORM,&format);
    if(!(format.optimalTilingFeatures&VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)){report(QStringLiteral("Vulkan: формат RGBA8 недоступен для предпросмотра."));return;}
    linearFilter=(format.optimalTilingFeatures&VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)!=0;
    sceneReady=sceneRenderer.initialize(w,[this](QString message){report(message);});displayedSceneSerial=0;
    VkDescriptorSetLayoutBinding binding{};binding.binding=0;binding.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount=1;binding.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo descriptorInfo{};descriptorInfo.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount=1;descriptorInfo.pBindings=&binding;
    if(!check(df->vkCreateDescriptorSetLayout(device,&descriptorInfo,nullptr,&descriptorLayout),"descriptor layout"))return;
    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1};
    VkDescriptorPoolCreateInfo poolInfo{};poolInfo.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;poolInfo.maxSets=1;poolInfo.poolSizeCount=1;poolInfo.pPoolSizes=&poolSize;
    if(!check(df->vkCreateDescriptorPool(device,&poolInfo,nullptr,&descriptorPool),"descriptor pool"))return;
    VkDescriptorSetAllocateInfo allocation{};allocation.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocation.descriptorPool=descriptorPool;allocation.descriptorSetCount=1;allocation.pSetLayouts=&descriptorLayout;
    if(!check(df->vkAllocateDescriptorSets(device,&allocation,&descriptorSet),"descriptor set"))return;
    VkPushConstantRange push{VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(Overlay)};
    VkPipelineLayoutCreateInfo layoutInfo{};layoutInfo.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount=1;layoutInfo.pSetLayouts=&descriptorLayout;layoutInfo.pushConstantRangeCount=1;layoutInfo.pPushConstantRanges=&push;
    if(!check(df->vkCreatePipelineLayout(device,&layoutInfo,nullptr,&pipelineLayout),"pipeline layout"))return;
    VkPipelineCacheCreateInfo cacheInfo{};cacheInfo.sType=VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    if(!check(df->vkCreatePipelineCache(device,&cacheInfo,nullptr,&pipelineCache),"pipeline cache"))return;
    VkSamplerCreateInfo samplerInfo{};samplerInfo.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter=samplerInfo.minFilter=linearFilter?VK_FILTER_LINEAR:VK_FILTER_NEAREST;
    samplerInfo.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU=samplerInfo.addressModeV=samplerInfo.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.maxLod=0;samplerInfo.maxAnisotropy=1;
    if(!check(df->vkCreateSampler(device,&samplerInfo,nullptr,&sampler),"sampler"))return;
    ready=true;
}
void PreviewRenderer::initSwapChainResources() {
    counters->surfaceFormat=int(w->colorFormat());
    if(ready&&!makePipeline())ready=false;
    if(sceneReady&&!sceneRenderer.initializeSwapchain())sceneReady=false;
    counters->pipelineReady=(ready&&pipeline!=VK_NULL_HANDLE)||sceneReady;
}
bool PreviewRenderer::makePipeline() {
    VkShaderModule vertex=VK_NULL_HANDLE,fragment=VK_NULL_HANDLE;
    auto module=[&](const auto &code,VkShaderModule &result){
        VkShaderModuleCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        info.codeSize=code.size()*sizeof(uint32_t);info.pCode=code.data();
        return check(df->vkCreateShaderModule(device,&info,nullptr,&result),"shader module");
    };
    if(!module(PreviewShaderCode::Vertex,vertex))return false;
    if(!module(PreviewShaderCode::Fragment,fragment)){df->vkDestroyShaderModule(device,vertex,nullptr);return false;}
    VkPipelineShaderStageCreateInfo stages[2]{};
    for(auto &stage:stages){stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;stage.pName="main";}
    stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT;stages[0].module=vertex;
    stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT;stages[1].module=fragment;
    VkPipelineVertexInputStateCreateInfo vertices{};vertices.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo assembly{};assembly.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;assembly.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo viewport{};viewport.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;viewport.viewportCount=1;viewport.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo raster{};raster.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    raster.polygonMode=VK_POLYGON_MODE_FILL;raster.cullMode=VK_CULL_MODE_NONE;raster.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;raster.lineWidth=1;
    VkPipelineMultisampleStateCreateInfo samples{};samples.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;samples.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineDepthStencilStateCreateInfo depth{};depth.sType=VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;depth.depthCompareOp=VK_COMPARE_OP_ALWAYS;
    VkPipelineColorBlendAttachmentState color{};color.colorWriteMask=VK_COLOR_COMPONENT_R_BIT|VK_COLOR_COMPONENT_G_BIT|VK_COLOR_COMPONENT_B_BIT|VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend{};blend.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;blend.attachmentCount=1;blend.pAttachments=&color;
    VkDynamicState dynamicStates[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{};dynamic.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;dynamic.dynamicStateCount=2;dynamic.pDynamicStates=dynamicStates;
    VkGraphicsPipelineCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    info.stageCount=2;info.pStages=stages;info.pVertexInputState=&vertices;info.pInputAssemblyState=&assembly;info.pViewportState=&viewport;
    info.pRasterizationState=&raster;info.pMultisampleState=&samples;info.pDepthStencilState=&depth;info.pColorBlendState=&blend;info.pDynamicState=&dynamic;
    info.layout=pipelineLayout;info.renderPass=w->defaultRenderPass();
    VkResult result=df->vkCreateGraphicsPipelines(device,pipelineCache,1,&info,nullptr,&pipeline);
    df->vkDestroyShaderModule(device,fragment,nullptr);df->vkDestroyShaderModule(device,vertex,nullptr);
    return check(result,"graphics pipeline");
}
void PreviewRenderer::releaseSwapChainResources() {
    sceneRenderer.releaseSwapchain();
    if(df&&device&&pipeline)df->vkDestroyPipeline(device,pipeline,nullptr);
    pipeline=VK_NULL_HANDLE;counters->pipelineReady=false;
}
void PreviewRenderer::destroyImage() {
    if(!df||!device)return;
    for(auto &slot:staging) {
        if(slot.mapped)df->vkUnmapMemory(device,slot.memory);
        if(slot.buffer)df->vkDestroyBuffer(device,slot.buffer,nullptr);
        if(slot.memory){df->vkFreeMemory(device,slot.memory,nullptr);counters->currentAllocatedBytes-=slot.bytes;}
        slot={};
    }
    if(imageView)df->vkDestroyImageView(device,imageView,nullptr);
    if(image)df->vkDestroyImage(device,image,nullptr);
    if(imageMemory){df->vkFreeMemory(device,imageMemory,nullptr);counters->currentAllocatedBytes-=imageAllocationBytes;}
    imageAllocationBytes=0;
    imageView=VK_NULL_HANDLE;image=VK_NULL_HANDLE;imageMemory=VK_NULL_HANDLE;imageSize={};uploaded=false;uploadedSerial=0;
}
void PreviewRenderer::releaseResources() {
    if(!df||!device)return;
    df->vkDeviceWaitIdle(device);
    sceneRenderer.release();sceneReady=false;
    releaseSwapChainResources();
    if(descriptorPool)df->vkDestroyDescriptorPool(device,descriptorPool,nullptr);
    descriptorPool=VK_NULL_HANDLE;descriptorSet=VK_NULL_HANDLE;
    destroyImage();
    if(sampler)df->vkDestroySampler(device,sampler,nullptr);
    if(pipelineCache)df->vkDestroyPipelineCache(device,pipelineCache,nullptr);
    if(pipelineLayout)df->vkDestroyPipelineLayout(device,pipelineLayout,nullptr);
    if(descriptorLayout)df->vkDestroyDescriptorSetLayout(device,descriptorLayout,nullptr);
    sampler=VK_NULL_HANDLE;pipelineCache=VK_NULL_HANDLE;pipelineLayout=VK_NULL_HANDLE;descriptorLayout=VK_NULL_HANDLE;
    ready=false;device=VK_NULL_HANDLE;df=nullptr;f=nullptr;
}
bool PreviewRenderer::makeStaging(Staging &slot,VkDeviceSize bytes) {
    VkBufferCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;info.size=bytes;info.usage=VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    if(!check(df->vkCreateBuffer(device,&info,nullptr,&slot.buffer),"upload buffer"))return false;
    ++counters->stagingAllocations;
    VkMemoryRequirements requirements{};df->vkGetBufferMemoryRequirements(device,slot.buffer,&requirements);
    auto type=memoryType(requirements.memoryTypeBits,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    slot.coherent=type.has_value();if(!type)type=memoryType(requirements.memoryTypeBits,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
    if(!type){report(QStringLiteral("Vulkan: память загрузки кадра недоступна."));return false;}
    VkMemoryAllocateInfo allocation{};allocation.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;allocation.allocationSize=requirements.size;allocation.memoryTypeIndex=*type;
    if(!check(df->vkAllocateMemory(device,&allocation,nullptr,&slot.memory),"upload memory"))return false;
    slot.bytes=requirements.size;counters->currentAllocatedBytes+=slot.bytes;
    if(!check(df->vkBindBufferMemory(device,slot.buffer,slot.memory,0),"upload binding"))return false;
    return check(df->vkMapMemory(device,slot.memory,0,VK_WHOLE_SIZE,0,&slot.mapped),"upload mapping")&&slot.mapped;
}
bool PreviewRenderer::ensureImage(QSize size) {
    if(image&&imageSize==size)return true;
    if(size.width()>int(w->physicalDeviceProperties()->limits.maxImageDimension2D)||size.height()>int(w->physicalDeviceProperties()->limits.maxImageDimension2D)) {
        report(QStringLiteral("Vulkan: размер кадра превышает возможности видеокарты."));return false;
    }
    if(!check(df->vkDeviceWaitIdle(device),"resize synchronization"))return false;
    w->d->displayValid=false;destroyImage();
    VkImageCreateInfo info{};info.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;info.imageType=VK_IMAGE_TYPE_2D;info.format=VK_FORMAT_R8G8B8A8_UNORM;
    info.extent={uint32_t(size.width()),uint32_t(size.height()),1};info.mipLevels=1;info.arrayLayers=1;info.samples=VK_SAMPLE_COUNT_1_BIT;
    info.tiling=VK_IMAGE_TILING_OPTIMAL;info.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
    if(!check(df->vkCreateImage(device,&info,nullptr,&image),"frame image")){destroyImage();return false;}
    ++counters->imageAllocations;
    VkMemoryRequirements requirements{};df->vkGetImageMemoryRequirements(device,image,&requirements);
    auto type=memoryType(requirements.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);if(!type)type=memoryType(requirements.memoryTypeBits,0);
    if(!type){report(QStringLiteral("Vulkan: память изображения недоступна."));destroyImage();return false;}
    VkMemoryAllocateInfo allocation{};allocation.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;allocation.allocationSize=requirements.size;allocation.memoryTypeIndex=*type;
    if(!check(df->vkAllocateMemory(device,&allocation,nullptr,&imageMemory),"image memory")){destroyImage();return false;}
    imageAllocationBytes=requirements.size;counters->currentAllocatedBytes+=imageAllocationBytes;
    if(!check(df->vkBindImageMemory(device,image,imageMemory,0),"image binding")){destroyImage();return false;}
    VkImageViewCreateInfo view{};view.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;view.image=image;view.viewType=VK_IMAGE_VIEW_TYPE_2D;view.format=info.format;
    view.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    if(!check(df->vkCreateImageView(device,&view,nullptr,&imageView),"image view")){destroyImage();return false;}
    VkDeviceSize bytes=VkDeviceSize(size.width())*VkDeviceSize(size.height())*4;
    for(auto &slot:staging)if(!makeStaging(slot,bytes)){destroyImage();return false;}
    VkDescriptorImageInfo source{sampler,imageView,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet write{};write.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;write.dstSet=descriptorSet;write.dstBinding=0;
    write.descriptorCount=1;write.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;write.pImageInfo=&source;
    df->vkUpdateDescriptorSets(device,1,&write,0,nullptr);imageSize=size;return true;
}
bool PreviewRenderer::upload(VkCommandBuffer commands,const Frame &frame) {
    if(!ensureImage(frame.size))return false;
    int index=w->currentFrame();
    if(index<0||index>=FramesInFlight||w->concurrentFrameCount()>FramesInFlight){report(QStringLiteral("Vulkan: неподдерживаемая очередь кадров."));return false;}
    auto &slot=staging[size_t(index)];
    // QVulkanWindow has already waited for this frame slot's fence before this callback.
    std::memcpy(slot.mapped,frame.pixels.constData(),size_t(frame.pixels.size()));
    if(!slot.coherent) {
        VkMappedMemoryRange range{};range.sType=VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;range.memory=slot.memory;range.size=VK_WHOLE_SIZE;
        if(!check(df->vkFlushMappedMemoryRanges(device,1,&range),"upload flush"))return false;
    }
    VkImageMemoryBarrier barrier{};barrier.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask=uploaded?VK_ACCESS_SHADER_READ_BIT:0;barrier.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.oldLayout=uploaded?VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:VK_IMAGE_LAYOUT_UNDEFINED;barrier.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcQueueFamilyIndex=barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;barrier.image=image;barrier.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    df->vkCmdPipelineBarrier(commands,uploaded?VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT:VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
    VkBufferImageCopy copy{};copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};copy.imageExtent={uint32_t(imageSize.width()),uint32_t(imageSize.height()),1};
    df->vkCmdCopyBufferToImage(commands,slot.buffer,image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
    barrier.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
    barrier.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;barrier.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    df->vkCmdPipelineBarrier(commands,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,0,nullptr,0,nullptr,1,&barrier);
    ++counters->uploads;
    uploaded=true;uploadedSerial=frame.serial;w->d->displaySize=frame.size;w->d->displayCamera=frame.camera;w->d->displayOptions=frame.options;w->d->displayRevision=frame.revision;w->d->displaySerial=frame.serial;w->d->displayValid=true;
    w->updatePointer(w->d->pointer);
    return true;
}
void PreviewRenderer::startNextFrame() {
    auto commands=w->currentCommandBuffer();QSize extent=w->swapChainImageSize();
    if(!df||!device||extent.isEmpty()){w->frameReady();return;}
    const bool reference=w->d->referenceView;
    const bool scenePrepared=sceneReady&&sceneRenderer.prepare(commands,w->d->scene,w->d->sceneSerial,w->d->lighting,w->d->lightSerial,w->d->frameEpoch.load(std::memory_order_acquire));
    const bool local=!reference&&scenePrepared&&w->d->scene;
    if(reference&&ready&&!w->d->frame.pixels.isEmpty()&&w->d->frame.serial!=uploadedSerial)upload(commands,w->d->frame);
    const bool srgb=w->colorFormat()==VK_FORMAT_B8G8R8A8_SRGB||w->colorFormat()==VK_FORMAT_R8G8B8A8_SRGB||w->colorFormat()==VK_FORMAT_A8B8G8R8_SRGB_PACK32;
    const auto background=local?PreviewSceneRenderer::background(w->d->environment):std::array<float,4>{Background[0],Background[1],Background[2],1};
    VkClearValue clear[2]{};for(int i=0;i<4;i++)clear[0].color.float32[i]=srgb&&i<3?(background[size_t(i)]<=.04045f?background[size_t(i)]/12.92f:std::pow((background[size_t(i)]+.055f)/1.055f,2.4f)):background[size_t(i)];
    clear[1].depthStencil={1,0};
    VkRenderPassBeginInfo pass{};pass.sType=VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;pass.renderPass=w->defaultRenderPass();pass.framebuffer=w->currentFramebuffer();
    pass.renderArea.extent={uint32_t(extent.width()),uint32_t(extent.height())};pass.clearValueCount=2;pass.pClearValues=clear;
    df->vkCmdBeginRenderPass(commands,&pass,VK_SUBPASS_CONTENTS_INLINE);
    bool recorded=false;
    if(local){
        w->d->displayValid=true;w->d->displayCamera=w->d->camera;w->d->displayOptions=w->d->cameraOptions;
        w->d->displaySize=extent;w->d->displayRevision=sceneRenderer.sceneRevision();w->d->displaySerial=w->d->sceneSerial;
        w->updatePointer(w->d->pointer);
        PreviewSceneRenderer::Brush brush;brush.state[1]=srgb?1.f:0.f;
        if(!w->d->brush.isEmpty()&&w->d->hover&&(w->d->drag==Qt::NoButton||w->d->drag==Qt::LeftButton)){
            const QRectF rect(QPointF(0,0),QSizeF(w->size()));const auto point=PreviewMath::project(*w->d->hover,w->d->workY,rect,w->d->camera);
            brush.ring[0]=float(point.x()/rect.width());brush.ring[1]=float(point.y()/rect.height());
            brush.ring[2]=float(w->d->radius/(w->d->camera.span*rect.width()/rect.height()));
            brush.ring[3]=float(w->d->radius*std::sin(w->d->camera.pitch*PreviewMath::Pi/180)/w->d->camera.span);
            brush.state[0]=1;brush.accent[3]=w->d->drag==Qt::LeftButton?.95f:.75f;
        }
        const double cut=w->d->cameraOptions.value("cutY").toDouble(320);
        recorded=sceneRenderer.draw(commands,extent,w->d->camera,w->d->environment,cut,srgb,brush);
    }else if(reference){
    if(ready&&pipeline&&uploaded&&w->d->displayValid) {
        QRectF rect=PreviewMath::fit(extent,imageSize);
        VkViewport viewport{float(rect.x()),float(rect.y()),float(rect.width()),float(rect.height()),0,1};
        int x=std::max(0,int(std::floor(rect.x()))),y=std::max(0,int(std::floor(rect.y())));
        VkRect2D scissor{{x,y},{uint32_t(std::max(0,std::min(extent.width(),int(std::ceil(rect.right())))-x)),uint32_t(std::max(0,std::min(extent.height(),int(std::ceil(rect.bottom())))-y))}};
        df->vkCmdSetViewport(commands,0,1,&viewport);df->vkCmdSetScissor(commands,0,1,&scissor);
        df->vkCmdBindPipeline(commands,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
        df->vkCmdBindDescriptorSets(commands,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineLayout,0,1,&descriptorSet,0,nullptr);
        Overlay overlay;overlay.state[1]=srgb?1:0;
        if(w->width()>0&&w->height()>0&&!w->d->brush.isEmpty()&&w->d->hover&&(w->d->drag==Qt::NoButton||w->d->drag==Qt::LeftButton)) {
            QPointF center;
            // Project against the image's aspect, not a square normalized viewport.
            QRectF logical=PreviewMath::fit(w->size(),w->d->displaySize);
            QPointF projected=PreviewMath::project(*w->d->hover,w->d->workY,logical,w->d->displayCamera);
            center={(projected.x()-logical.x())/logical.width(),(projected.y()-logical.y())/logical.height()};
            overlay.ring[0]=float(center.x());overlay.ring[1]=float(center.y());
            overlay.ring[2]=float(w->d->radius/(w->d->displayCamera.span*double(imageSize.width())/imageSize.height()));
            overlay.ring[3]=float(w->d->radius*std::sin(w->d->displayCamera.pitch*PreviewMath::Pi/180)/w->d->displayCamera.span);
            overlay.state[0]=1;overlay.accent[3]=w->d->drag==Qt::LeftButton?.95f:.75f;
        }
        df->vkCmdPushConstants(commands,pipelineLayout,VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(overlay),&overlay);
        df->vkCmdDraw(commands,3,1,0,0);
    }

    }else w->d->displayValid=false;
    df->vkCmdEndRenderPass(commands);++counters->frames;counters->scene=sceneRenderer.statistics(w->d->sceneSerial);w->frameReady();
    if(recorded){
        if(displayedSceneSerial!=sceneRenderer.serial()){displayedSceneSerial=sceneRenderer.serial();emit w->sceneDisplayed(sceneRenderer.sceneRevision());}
        emit w->localFrameRecorded(counters->frames,sceneRenderer.sceneRevision(),w->camera());
    }
    // Idle never requests another frame; navigation and scene/light receipts schedule bounded redraws.
}

PreviewViewport::PreviewViewport(QWindow *parent):QVulkanWindow(parent),d(std::make_unique<Private>()) {
    setSampleCount(1);setPreferredColorFormats({VK_FORMAT_B8G8R8A8_UNORM,VK_FORMAT_R8G8B8A8_UNORM});
    setFlags(QVulkanWindow::PersistentResources);setCursor(Qt::CrossCursor);
    d->updateTimer.setSingleShot(true);d->updateTimer.setInterval(16);d->updateTimer.setTimerType(Qt::PreciseTimer);
    connect(&d->updateTimer,&QTimer::timeout,this,[this]{requestUpdate();});
}
PreviewViewport::~PreviewViewport()=default;
void PreviewViewport::scheduleUpdate() {
    ++d->counters->redrawRequests;
    if(d->updateTimer.isActive()){++d->counters->coalescedRedraws;return;}
    if(isExposed())d->updateTimer.start();
}
QVulkanWindowRenderer *PreviewViewport::createRenderer(){return new PreviewRenderer(this);}
void PreviewViewport::resetScene() {
    if(QThread::currentThread()!=thread()){emit diagnostic(QStringLiteral("Сеанс предпросмотра должен меняться в потоке интерфейса."));return;}
    {QMutexLocker lock(&d->pendingMutex);d->frameEpoch.fetch_add(1,std::memory_order_release);d->pending.reset();d->frameQueued=false;}
    d->frame=Frame{};d->scene.reset();d->lighting.reset();++d->sceneSerial;++d->lightSerial;
    d->displayValid=false;d->displayRevision=0;d->displaySerial=0;d->displaySize=QSize{};
    d->drag=Qt::NoButton;d->stroke.clear();setCursor(Qt::CrossCursor);updatePointer(d->pointer);scheduleUpdate();
}
void PreviewViewport::setScene(std::shared_ptr<const SceneBundle> scene) {
    if(QThread::currentThread()!=thread()){emit diagnostic(QStringLiteral("Нативная сцена должна приниматься в потоке интерфейса."));return;}
    if(!scene)return;
    const qint64 atlasBytes=qint64(scene->atlasSize.width())*scene->atlasSize.height()*4;
    const qint64 meshBytes=qint64(scene->vertices.size())+scene->indices.size();
    bool valid=scene->sceneRevision>0&&scene->sceneRevision<=9007199254740991ULL&&scene->resourceEpoch>0
        &&qint64(scene->vertexCount)*32==scene->vertices.size()&&qint64(scene->indexCount)*4==scene->indices.size()
        &&meshBytes<=512LL*1024*1024&&scene->atlasSize.width()>0&&scene->atlasSize.height()>0
        &&scene->atlasSize.width()<=8192&&scene->atlasSize.height()<=8192&&atlasBytes==scene->atlas.size()
        &&atlasBytes<=256LL*1024*1024&&meshBytes+atlasBytes+scene->lightmap.size()<=768LL*1024*1024
        &&scene->lightmapSize==QSize(16,16)&&scene->lightmap.size()==1024&&scene->parts.size()<=16384&&std::isfinite(scene->cutY);
    for(double value:scene->origin)valid&=std::isfinite(value);
    for(const auto &part:scene->parts){
        valid&=part.layer>=0&&part.layer<=2&&part.indexCount%3==0&&quint64(part.firstIndex)+part.indexCount<=scene->indexCount;
        for(double value:part.bounds)valid&=std::isfinite(value);
    }
    if(!valid){emit diagnostic(QStringLiteral("Нативная сцена отклонена: размеры, формат или границы превышают контракт."));return;}
    if(d->scene&&scene->sceneRevision<d->scene->sceneRevision)return;
    if(d->scene&&scene->sceneRevision==d->scene->sceneRevision&&scene->resourceEpoch==d->scene->resourceEpoch)return;
    if(d->drag==Qt::LeftButton){d->drag=Qt::NoButton;d->stroke.clear();}
    d->scene=std::move(scene);d->lighting.reset();++d->sceneSerial;++d->lightSerial;
    d->environment=d->scene->environment;if(d->referenceView)d->displayValid=false;d->referenceView=false;
    emit sceneAccepted(d->scene->sceneRevision);scheduleUpdate();
}
void PreviewViewport::setLighting(std::shared_ptr<const SceneLighting> lighting) {
    if(QThread::currentThread()!=thread()){emit diagnostic(QStringLiteral("Освещение должно приниматься в потоке интерфейса."));return;}
    if(!lighting||!d->scene||lighting->sceneRevision!=d->scene->sceneRevision||lighting->resourceEpoch!=d->scene->resourceEpoch)return;
    if(lighting->lightmapSize!=QSize(16,16)||lighting->lightmap.size()!=1024){emit diagnostic(QStringLiteral("Некорректная нативная карта освещения."));return;}
    if(d->lighting&&lighting->revision<=d->lighting->revision)return;
    d->lighting=std::move(lighting);d->environment=d->lighting->environment;++d->lightSerial;
    if(!d->referenceView)scheduleUpdate();
}
void PreviewViewport::setEnvironment(QJsonObject environment) {
    if(QThread::currentThread()!=thread()){emit diagnostic(QStringLiteral("Окружение должно меняться в потоке интерфейса."));return;}
    if(environment==d->environment)return;
    d->environment=std::move(environment);if(d->scene&&!d->referenceView)scheduleUpdate();
}
void PreviewViewport::setReferenceView(bool enabled) {
    if(QThread::currentThread()!=thread())return;
    if(d->referenceView==enabled)return;
    d->referenceView=enabled;d->displayValid=false;d->drag=Qt::NoButton;d->stroke.clear();
    if(enabled&&!d->frame.pixels.isEmpty()){if(++d->nextSerial==0)++d->nextSerial;d->frame.serial=d->nextSerial;}
    updatePointer(d->pointer);scheduleUpdate();
}
void PreviewViewport::setFrame(QByteArray rgba,QSize size,quint64 revision) {
    setReferenceView(true);
    // Legacy callers provide no capture metadata; take the active GUI camera at acceptance.
    setFrameSnapshot(std::move(rgba),size,revision,QThread::currentThread()==thread()?camera():QJsonObject{});
}
void PreviewViewport::setFrameSnapshot(QByteArray rgba,QSize size,quint64 revision,QJsonObject capturedCamera) {
    if(!validFrame(rgba,size,revision)){emit diagnostic(QStringLiteral("Кадр предпросмотра отклонён: неверный размер RGBA8 или версия сцены."));return;}
    if(QThread::currentThread()!=thread()) {
        const auto epoch=d->frameEpoch.load(std::memory_order_acquire);bool schedule=false;
        {QMutexLocker lock(&d->pendingMutex);if(epoch!=d->frameEpoch.load(std::memory_order_acquire)||(d->pending&&revision<d->pending->revision))return;
            d->pending=PendingFrame{std::move(rgba),size,revision,std::move(capturedCamera)};if(!d->frameQueued){d->frameQueued=true;schedule=true;}}
        if(schedule)QMetaObject::invokeMethod(this,[this,epoch]{
            std::optional<PendingFrame> frame;
            {QMutexLocker lock(&d->pendingMutex);if(epoch!=d->frameEpoch.load(std::memory_order_acquire))return;
                frame=std::move(d->pending);d->pending.reset();d->frameQueued=false;}
            if(frame)setFrameSnapshot(std::move(frame->pixels),frame->size,frame->revision,std::move(frame->camera));
        },Qt::QueuedConnection);
        return;
    }
    if(revision<d->frame.revision){emit diagnostic(QStringLiteral("Устаревший кадр сцены отклонён."));return;}
    if(capturedCamera.isEmpty())capturedCamera=camera();
    auto snapshot=PreviewMath::camera(capturedCamera,d->camera);
    auto cut=capturedCamera.value("cutY");
    bool cutValid=!capturedCamera.contains("cutY")||(cut.isDouble()&&std::isfinite(cut.toDouble())&&cut.toDouble()>=-64&&cut.toDouble()<=320);
    if(!snapshot||!cutValid){emit diagnostic(QStringLiteral("Кадр предпросмотра отклонён: неверные параметры камеры захвата."));return;}
    QJsonObject options=capturedCamera;for(const auto &key:snapshot->json().keys())options.remove(key);
    if(d->referenceView&&d->drag==Qt::LeftButton&&(revision!=d->displayRevision||!PreviewMath::same(*snapshot,d->displayCamera)||options!=d->displayOptions)){d->drag=Qt::NoButton;d->stroke.clear();}
    if(++d->nextSerial==0)++d->nextSerial;
    d->frame={std::move(rgba),size,revision,d->nextSerial,*snapshot,options};if(d->referenceView)scheduleUpdate();
}
void PreviewViewport::setCamera(QJsonObject value) {
    if(QThread::currentThread()!=thread()){emit diagnostic(QStringLiteral("Камера должна обновляться в потоке интерфейса."));return;}
    auto next=PreviewMath::camera(value,d->camera);if(!next){emit diagnostic(QStringLiteral("Некорректные параметры камеры."));return;}
    if(value.contains("cutY")){auto cut=value.value("cutY");if(!cut.isDouble()||!std::isfinite(cut.toDouble())||cut.toDouble()<-64||cut.toDouble()>320){emit diagnostic(QStringLiteral("Высота среза должна быть от −64 до 320."));return;}}
    QJsonObject extras=value;for(const auto &key:d->camera.json().keys())extras.remove(key);
    if(PreviewMath::same(d->camera,*next)&&extras==d->cameraOptions)return;
    if(d->drag==Qt::LeftButton){d->drag=Qt::NoButton;d->stroke.clear();scheduleUpdate();}
    d->camera=*next;d->cameraOptions=extras;
    if(d->scene&&!d->referenceView){updatePointer(d->pointer);scheduleUpdate();}
}
QJsonObject PreviewViewport::camera()const{QJsonObject value=d->cameraOptions;auto known=d->camera.json();for(auto i=known.begin();i!=known.end();++i)value.insert(i.key(),i.value());return value;}
QJsonObject PreviewViewport::renderDiagnostics()const {
    const auto &stats=*d->counters;
    QJsonObject result{{"frames",double(stats.frames)},{"uploads",double(stats.uploads)},{"imageAllocations",double(stats.imageAllocations)},
        {"stagingAllocations",double(stats.stagingAllocations)},{"currentAllocatedBytes",double(stats.currentAllocatedBytes)},
        {"lastSceneRevision",double(d->displayRevision)},{"acceptedSceneRevision",double(d->frame.revision)},
        {"frameEpoch",double(d->frameEpoch.load(std::memory_order_acquire))},{"acceptedSerial",double(d->frame.serial)},{"uploadedSerial",double(d->displaySerial)},
        {"frameSize",QJsonArray{d->displaySize.width(),d->displaySize.height()}},{"displayValid",d->displayValid},
        {"pipelineReady",stats.pipelineReady},{"deviceName",stats.deviceName},{"surfaceFormat",stats.surfaceFormat},
        {"redrawRequests",double(stats.redrawRequests)},{"coalescedRedraws",double(stats.coalescedRedraws)},{"interactiveFrameIntervalMs",16},
        {"viewMode",d->referenceView?"native-reference":d->scene?"local-mesh":"waiting"},
        {"scope","Actual Vulkan command records, retained native mesh resources and optional native RGBA references. Presentation/scanout is not measured."}};
    const auto scene=stats.scene.json();for(auto i=scene.begin();i!=scene.end();++i)result.insert(i.key(),i.value());return result;
}
void PreviewViewport::setBrush(QString kind,double radius,double workY) {
    if(QThread::currentThread()!=thread()){emit diagnostic(QStringLiteral("Кисть должна обновляться в потоке интерфейса."));return;}
    if(!validBrush(kind)||!std::isfinite(radius)||!std::isfinite(workY)){emit diagnostic(QStringLiteral("Некорректные параметры кисти."));return;}
    radius=std::clamp(radius,.25,256.0);workY=std::clamp(workY,-2048.0,4096.0);
    if(kind==d->brush&&radius==d->radius&&workY==d->workY)return;
    if(d->drag==Qt::LeftButton){d->drag=Qt::NoButton;d->stroke.clear();}
    d->brush=std::move(kind);d->radius=radius;d->workY=workY;updatePointer(d->pointer);scheduleUpdate();
}
void PreviewViewport::navigate(const QJsonObject &value) {
    auto before=camera();auto merged=d->cameraOptions;for(auto i=value.begin();i!=value.end();++i)merged.insert(i.key(),i.value());
    setCamera(merged);if(before!=camera())emit cameraChanged(camera());
}
QRectF PreviewViewport::viewRect()const {
    return d->scene&&!d->referenceView?QRectF(QPointF(0,0),QSizeF(size())):PreviewMath::fit(size(),d->displaySize.isEmpty()?QSize(16,9):d->displaySize);
}
void PreviewViewport::updatePointer(QPointF point) {
    d->pointer=point;d->hover.reset();
    if(d->displayValid) {
        const auto &camera=d->scene&&!d->referenceView?d->camera:d->displayCamera;
        auto world=PreviewMath::unproject(point,viewRect(),camera,d->workY);
        if(world&&std::abs(world->x())<=30000000&&std::abs(world->y())<=30000000)d->hover=world;
    }
    QJsonObject position{{"valid",bool(d->hover)},{"plane","workY"}};
    if(d->hover){position.insert("x",d->hover->x());position.insert("y",d->workY);position.insert("z",d->hover->y());}
    if(position!=d->lastPointer){d->lastPointer=position;emit pointerWorldChanged(position);}
}
void PreviewViewport::mousePressEvent(QMouseEvent *event) {
    if(d->drag!=Qt::NoButton){event->ignore();return;}
    updatePointer(event->position());d->lastDrag=event->position();
    if(event->button()==Qt::LeftButton) {
        const bool local=d->scene&&!d->referenceView;
        if(d->brush.isEmpty()||!d->hover||!isValid()||!d->displayValid
                ||(!local&&(d->displaySerial!=d->frame.serial||!PreviewMath::same(d->camera,d->displayCamera)||d->cameraOptions!=d->displayOptions))){event->ignore();return;}
        d->stroke.clear();d->stroke.push_back(*d->hover);d->strokeLimitReported=false;d->drag=Qt::LeftButton;
    }else if(event->button()==Qt::RightButton||event->button()==Qt::MiddleButton) {
        d->drag=event->button();setCursor(Qt::ClosedHandCursor);
    }else {event->ignore();return;}
    event->accept();scheduleUpdate();
}
void PreviewViewport::mouseMoveEvent(QMouseEvent *event) {
    QPointF delta=event->position()-d->lastDrag;d->lastDrag=event->position();
    if(d->drag==Qt::RightButton) {
        auto next=d->camera;next.yaw+=delta.x()*.28;next.pitch+=delta.y()*.22;navigate(next.json());
    }else if(d->drag==Qt::MiddleButton) {
        QRectF rect=viewRect();
        auto a=PreviewMath::unproject(event->position()-delta,rect,d->camera,d->workY),b=PreviewMath::unproject(event->position(),rect,d->camera,d->workY);
        if(a&&b){auto next=d->camera;next.x-=b->x()-a->x();next.z-=b->y()-a->y();navigate(next.json());}
    }
    updatePointer(event->position());
    if(d->drag==Qt::LeftButton&&d->hover) {
        double distance=std::hypot(d->hover->x()-d->stroke.last().x(),d->hover->y()-d->stroke.last().y());
        if(distance>=std::max(.25,d->radius*.08)) {
            if(d->stroke.size()<MaxStrokePoints)d->stroke.push_back(*d->hover);
            else {d->stroke.last()=*d->hover;if(!d->strokeLimitReported){d->strokeLimitReported=true;emit diagnostic(QStringLiteral("Достигнут предел точек одного мазка."));}}
        }
    }
    event->accept();if(!d->brush.isEmpty()||(d->scene&&!d->referenceView))scheduleUpdate();
}
void PreviewViewport::mouseReleaseEvent(QMouseEvent *event) {
    if(event->button()!=d->drag){event->ignore();return;}
    updatePointer(event->position());
    bool brush=d->drag==Qt::LeftButton;d->drag=Qt::NoButton;setCursor(Qt::CrossCursor);
    if(brush&&!d->stroke.isEmpty()) {
        if(d->hover&&d->stroke.size()<MaxStrokePoints&&d->stroke.last()!=*d->hover)d->stroke.push_back(*d->hover);
        auto sampled=PreviewMath::resample(d->stroke,32);
        QJsonArray points;for(auto point:sampled)points.append(QJsonArray{point.x(),point.y()});
        auto center=sampled.last();d->stroke.clear();
        emit brushStroke(QJsonObject{{"kind",d->brush},{"enabled",true},{"x",center.x()},{"y",d->workY},{"z",center.y()},
                {"radius",d->radius},{"points",points},{"sceneRevision",double(d->displayRevision)}});
    }
    event->accept();scheduleUpdate();
}
void PreviewViewport::wheelEvent(QWheelEvent *event) {
    double steps=event->angleDelta().y()/120.0;if(steps==0)steps=event->pixelDelta().y()/60.0;
    if(steps==0){event->ignore();return;}
    auto next=d->camera;QRectF rect=viewRect();
    auto before=PreviewMath::unproject(event->position(),rect,next,d->workY);
    next.span=std::clamp(next.span*std::exp(-steps*.13),4.0,4096.0);
    auto after=PreviewMath::unproject(event->position(),rect,next,d->workY);
    if(before&&after){next.x+=before->x()-after->x();next.z+=before->y()-after->y();}
    navigate(next.json());updatePointer(event->position());event->accept();
}
bool PreviewViewport::event(QEvent *event) {
    if(event->type()==QEvent::Leave){updatePointer({-1,-1});scheduleUpdate();}
    if(event->type()==QEvent::FocusOut||event->type()==QEvent::Hide) {
        d->drag=Qt::NoButton;d->stroke.clear();updatePointer({-1,-1});setCursor(Qt::CrossCursor);
        if(event->type()==QEvent::Hide)d->updateTimer.stop();else scheduleUpdate();
    }
    bool handled=QVulkanWindow::event(event);
    if(event->type()==QEvent::Resize)updatePointer(d->pointer);
    return handled;
}
