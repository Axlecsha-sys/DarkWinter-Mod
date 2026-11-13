#pragma once
#include "scene.hpp"
#include "material_mips.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>

namespace dw::client {
// GPU texture-array packing for the standalone host; filtering is the same CPU
// material kernel used by the embedded Minecraft renderer.
struct WorldMipChain {
    uint32_t size=1,layers=0,levels=1;
    struct Slice {size_t wordOffset=0;uint32_t level=0,layer=0,size=0;};
    std::vector<uint32_t> pixels;
    std::vector<Slice> slices;

    explicit WorldMipChain(const std::vector<uint32_t>& a) {
        if(a.size()<16||!a[8]||a[8]>256)throw std::runtime_error("World texture count");
        layers=a[8];
        for(uint32_t layer=0;layer<layers;++layer) {
            size_t d=a[4]+size_t(layer)*4;
            if(d+4>a.size()||!a[d+1]||!a[d+2])throw std::runtime_error("World texture bounds");
            size=std::max(size,std::max(a[d+1],a[d+2]));
        }
        size=std::bit_ceil(size);
        if(size>2048)throw std::runtime_error("World texture layer extent");
        levels=std::bit_width(size);
        std::vector<uint8_t> masked(layers,0);
        for(uint32_t triangle=0;triangle<a[7];++triangle) {
            size_t t=a[3]+size_t(triangle)*24;
            if(t+24>a.size()||a[t+3]>=layers)throw std::runtime_error("World texture triangle");
            if(a[t+7]&8u)masked[a[t+3]]=1;
        }
        const uint64_t layerWords=wke::materials::mip_words(size,size,levels);
        if(!layerWords||layerWords*layers>size_t(128)*1024*1024/4)throw std::runtime_error("World mip memory limit");
        pixels.reserve(size_t(layerWords)*layers);
        slices.reserve(size_t(layers)*levels);
        for(uint32_t layer=0;layer<layers;++layer) {
            const size_t descriptor=a[4]+size_t(layer)*4,source=size_t(a[5])+a[descriptor];
            const uint32_t width=a[descriptor+1],height=a[descriptor+2];
            if(source+size_t(width)*height>a.size())throw std::runtime_error("World texture pixels");
            const size_t first=pixels.size();
            pixels.resize(first+size_t(layerWords));
            for(uint32_t y=0;y<size;++y)for(uint32_t x=0;x<size;++x)
                pixels[first+size_t(y)*size+x]=a[source+size_t(y)*height/size*width+size_t(x)*width/size];
            auto output=std::span<uint32_t>(pixels).subspan(first,size_t(layerWords));
            wke::materials::build(output.first(size_t(size)*size),size,size,levels,.5f,
                masked[layer]?wke::materials::cutout|wke::materials::color_padding:0u,output);
            size_t offset=first;
            for(uint32_t level=0,dimension=size;level<levels;++level,dimension=std::max(1u,dimension>>1)) {
                slices.push_back({offset,level,layer,dimension});
                offset+=size_t(dimension)*dimension;
            }
        }
    }
};
}
