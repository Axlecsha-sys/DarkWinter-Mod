#include "material_mips.h"
#include "../src/material_mips.hpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <stdexcept>
#include <vector>

static uint32_t assertions=0;
static void require(bool okay,const char* reason) {
    ++assertions;
    if(!okay)throw std::runtime_error(reason);
}
int main() {
    try {
        require(wke_material_api_version()==1,"CPU ABI1");
        require(wke_material_mip_words(0,16,5)==0,"Reject zero width");
        require(wke_material_mip_words(1,1,2)==0,"Reject repeated terminal level");
        require(wke_material_mip_words(16384,16384,1)==0,"Reject memory budget overflow");
        require(wke_material_mip_words(16,1,5)==31,"Rectangular terminal dimensions");
        require(wke_material_mip_words(32,32,6)==1365,"Full chain word count");
        std::vector<uint32_t> simple={0xff00ff00u,0u,0u,0u};
        std::vector<uint32_t> small(5,0x12345678u);
        double tinyCoverage[2]{};
        require(wke_material_build_mips(simple.data(),4,2,2,2,.5f,0,small.data(),5,tinyCoverage,2)==0,"Premultiplied chain");
        require((small[4]&0x00ffffffu)==0x0000ff00u,"No black transparent RGB in downsample");
        require((small[4]>>24)==64,"Alpha averages linearly");
        require(wke_material_build_mips(nullptr,4,2,2,2,.5f,0,small.data(),5,nullptr,0)!=0,"Null input rejected");
        require(wke_material_build_mips(simple.data(),4,2,2,2,.5f,0,small.data(),4,nullptr,0)!=0,"Short output rejected");
        require(wke_material_build_mips(simple.data(),4,2,2,2,std::nanf(""),0,small.data(),5,nullptr,0)!=0,"NaN threshold rejected");
        require(wke_material_build_mips(simple.data(),4,2,2,2,.5f,8,small.data(),5,nullptr,0)!=0,"Unknown flags rejected");
        std::vector<uint32_t> odd(15,0xffffffffu),oddOutput(size_t(wke_material_mip_words(3,5,3)));
        odd.back()=0xff000000u;
        require(wke_material_build_mips(odd.data(),odd.size(),3,5,3,.5f,0,oddOutput.data(),oddOutput.size(),nullptr,0)==0,"Odd rectangular footprint includes edges");
        require((oddOutput.back()&255u)<255,"Odd final edge is filtered rather than dropped");
        std::vector<uint32_t> strip(16,0xffdddd44u),stripOutput(31);
        double stripCoverage[5]{};
        require(wke_material_build_mips(strip.data(),strip.size(),16,1,5,.5f,3,stripOutput.data(),stripOutput.size(),stripCoverage,5)==0,"One-dimensional full chain");
        for(double value:stripCoverage)require(std::isfinite(value),"Finite terminal coverage");

        constexpr uint32_t size=64,levels=7;
        std::vector<uint32_t> source(size*size);
        for(uint32_t y=0;y<size;++y)for(uint32_t x=0;x<size;++x) {
            const int centre=24+int(y%11),distance=std::abs(int(x)-centre);
            uint32_t alpha=distance<=1?255u:distance==2?155u:distance==3?30u:0u;
            if((y%9)<2&&x>7&&x<54)alpha=std::max(alpha,210u);
            source[size_t(y)*size+x]=(alpha<<24)|0x003b9425u;
        }
        const size_t words=size_t(wke_material_mip_words(size,size,levels));
        std::vector<uint32_t> raw(words),filtered(words),padded(words),aliased(words);
        double before[levels]{},after[levels]{};
        require(wke_material_build_mips(source.data(),source.size(),size,size,levels,.5f,0,raw.data(),words,before,levels)==0,"Raw chain");
        require(wke_material_build_mips(source.data(),source.size(),size,size,levels,.5f,1,filtered.data(),words,after,levels)==0,"Coverage chain");
        require(wke_material_build_mips(source.data(),source.size(),size,size,levels,.5f,3,padded.data(),words,nullptr,0)==0,"Padded chain");
        size_t offset=0;
        for(uint32_t level=0,dim=size;level<levels;++level,dim=std::max(1u,dim>>1)) {
            require(std::isfinite(after[level]),"Coverage finite");
            require(std::abs(after[level]-before[0])<=std::abs(before[level]-before[0])+1e-12,"Coverage fitting cannot worsen raw mip error");
            for(size_t i=0;i<size_t(dim)*dim;++i) {
                require((filtered[offset+i]&0x00ffffffu)==(raw[offset+i]&0x00ffffffu),"Adjusted alpha never contaminates later raw RGB");
                require((padded[offset+i]>>24)==(filtered[offset+i]>>24),"RGB padding preserves alpha");
            }
            std::printf("material level=%u raw=%.8f fitted=%.8f target=%.8f\n",level,before[level],after[level],before[0]);
            offset+=size_t(dim)*dim;
        }
        for(size_t i=0;i<source.size();++i) {
            require((padded[i]>>24)==(source[i]>>24),"Level0 alpha preserved");
            if(source[i]>>24)require(padded[i]==source[i],"Visible authored level0 RGB preserved");
        }
        std::copy(source.begin(),source.end(),aliased.begin());
        require(wke_material_build_mips(aliased.data(),source.size(),size,size,levels,.5f,3,aliased.data(),words,nullptr,0)==0,"Exact input/output alias accepted");
        require(aliased==padded,"Exact alias has identical output");
        std::printf("WKE_MATERIAL_MIPS_PASS assertions=%u\n",assertions);
        return 0;
    } catch(const std::exception& error) {
        std::fprintf(stderr,"WKE_MATERIAL_MIPS_FAIL %s\n",error.what());
        return 1;
    }
}
