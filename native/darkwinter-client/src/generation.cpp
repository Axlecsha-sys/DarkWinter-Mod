#include "generation.hpp"
#include <string_view>
#include <optional>
namespace dw::client {
static int floorDiv(int n,int d){int q=n/d;return q-(n<0&&n%d!=0);}
uint64_t Generator::hash(int x,int z,uint64_t salt)const{
 uint64_t h=seed^salt^(uint64_t(uint32_t(x))*0x9e3779b185ebca87ull)^(uint64_t(uint32_t(z))*0xc2b2ae3d27d4eb4full);
 h^=h>>30;h*=0xbf58476d1ce4e5b9ull;h^=h>>27;h*=0x94d049bb133111ebull;return h^(h>>31);
}
double Generator::noise(double x,double z,uint64_t salt)const{
 int ix=int(std::floor(x)),iz=int(std::floor(z));double fx=x-ix,fz=z-iz;fx=fx*fx*(3-2*fx);fz=fz*fz*(3-2*fz);
 auto n=[&](int a,int b){return double(hash(a,b,salt)>>11)*(1./9007199254740992.)*2-1;};
 return std::lerp(std::lerp(n(ix,iz),n(ix+1,iz),fx),std::lerp(n(ix,iz+1),n(ix+1,iz+1),fx),fz);
}
uint32_t Generator::find(std::string_view prefix)const{for(uint32_t i=0;i<names.size();i++)if(names[i].starts_with(prefix))return i;return 0;}
Generator::Generator(const Scene& scene,uint64_t value):seed(value){
 if(scene.world.size()>=16){nativeMinY=std::bit_cast<int32_t>(scene.world[1])*16;nativeMaxY=nativeMinY+int(scene.world[5])*16;}
 for(uint32_t i=0;i<scene.materials;i++)names.push_back(scene.materialName(i));
 stone=find("minecraft:stone");dirt=find("minecraft:dirt");grass=find("minecraft:grass_block[snowy=true]");slab=find("darkwinter:test_snowy_grass_slab[");snow=find("minecraft:snow[layers=1]");tuft=find("darkwinter:dry_grass_tuft[");rock=find("darkwinter:rock_scatter[");
 if(!stone||!dirt||!grass||!slab||!snow)throw std::runtime_error("Native biome palette incomplete");
 for(uint32_t i=0;i<names.size();i++){
  const auto& n=names[i];if(n.starts_with("darkwinter:test_spruce_log["))for(int r=1;r<=5;r++)if(n.find("radius="+std::to_string(r))!=std::string::npos)logs[r]=i;
  if(n.starts_with("darkwinter:test_spruce_bough["))for(int d=0;d<8;d++)if(n.find("direction="+std::to_string(d))!=std::string::npos)for(int drop=0;drop<2;drop++)if(n.find("drop="+std::to_string(drop))!=std::string::npos ){if(n.find("tip=true")!=std::string::npos)boughs[d][drop]=i;else innerBoughs[d][drop]=i;}
 }
 static constexpr const char* facing[]={"north","east","south","west"};for(int i=0;i<4;i++)for(uint32_t m=0;m<names.size();m++)if(names[m].starts_with("darkwinter:snowy_shrub[")&&names[m].find(std::string("facing=")+facing[i])!=std::string::npos){if(names[m].find("part=0")!=std::string::npos)shrubs[i]=m;else if(names[m].find("part=1")!=std::string::npos)shrubTops[i]=m;}
 for(int r=1;r<=5;r++)if(!logs[r])logs[r]=find("darkwinter:test_spruce_log[");
 for(auto& b:boughs)for(auto& m:b)if(!m)m=find("darkwinter:test_spruce_bough[");for(int d=0;d<8;d++)for(int drop=0;drop<2;drop++)if(!innerBoughs[d][drop])innerBoughs[d][drop]=boughs[d][drop];
}
double Generator::height(int x,int z)const{
 double h=76+8*noise(x/180.,z/180.,0x2134)+2*noise(x/34.,z/34.,0x8421);
 return std::floor(h*2)/2;
}

bool Generator::naturalLeader(int x,int y,int z,uint32_t material)const{
 const uint32_t expected=logs[generationVersion>=4?1:2];
 if(!expected||material!=expected)return false;
 const int spacing=generationVersion>=2?8:12;
 const int gx=floorDiv(x,spacing),gz=floorDiv(z,spacing);
 const uint64_t random=hash(gx,gz,0x74726565);
 if(random%5==0)return false;
 const int jitter=generationVersion>=2?4:8;
 const int wantedX=gx*spacing+2+int((random>>8)%jitter);
 const int wantedZ=gz*spacing+2+int((random>>16)%jitter);
 // Legacy anchors never move inside their cell, so reject other columns before
 // sampling terrain. Modern support searches are cached by seed/version/anchor.
 if(generationVersion<3&&(x!=wantedX||z!=wantedZ))return false;
 const size_t index=size_t(random^(random>>32)^generationVersion)&(leaderAnchors.size()-1);
 auto& cached=leaderAnchors[index];
 if(cached.version!=generationVersion||cached.gx!=gx||cached.gz!=gz){
  cached={};cached.version=generationVersion;cached.gx=gx;cached.gz=gz;
  cached.tall=12+int((random>>24)%7);
  if(generationVersion<3){
   cached.x=wantedX;cached.z=wantedZ;cached.base=int(std::ceil(height(wantedX,wantedZ)));cached.present=true;
  }else{
   int bestDistance=INT32_MAX;uint64_t bestTie=UINT64_MAX;
   // Match generateGrounded's support rule exactly. This predicate changes only
   // the selected render variant; it never edits or relocates a saved tree.
   auto consider=[&](int targetX,int targetZ){
    int distance=(targetX-wantedX)*(targetX-wantedX)+(targetZ-wantedZ)*(targetZ-wantedZ);
    if(distance>bestDistance)return;
    double h=height(targetX,targetZ);int base=int(std::ceil(h));
    if(std::floor(h)!=h||base<nativeMinY||base+cached.tall>nativeMaxY)return;
    uint64_t tie=hash(targetX,targetZ,random^0x737570706f7274ull);
    if(distance<bestDistance||(distance==bestDistance&&tie<bestTie)){
     cached.x=targetX;cached.z=targetZ;cached.base=base;cached.present=true;
     bestDistance=distance;bestTie=tie;
    }
   };
   consider(wantedX,wantedZ);
   if(!cached.present)for(int dz=0;dz<spacing;++dz)for(int dx=0;dx<spacing;++dx)
    consider(gx*spacing+dx,gz*spacing+dz);
  }
 }
 return cached.present&&cached.x==x&&cached.z==z&&cached.base+cached.tall-1==y;
}
GeneratedColumn Generator::generate(int sx,int sz,int minY,int sectionsY,const std::atomic<bool>* cancel)const{
 // Keep stored v1/v2 generation byte-for-byte in the original path.
 if(generationVersion>=3)return generateGrounded(sx,sz,minY,sectionsY,cancel);
 GeneratedColumn out;out.x=sx;out.z=sz;out.sections.resize(sectionsY);
 for(int i=0;i<sectionsY;i++){out.sections[i].first={sx,minY+i,sz};out.sections[i].second.fill(0);}
 auto put=[&](int x,int y,int z,uint32_t m){if(x<sx*16||x>=sx*16+16||z<sz*16||z>=sz*16+16||y<minY*16||y>=(minY+sectionsY)*16||!m)return;out.sections[size_t(y/16-minY)].second[size_t(x-sx*16)+16*size_t(z-sz*16)+256*size_t(y%16)]=m;};
 for(int z=sz*16;z<sz*16+16;z++)for(int x=sx*16;x<sx*16+16;x++){
  if(cancel&&cancel->load())return out;double h=height(x,z);int top=int(std::ceil(h))-1;bool half=std::floor(h)!=h;
  for(int y=minY*16;y<=top;y++)put(x,y,z,y<top-3?stone:y<top?dirt:half?slab:grass);
  if(!half)put(x,top+1,z,snow);
  uint64_t r=hash(x,z,0xdec0);if(r%113==0)put(x,top+1,z,rock);else if(generationVersion>=2&&r%67==0){int d=int((r>>12)&3);put(x,top+1,z,shrubs[d]);put(x,top+2,z,shrubTops[d]);}else if(r%31==0)put(x,top+1,z,tuft);
 }
 // Evaluate world-space anchors in the halo, so chunk order cannot change a tree.
 static constexpr int dx[]={0,1,1,1,0,-1,-1,-1},dz[]={1,1,0,-1,-1,-1,0,1};
 int spacing=generationVersion>=2?8:12;
 for(int gz=floorDiv(sz*16-6,spacing);gz<=floorDiv(sz*16+21,spacing);gz++)for(int gx=floorDiv(sx*16-6,spacing);gx<=floorDiv(sx*16+21,spacing);gx++){
  auto r=hash(gx,gz,0x74726565);if(r%5==0)continue;int jitter=generationVersion>=2?4:8;int x=gx*spacing+2+int((r>>8)%jitter),z=gz*spacing+2+int((r>>16)%jitter),base=int(std::ceil(height(x,z))),tall=12+int((r>>24)%7);
  put(x,base-1,z,grass);
  for(int y=0;y<tall;y++)put(x,base+y,z,logs[std::clamp(5-y*4/tall,1,5)]);
  for(int y=3;y<tall-1;y+=2){int radius=y>tall-5?1:2;for(int d=0;d<8;d++){
    int drop=int((r>>((y+d)%48))&1),zDirection=generationVersion>=2?-dz[d]:dz[d];put(x+dx[d]*radius,base+y,z+zDirection*radius,boughs[d][drop]);
    if(radius==2)put(x+dx[d],base+y,z+zDirection,generationVersion>=2?innerBoughs[d][0]:boughs[d][0]);
  }}
 }
 return out;
}

GeneratedColumn Generator::generateGrounded(int sx,int sz,int minY,int sectionsY,const std::atomic<bool>* cancel)const{
 const bool livingCrown=generationVersion>=4;
 GeneratedColumn out;out.x=sx;out.z=sz;out.sections.resize(sectionsY);
 for(int i=0;i<sectionsY;++i){out.sections[i].first={sx,minY+i,sz};out.sections[i].second.fill(0);}
 const int x0=sx*16,z0=sz*16,y0=minY*16,y1=(minY+sectionsY)*16;
 auto cancelled=[&](){return cancel&&cancel->load();};
 auto put=[&](int x,int y,int z,uint32_t material){
  if(!material||x<x0||x>=x0+16||z<z0||z>=z0+16||y<y0||y>=y1)return;
  int sectionY=floorDiv(y,16),localY=y-sectionY*16;
  out.sections[size_t(sectionY-minY)].second[size_t(x-x0)+16*size_t(z-z0)+256*size_t(localY)]=material;
 };
 auto at=[&](int x,int y,int z){
  if(x<x0||x>=x0+16||z<z0||z>=z0+16||y<y0||y>=y1)return UINT32_MAX;
  int sectionY=floorDiv(y,16),localY=y-sectionY*16;
  return out.sections[size_t(sectionY-minY)].second[size_t(x-x0)+16*size_t(z-z0)+256*size_t(localY)];
 };

 // The terrain and its snow cap stay unchanged. Cache neighbouring samples for
 // support searches instead of reevaluating both noise fields for every plant.
 struct Surface {int base=0;bool half=false;};
 constexpr int cacheSide=32,cacheMargin=8;
 std::array<Surface,cacheSide*cacheSide> cached{};
 std::array<uint8_t,cacheSide*cacheSide> ready{};
 auto surface=[&](int x,int z){
  int cx=x-x0+cacheMargin,cz=z-z0+cacheMargin;
  auto sample=[&](){double h=height(x,z);return Surface{int(std::ceil(h)),std::floor(h)!=h};};
  if(unsigned(cx)>=cacheSide||unsigned(cz)>=cacheSide)return sample();
  size_t index=size_t(cx)+cacheSide*size_t(cz);
  if(!ready[index]){cached[index]=sample();ready[index]=1;}
  return cached[index];
 };
 for(int z=z0;z<z0+16;++z)for(int x=x0;x<x0+16;++x){
  if(cancelled())return out;
  Surface ground=surface(x,z);int top=ground.base-1;
  for(int y=y0;y<=top;++y)put(x,y,z,y<top-3?stone:y<top?dirt:ground.half?slab:grass);
  if(!ground.half)put(x,ground.base,z,snow);
 }

 struct Tree {int x=0,z=0,base=0,tall=0;uint64_t random=0;};
 std::array<Tree,16> trees{};size_t treeCount=0;
 constexpr int spacing=8;
 // Each anchor owns its original 8x8 cell. Search the nearest genuine full-soil
 // position inside that cell; never raise one terrain block into a pedestal.
 for(int gz=floorDiv(z0-6,spacing);gz<=floorDiv(z0+21,spacing);++gz)
  for(int gx=floorDiv(x0-6,spacing);gx<=floorDiv(x0+21,spacing);++gx){
   if(cancelled())return out;
   uint64_t random=hash(gx,gz,0x74726565);if(random%5==0)continue;
   int wantedX=gx*spacing+2+int((random>>8)%4),wantedZ=gz*spacing+2+int((random>>16)%4);
   std::optional<Tree> selected;int bestDistance=INT32_MAX;uint64_t bestTie=UINT64_MAX;
   const int tall=12+int((random>>24)%7);
   auto consider=[&](int x,int z){
    int distance=(x-wantedX)*(x-wantedX)+(z-wantedZ)*(z-wantedZ);
    if(distance>bestDistance)return;
    Surface ground=surface(x,z);if(ground.half||ground.base<y0||ground.base+tall>y1)return;
    uint64_t tie=hash(x,z,random^0x737570706f7274ull);
    if(distance<bestDistance||(distance==bestDistance&&tie<bestTie)){
     selected=Tree{x,z,ground.base,tall,random};bestDistance=distance;bestTie=tie;
    }
   };
   // Most original anchors already have full soil. Resolve them before walking
   // the cell, and avoid noise samples farther away than a valid chosen point.
   consider(wantedX,wantedZ);
   if(!selected)for(int dz=0;dz<spacing;++dz)for(int dx=0;dx<spacing;++dx)
    consider(gx*spacing+dx,gz*spacing+dz);
   if(!selected)continue;
   if(treeCount==trees.size())throw std::runtime_error("Native tree anchor halo limit");
   trees[treeCount++]=*selected;
  }

 static constexpr int dx[]={0,1,1,1,0,-1,-1,-1},dz[]={1,1,0,-1,-1,-1,0,1};
 // Publish all trunks first. A neighbouring tree's branch must not replace a
 // trunk cell when a support search brings two anchors closer together.
 for(size_t i=0;i<treeCount;++i){
  const Tree& tree=trees[i];
  // The old four-band taper stopped at radius 2. Five bands reach radius 1
  // before the terminal segment, whose authored variant carries a needle crown.
  const int taperBands=livingCrown?5:4;
  for(int y=0;y<tree.tall;++y)put(tree.x,tree.base+y,tree.z,logs[std::clamp(5-y*taperBands/tree.tall,1,5)]);
 }
 auto putBough=[&](int x,int y,int z,uint32_t material){
  uint32_t existing=at(x,y,z);
  for(size_t radius=1;radius<logs.size();++radius)if(logs[radius]&&existing==logs[radius])return;
  put(x,y,z,material);
 };
 for(size_t i=0;i<treeCount;++i){
  const Tree& tree=trees[i];
  auto tier=[&](int y){
   int radius=y>tree.tall-5?1:2;
   for(int d=0;d<8;++d){
    // Keep the last lateral tier raised to meet the leader crown. A full-size
    // ring on the terminal cell would create a flat umbrella instead of a tip.
    int drop=livingCrown&&y==tree.tall-2?0:int((tree.random>>((y+d)%48))&1);
    putBough(tree.x+dx[d]*radius,tree.base+y,tree.z-dz[d]*radius,boughs[d][drop]);
    if(radius==2)putBough(tree.x+dx[d],tree.base+y,tree.z-dz[d],innerBoughs[d][0]);
   }
  };
  for(int y=3;y<tree.tall-1;y+=2)tier(y);
  const int upperTier=tree.tall-2;
  if(livingCrown&&(upperTier-3)%2!=0)tier(upperTier);
 }
 // Queries also work outside this output column, so the same source plant picks
 // the same destination on both sides of a streaming boundary.
 auto treeBlocks=[&](int x,int y,int z){
  for(size_t i=0;i<treeCount;++i){
   const Tree& tree=trees[i];int level=y-tree.base;
   if(level>=0&&level<tree.tall&&x==tree.x&&z==tree.z)return true;
   bool regularTier=level>=3&&level<tree.tall-1&&(level-3)%2==0;
   bool upperTier=livingCrown&&level==tree.tall-2;
   if(!regularTier&&!upperTier)continue;
   int radius=level>tree.tall-5?1:2;
   for(int d=0;d<8;++d)
    if((x==tree.x+dx[d]*radius&&z==tree.z-dz[d]*radius)
      ||(radius==2&&x==tree.x+dx[d]&&z==tree.z-dz[d]))return true;
  }
  return false;
 };

 struct Decoration {
  uint32_t material=0,upper=0;int base=0,distance=0,sourceX=0,sourceZ=0;uint64_t priority=0;
 };
 std::array<Decoration,256> decorations{};
 constexpr int searchRadius=3;
 // Relocate half-terrace candidates before dropping them. A deterministic winner
 // handles competing candidates without relying on column generation order.
 for(int z=z0-searchRadius;z<z0+16+searchRadius;++z)
  for(int x=x0-searchRadius;x<x0+16+searchRadius;++x){
   if(cancelled())return out;
   uint64_t random=hash(x,z,0xdec0);uint32_t material=0,upper=0;
   if(random%113==0)material=rock;
   else if(random%67==0){int facing=int((random>>12)&3);material=shrubs[facing];upper=shrubTops[facing];if(!upper)continue;}
   else if(random%31==0)material=tuft;
   if(!material)continue;
   int selectedX=0,selectedZ=0,selectedBase=0,bestDistance=INT32_MAX;uint64_t bestTie=UINT64_MAX;
   for(int oz=-searchRadius;oz<=searchRadius;++oz)for(int ox=-searchRadius;ox<=searchRadius;++ox){
    int targetX=x+ox,targetZ=z+oz,distance=ox*ox+oz*oz;
    if(distance>bestDistance)continue;
    Surface ground=surface(targetX,targetZ);
    if(ground.half||ground.base<y0||ground.base+(upper?1:0)>=y1
      ||treeBlocks(targetX,ground.base,targetZ)||(upper&&treeBlocks(targetX,ground.base+1,targetZ)))continue;
    uint64_t tie=hash(targetX,targetZ,random^0x706c616e74ull);
    if(distance<bestDistance||(distance==bestDistance&&tie<bestTie)){
     selectedX=targetX;selectedZ=targetZ;selectedBase=ground.base;bestDistance=distance;bestTie=tie;
    }
   }
   if(bestDistance==INT32_MAX||selectedX<x0||selectedX>=x0+16||selectedZ<z0||selectedZ>=z0+16)continue;
   auto& previous=decorations[size_t(selectedX-x0)+16*size_t(selectedZ-z0)];
   bool wins=!previous.material||bestDistance<previous.distance
    ||(bestDistance==previous.distance&&(random<previous.priority
      ||(random==previous.priority&&(x<previous.sourceX||(x==previous.sourceX&&z<previous.sourceZ)))));
   if(wins)previous={material,upper,selectedBase,bestDistance,x,z,random};
  }
 for(int z=z0;z<z0+16;++z)for(int x=x0;x<x0+16;++x){
  const auto& decoration=decorations[size_t(x-x0)+16*size_t(z-z0)];if(!decoration.material)continue;
  uint32_t below=at(x,decoration.base-1,z),bottom=at(x,decoration.base,z);
  uint32_t top=decoration.upper?at(x,decoration.base+1,z):0;
  // Validate both parts before writing either one. Plants may replace the natural
  // floor snow, but may not replace ground, a trunk, branches or another assembly.
  if(below!=grass||(bottom!=0&&bottom!=snow)||(decoration.upper&&top!=0))continue;
  put(x,decoration.base,z,decoration.material);
  if(decoration.upper)put(x,decoration.base+1,z,decoration.upper);
 }
 return out;
}
}
