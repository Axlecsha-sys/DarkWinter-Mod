"""Generate algorithm-only candidates from the frozen stage-3 shader."""
import json,hashlib,sys
from pathlib import Path
root=Path(sys.argv[1]);out=root/'build/core/render-study';base=(root/'native/darkwinter-client/checks/reference/trace-stage3.comp').read_text()
assert hashlib.sha256(base.encode()).hexdigest()=='4b108c22c66d48545894fe2e4722cea00a5f379b523de19d2ad5745e8035ee6e'
def grid(s):
 s=s.replace('sec=ivec3(floor(vec3(cell)/16))','sec=cell>>4')
 s=s.replace('ivec3 local=cell-sec*16;','ivec3 local=cell&15;')
 s=s.replace('section=ivec3(floor(vec3(cell)/16))','section=cell>>4')
 s=s.replace('ivec3 local=cell-section*16;','ivec3 local=cell&15;')
 s=s.replace('vec3 low=floor(vec3(cell)/size)*size;','vec3 low=vec3(cell&ivec3(~(size-1)));')
 return s
def shadow(s):
 return s.replace('shape,t,min(boundary,end),occlusion,hit)','shape,t,occlusion?end:min(boundary,end),occlusion,hit)')
def occupied(s):
 old='for(int level=4;level>=0;--level)if(!occupied(slot,local,level)){size=1<<level;break;}'
 new='if(!occupied(slot,local,4))size=16;else if(!occupied(slot,local,0)){for(int level=3;level>=1;--level)if(!occupied(slot,local,level)){size=1<<level;break;}}'
 assert old in s
 return s.replace(old,new)

def cache(s):
 # Test an owner once per ray/section, retain the nearest future hit until DDA reaches it.
 s=s.replace('for(uint iteration=0;iteration<view.settings.w&&t<=end;++iteration){',
 '''uvec4 tested=uvec4(0xffffffffu);uint cursor=0u;ivec3 priorSection=ivec3(2147483647);
    for(uint iteration=0;iteration<view.settings.w&&t<=end;++iteration){''')
 s=s.replace('uint slot=w[w[8]+uint(grid.x+dims.x*(grid.z+dims.z*grid.y))];',
 '''uint slot=w[w[8]+uint(grid.x+dims.x*(grid.z+dims.z*grid.y))];
        if(any(notEqual(section,priorSection))){tested=uvec4(0xffffffffu);cursor=0u;priorSection=section;}''')
 s=s.replace('float boundary=min(next.x,min(next.y,next.z));bool found=false;',
 '''float boundary=min(next.x,min(next.y,next.z));bool found=false;
                vec3 sectionNext=(vec3(section*16)+step(vec3(0),ray)*16-origin)*inverseRay;
                float sectionEnd=min(end,min(sectionNext.x,min(sectionNext.y,sectionNext.z)));''')
 old='''uint shape=shapeAt(owner);
                    if(shape!=0xffffffffu)found=shapeHit(origin,ray,inverseRay,owner,shape,t,min(boundary,end),occlusion,hit)||found;
                    if(occlusion&&found)return true;'''
 new='''ivec3 ownerSection=ivec3(floor(vec3(owner)/16)),ownerGrid=ownerSection-base;
                    if(any(lessThan(ownerGrid,ivec3(0)))||any(greaterThanEqual(ownerGrid,dims)))continue;
                    uint ownerSlot=w[w[8]+uint(ownerGrid.x+dims.x*(ownerGrid.z+dims.z*ownerGrid.y))];
                    if(ownerSlot==0xffffffffu)continue;
                    ivec3 own=owner-ownerSection*16;uint ownerId=ownerSlot*4096u+uint(own.x+16*own.z+256*own.y);
                    if(any(equal(tested,uvec4(ownerId))))continue;
                    tested[cursor&3u]=ownerId;++cursor;
                    uint shape=w[w[7]+ownerId];
                    found=shapeHit(origin,ray,inverseRay,owner,shape,t,sectionEnd,occlusion,hit)||found;
                    if(occlusion&&found)return true;'''
 assert old in s
 s=s.replace(old,new).replace('if(found)return true;','if(hit.shape!=0xffffffffu&&hit.t<=boundary+.0002)return true;')
 s=s.replace('float advance=min(next.x,min(next.y,next.z));','float advance=min(next.x,min(next.y,next.z));\n        if(hit.shape!=0xffffffffu&&hit.t<=min(advance,end)+.0002)return true;')
 return s

variants={'baseline':base,'grid':grid(base),'shadow':shadow(base),'occupancy':occupied(base),'combined':occupied(shadow(grid(base))),'cache':cache(base),'cache_grid':grid(cache(base)),'primary_only':base.replace('trace(point+normal*.003,sun,160,true,shadow)?','false?')}
indexed=base.replace('uint t=a[3]+(first+j)*24;', 'uint triangle=a[14]==0u?first+j:a[a[14]+first+j];uint t=a[3]+triangle*24;')
indexed=indexed.replace('if(hit.shape!=0xffffffffu&&abs(distance-hit.t)<=.00005&&a[a[1]+shape*8+3]>=a[a[1]+hit.shape*8+3])continue;', '''if(hit.shape!=0xffffffffu&&abs(distance-hit.t)<=.00005){
                uint material=a[a[1]+shape*8+3],prior=a[a[1]+hit.shape*8+3];
                if(material>prior||(material==prior&&(shape!=hit.shape||any(notEqual(cell,hit.cell))||t>=hit.triangle)))continue;
            }''')
variants['indexed']=indexed
variants['triangle_probe']=base.replace('found?a[a[1]+hit.shape*8+3]:0xffffffffu','found?hit.triangle:0xffffffffu')
for name,s in variants.items():(out/(name+'.comp')).write_text(s)
manifest={'baseSourceSha256':hashlib.sha256(base.encode()).hexdigest(),'variants':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in variants.items()}}
(out/'variants.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest))
