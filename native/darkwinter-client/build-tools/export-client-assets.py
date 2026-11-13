"""Bake existing DarkWinter shape templates and owned textures for the standalone viewer.
Build tool only. Never opens or writes a Minecraft world; input is an immutable DWV snapshot.
"""
import argparse, copy, hashlib, json, math, struct, zlib
from pathlib import Path

def fnv(b):
    h=14695981039346656037
    for x in b: h=((h^x)*1099511628211)&0xffffffffffffffff
    return h

def png(path):
    b=path.read_bytes()
    if b[:8]!=b'\x89PNG\r\n\x1a\n': raise ValueError("Not PNG: "+str(path))
    pos=8; raw=bytearray(); palette=b''; alpha=b''; w=h=ct=depth=0
    while pos<len(b):
        n=struct.unpack_from('>I',b,pos)[0]; kind=b[pos+4:pos+8]; data=b[pos+8:pos+8+n]
        if len(data)!=n or zlib.crc32(kind+data)&0xffffffff!=struct.unpack_from('>I',b,pos+8+n)[0]: raise ValueError("PNG CRC")
        if kind==b'IHDR':
            w,h,depth,ct,comp,filt,inter=struct.unpack('>IIBBBBB',data)
            if not 0<w<=4096 or not 0<h<=4096 or depth!=8 or comp or filt or inter: raise ValueError("Only bounded noninterlaced 8-bit PNG")
        elif kind==b'PLTE': palette=data
        elif kind==b'tRNS': alpha=data
        elif kind==b'IDAT': raw.extend(data)
        elif kind==b'IEND': break
        pos+=n+12
    channels={0:1,2:3,3:1,4:2,6:4}.get(ct)
    if not channels: raise ValueError("Unsupported PNG colour type")
    stride=w*channels; d=zlib.decompressobj(); decoded=d.decompress(raw,(stride+1)*h+1)
    if len(decoded)!=(stride+1)*h or not d.eof: raise ValueError("PNG decompression limit")
    prior=bytearray(stride); rgba=bytearray()
    def paeth(a,b,c):
        p=a+b-c; aa=abs(p-a);bb=abs(p-b);cc=abs(p-c)
        return a if aa<=bb and aa<=cc else b if bb<=cc else c
    for y in range(h):
        f=decoded[y*(stride+1)]; row=bytearray(decoded[y*(stride+1)+1:(y+1)*(stride+1)])
        if f>4: raise ValueError("PNG filter")
        for i in range(stride):
            a=row[i-channels] if i>=channels else 0; q=prior[i]; c=prior[i-channels] if i>=channels else 0
            row[i]=(row[i]+(0 if f==0 else a if f==1 else q if f==2 else (a+q)//2 if f==3 else paeth(a,q,c)))&255
        for x in range(w):
            v=row[x*channels:(x+1)*channels]
            if ct==6: rgba.extend(v)
            elif ct==2: rgba.extend(v+b'\xff')
            elif ct==0: rgba.extend(bytes([v[0],v[0],v[0],255]))
            elif ct==4: rgba.extend(bytes([v[0],v[0],v[0],v[1]]))
            else:
                i=v[0]
                if i*3+3>len(palette): raise ValueError("PNG palette bounds")
                rgba.extend(palette[i*3:i*3+3]+bytes([alpha[i] if i<len(alpha) else 255]))
        prior=row
    return w,h,rgba

def read_world(path):
    b=path.read_bytes()
    if len(b)<40 or b[:8]!=b'DWVOXEL\0': raise ValueError("DWV magic")
    version,side,nm,ns,size,check=struct.unpack_from('<IIIIQQ',b,8)
    if version!=1 or side!=16 or size!=len(b)-40 or fnv(b[40:])!=check: raise ValueError("DWV header")
    at=40; materials=[]
    for i in range(nm):
        idx,flags,n=struct.unpack_from('<III',b,at);at+=12
        if idx!=i or n>8192: raise ValueError("DWV material bounds")
        materials.append((b[at:at+n].decode(),flags));at+=n
    cells={}
    for _ in range(ns):
        x,y,z,rev=struct.unpack_from('<iiiQ',b,at);at+=20
        values=struct.unpack_from('<4096I',b,at);at+=16384
        for i,v in enumerate(values):
            if v>=nm: raise ValueError("DWV palette index")
            if not materials[v][1]&1: cells[(x*16+(i&15),y*16+(i>>8),z*16+((i>>4)&15))]=v
    if at!=len(b): raise ValueError("DWV trailing bytes")
    return b,materials,cells

def state(name):
    head,_,tail=name.partition('[')
    props=dict(x.split('=',1) for x in tail.rstrip(']').split(',')) if tail else {}
    return head,props

def cable(axis,distance,index,lo=0.,hi=1.,end=False):
    result=[]; sag=min(.42,.025*distance);r=.0075
    for cross in [.15,.5,.85]:
        for seg in range(3):
            a=lo+(hi-lo)*seg/3;b=lo+(hi-lo)*(seg+1)/3
            ta=(index+a-.5)/distance;tb=(index+b-.5)/distance
            if end: ta=1+(a-.5)/distance;tb=1+(b-.5)/distance
            ya=.9125-4*sag*ta*(1-ta);yb=.9125-4*sag*tb*(1-tb)
            def point(t,y,k):
                c=cross+(-r if k in (0,3) else r);y+=-r if k<2 else r
                return [t*16,y*16,c*16] if axis=='x' else [c*16,y*16,t*16]
            start=[point(a,ya,k) for k in range(4)];finish=[point(b,yb,k) for k in range(4)]
            for k in range(4):
                j=(k+1)%4
                result.append(dict(texture='road_ref_graphite',p=[start[k],finish[k],finish[j],start[j]],uv=[[.48,8.48],[7.2,8.48],[7.2,15.2],[.48,15.2]],tint=0xff605b55))
    return result

def cube(texture):
    p=[[0,0,0],[16,0,0],[16,16,0],[0,16,0],[0,0,16],[16,0,16],[16,16,16],[0,16,16]]
    faces=[(0,3,2,1),(4,5,6,7),(0,4,7,3),(1,2,6,5),(0,1,5,4),(3,7,6,2)]
    return [dict(texture=texture,p=[p[i] for i in f],uv=[[0,16],[0,0],[16,0],[16,16]]) for f in faces]

def fword(x): return struct.unpack('<I',struct.pack('<f',x))[0]
def sword(x): return x&0xffffffff

def main():
    ap=argparse.ArgumentParser();ap.add_argument('root',type=Path);ap.add_argument('world',type=Path);ap.add_argument('out',type=Path)
    a=ap.parse_args();root=a.root.resolve();world,materials,cells=read_world(a.world)
    meshfile=root/'src/main/resources/assets/darkwinter/furniture/meshes.json'
    meshes=json.loads(meshfile.read_text(encoding='utf-8'));entries={e['id']:e for e in meshes['entries']}
    palette=dict(meshes['palette'])
    palette['_core_snow']='darkwinter:block/outdoor/d_snow'
    palette['_core_stone']='darkwinter:block/outdoor/d_concrete'
    sources={str(meshfile.relative_to(root)):hashlib.sha256(meshfile.read_bytes()).hexdigest()}
    textures=[];texids={};texture_refs={}
    def texture(name):
        if name in texids:return texids[name]
        ref=palette[name]
        if ref in texture_refs:
            texids[name]=texture_refs[ref];return texids[name]
        if not ref.startswith('darkwinter:'):raise ValueError("Standalone pack only accepts owned DarkWinter textures: "+ref)
        path=root/'src/main/resources/assets/darkwinter/textures'/(ref.split(':')[1]+'.png')
        w,h,rgba=png(path);texids[name]=len(textures);texture_refs[ref]=len(textures);textures.append((w,h,rgba))
        sources[str(path.relative_to(root))]=hashlib.sha256(path.read_bytes()).hexdigest()
        return texids[name]
    surfaces={'asphalt_block','asphalt_white_line','asphalt_dashed_line','asphalt_crosswalk','asphalt_edge_line','gravel_surface'}
    supported=surfaces|{'guardrail_section','utility_pole'}
    shapes=[];offsets=[]
    for name,flags in materials:
        ident,props=state(name);short=ident.split(':')[-1];turn={'north':0,'east':1,'south':2,'west':3}.get(props.get('facing','north'))
        if turn is None:raise ValueError("Unknown facing")
        off=[0,0,0]
        if flags&1 or ident=='minecraft:light':qs=[]
        elif ident=='minecraft:snow_block':qs=cube('_core_snow')
        elif ident=='minecraft:smooth_stone':qs=cube('_core_stone')
        elif ident=='darkwinter:utility_wire':
            axis=props.get('axis','z');distance=int(props.get('distance',2));index=int(props.get('index',1))
            if axis not in ('x','z') or not 2<=distance<=16 or not 1<=index<distance:raise ValueError("Wire state")
            qs=cable(axis,distance,index)
        elif ident.startswith('darkwinter:') and short in supported:
            part=int(props.get('part',0));e=entries[short]
            if not 0<=part<len(e['parts']):raise ValueError("Unknown furniture part")
            p=e['parts'][part];qs=copy.deepcopy(p['quads']);off=list(p['offset'])
            if props.get('snowy','false')=='true':
                qs+=copy.deepcopy(e.get('snow_parts',[])[part]['quads'])
            for q in qs:
                for v in q['p']:
                    for _ in range(turn):v[0],v[2]=16-v[2],v[0]
            for _ in range(turn):off[0],off[2]=-off[2],off[0]
        else:raise ValueError("Unsupported standalone material: "+name)
        shapes.append(qs);offsets.append(off)
    # Context-dependent cable halves belong to pole cells, exactly as in RoadFurnitureModels.
    overrides=[];variants={}
    for pos,mid in sorted(cells.items()):
        ident,props=state(materials[mid][0])
        if ident!='darkwinter:utility_pole' or int(props.get('part',0))!=2:continue
        axis='x' if props.get('facing','north') in ('east','west') else 'z';ds=[]
        for sign in (-1,1):
            q=list(pos);q[0 if axis=='x' else 2]+=sign
            peer=state(materials[cells[tuple(q)]][0]) if tuple(q) in cells else ('',{})
            d=int(peer[1].get('distance',0));idx=int(peer[1].get('index',0))
            ds.append(d if peer[0]=='darkwinter:utility_wire' and peer[1].get('axis')==axis and idx==(d-1 if sign<0 else 1) else 0)
        key=(mid,*ds)
        if not any(ds):continue
        if key not in variants:
            qs=copy.deepcopy(shapes[mid])
            if ds[0]:qs+=cable(axis,ds[0],0,0,.5,True)
            if ds[1]:qs+=cable(axis,ds[1],0,.5,1,False)
            variants[key]=len(shapes);shapes.append(qs);offsets.append(offsets[mid])
        overrides.append((*pos,mid,variants[key]))
    nodes=[];triangles=[];descs=[];shape_stats=[]
    for sid,qs in enumerate(shapes):
        tris=[]
        for q in qs:
            p=q['p'];uv=q['uv'];tname=q['texture'];tid=texture(tname)
            style=1 if tname in ('road_ref_asphalt','road_ref_gravel') else 2 if tname.startswith('road_ref_') else 0
            tint=q.get('tint',0xff50b5db if tname=='road_ref_yellow' else 0xffe6f1f1 if tname=='road_ref_white' else 0xffffffff)
            for indices in ((0,1,2),(0,2,3)):
                v=[[p[k][j]/16 for j in range(3)] for k in indices]
                if any(not math.isfinite(x) or not -1e-5<=x<=1.00001 for pt in v for x in pt):raise ValueError("Shape escapes its cell")
                e1=[v[1][j]-v[0][j] for j in range(3)];e2=[v[2][j]-v[0][j] for j in range(3)]
                normal=[e1[1]*e2[2]-e1[2]*e2[1],e1[2]*e2[0]-e1[0]*e2[2],e1[0]*e2[1]-e1[1]*e2[0]]
                length=math.sqrt(sum(x*x for x in normal))
                if length<1e-10:continue
                normal=[x/length for x in normal]
                u=[[uv[k][j]/16 for j in range(2)] for k in indices]
                words=[*map(fword,v[0]),tid,*map(fword,e1),style| (8 if q.get('cutout',False) else 0),*map(fword,e2),tint,
                       *map(fword,u[0]),*map(fword,[u[1][j]-u[0][j] for j in range(2)]),
                       *map(fword,[u[2][j]-u[0][j] for j in range(2)]),0,0,*map(fword,normal),0]
                tris.append((v,words))
        begin=len(nodes)
        def bvh(items,depth=0):
            if depth>32:raise ValueError("BVH depth")
            idx=len(nodes);nodes.append(None)
            lo=[min(t[0][k][j] for t in items for k in range(3))-1e-6 for j in range(3)]
            hi=[max(t[0][k][j] for t in items for k in range(3))+1e-6 for j in range(3)]
            first=count=0
            if len(items)<=8:
                first=len(triangles);count=len(items);triangles.extend(t[1] for t in items)
            else:
                axis=max(range(3),key=lambda j:hi[j]-lo[j]);items.sort(key=lambda t:sum(v[axis] for v in t[0]))
                half=len(items)//2;bvh(items[:half],depth+1);bvh(items[half:],depth+1)
            nodes[idx]=[*map(fword,lo),first,*map(fword,hi),count,len(nodes),0,0,0]
        if tris:bvh(tris)
        original=sid if sid<len(materials) else next(k[0] for k,v in variants.items() if v==sid)
        off=offsets[sid]
        descs.append([begin if tris else 0xffffffff,len(nodes),len(tris),original,sword(off[0]),sword(off[2]),0,0])
        shape_stats.append({'shape':sid,'material':materials[original][0],'triangles':len(tris),'nodes':len(nodes)-begin})
    words=[0]*16
    def append(items):at=len(words);words.extend(items);return at
    words[0]=len(descs);words[1]=append([x for row in descs for x in row])
    words[2]=append([x for row in nodes for x in row]);words[3]=append([x for row in triangles for x in row])
    texdesc=[];pixels=[]
    for w,h,rgba in textures:
        texdesc.extend([len(pixels),w,h,0]);pixels.extend(struct.unpack('<'+'I'*(w*h),rgba))
    words[4]=append(texdesc);words[5]=append(pixels);words[6]=len(nodes);words[7]=len(triangles);words[8]=len(textures)
    words[9]=append([sword(x) for row in overrides for x in row]);words[10]=len(overrides);words[11]=len(materials)
    data=struct.pack('<'+'I'*len(words),*words)
    out=a.out;out.parent.mkdir(parents=True,exist_ok=True)
    out.write_bytes(struct.pack('<8sIIQQ',b'DWASSET\0',1,len(words),fnv(world),fnv(data))+data)
    receipt={'schema':1,'worldSha256':hashlib.sha256(world).hexdigest(),'assetSha256':hashlib.sha256(out.read_bytes()).hexdigest(),
             'shapeCount':len(descs),'triangles':len(triangles),'bvhNodes':len(nodes),'textures':len(textures),'contextOverrides':len(overrides),
             'sourceSha256':sources,'shapes':shape_stats,'scope':'Owned DarkWinter templates and textures; vanilla snow/stone mapped to owned snow/concrete; no Mojang assets.'}
    out.with_suffix('.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in receipt.items() if k not in ('sourceSha256','shapes')}))

if __name__=='__main__':main()
