"""Build-only export of real Minecraft collision boxes and the mod's survivor mesh."""
import sys,json,struct,math,hashlib
from pathlib import Path
from importlib.machinery import SourceFileLoader
root,out=map(Path,sys.argv[1:3]);src=root/'build/core/player-source'
m=SourceFileLoader('dw_player_assets',str(Path(__file__).with_name('export-client-assets.py'))).load_module()
scene=json.loads((src/'scene.json').read_text());world=(out/'world.dwv').read_bytes()
assert scene['completed'] and scene['snapshotSha256']==hashlib.sha256((root/'build/core/biome-source/world.dwv').read_bytes()).hexdigest(),'Run coreExportPlayer after changing the imported biome snapshot'
at=40
for _ in range(struct.unpack_from('<I',world,16)[0]):at+=12+struct.unpack_from('<I',world,at+8)[0]
palette=m.fnv(world[40:at]);shapes=scene['collisionShapes'];overrides=scene['collisionOverrides']
target=[];cursor=40
for _ in scene['materials']:
 n=struct.unpack_from('<I',world,cursor+8)[0];target.append(world[cursor+12:cursor+12+n].decode());cursor+=12+n
remap={i:target.index(name) for i,name in enumerate(scene['materials'])}
defaults=[0]*len(target)
for old,shape in enumerate(scene['collisionDefaults']):defaults[remap[old]]=shape
for s in shapes:
 s['material']=remap[s['material']]
 assert isinstance(s['terrain'],bool)
for r in overrides:r[3]=remap[r[3]]
assert len(defaults)==len(scene['materials'])
body=bytearray(struct.pack('<'+'I'*len(defaults),*defaults))
for s in shapes:
 body+=struct.pack('<III',s['material'],len(s['boxes']),int(s['terrain']))
 for b in s['boxes']:
  assert len(b)==6 and all(math.isfinite(v) and -4<=v<=5 for v in b)
  assert all(b[i]<b[i+3] for i in range(3))
  body+=struct.pack('<6f',*b)
for r in overrides:body+=struct.pack('<iiiII',*r)
out.mkdir(parents=True,exist_ok=True)
(out/'collision.dwc').write_bytes(struct.pack('<8sIIIIQQQ',b'DWCOLL\0\0',2,len(defaults),len(shapes),len(overrides),palette,len(body),m.fnv(body))+body)
# Six exact top-level meshes, including their authored children and original UV islands.
dressed=(src/'avatar-dressed.json').is_file()
avatar=json.loads((src/('avatar-dressed.json' if dressed else 'avatar.json')).read_text());assert len(avatar) in (6,7)
texture=root/'src/client/resources/assets/darkwinter/textures/entity/northern_survivor.png'
textures={}
for bone in avatar:
 for q in bone['quads']:
  name=q.get('texture') if isinstance(q.get('texture'),str) else 'textures/entity/northern_survivor.png'
  if name not in textures:textures[name]=m.png(root/'src/client/resources/assets/darkwinter'/name)
tw=max(t[0] for t in textures.values());th=sum(t[1]+2 for t in textures.values());assert tw<=4096 and th<=4096
rgba=bytearray(tw*th*4);regions={};offset=1
for name,(width,height,pixels) in textures.items():
 regions[name]=(width,height,offset)
 for y in range(height):rgba[((offset+y)*tw)*4:((offset+y)*tw+width)*4]=pixels[y*width*4:(y+1)*width*4]
 offset+=height+2

nodes=[];tris=[];descs=[]
for bone in avatar:
 items=[]
 for q in bone['quads']:
  for ids in ((0,1,2),(0,2,3)):
   p=[q['p'][i] for i in ids]
   name=q.get('texture') if isinstance(q.get('texture'),str) else 'textures/entity/northern_survivor.png'
   width,height,offset=regions[name]
   uv=[(max(0,min(.999999,q['uv'][i][0]))*width/tw,(offset+max(0,min(.999999,q['uv'][i][1]))*height)/th) for i in ids]
   tint=int(q.get('tint',4294967295));tint=(tint&0xFF00FF00)|((tint>>16)&255)|((tint&255)<<16)
   e1=[p[1][i]-p[0][i] for i in range(3)];e2=[p[2][i]-p[0][i] for i in range(3)]
   n=[e1[1]*e2[2]-e1[2]*e2[1],e1[2]*e2[0]-e1[0]*e2[2],e1[0]*e2[1]-e1[1]*e2[0]];length=math.sqrt(sum(v*v for v in n))
   if length<1e-10:continue
   words=[*map(m.fword,p[0]),0,*map(m.fword,e1),8,*map(m.fword,e2),tint,*map(m.fword,uv[0]),*map(m.fword,[uv[1][i]-uv[0][i] for i in range(2)]),*map(m.fword,[uv[2][i]-uv[0][i] for i in range(2)]),0,0,*map(m.fword,[v/length for v in n]),0]
   assert len(words)==24;items.append((p,words))
 begin=len(nodes);start=len(tris)
 def bvh(group):
  idx=len(nodes);nodes.append(None)
  lo=[min(p[i] for t in group for p in t[0])-1e-6 for i in range(3)];hi=[max(p[i] for t in group for p in t[0])+1e-6 for i in range(3)]
  first=count=0
  if len(group)<=8:first=len(tris);count=len(group);tris.extend(t[1] for t in group)
  else:
   axis=max(range(3),key=lambda i:hi[i]-lo[i]);group.sort(key=lambda t:sum(p[axis] for p in t[0]));half=len(group)//2;bvh(group[:half]);bvh(group[half:])
  nodes[idx]=[*map(m.fword,lo),first,*map(m.fword,hi),count,len(nodes),0,0,0]
 assert items;bvh(items)
 descs.append([*map(m.fword,bone['pivot']),0,begin,len(nodes),len(tris)-start,start,0,0,0,0])
words=[0]*16
def add(data):at=len(words);words.extend(data);return at
words[0]=len(avatar);words[1]=add([v for row in descs for v in row]);words[2]=add([v for row in nodes for v in row]);words[3]=add([v for row in tris for v in row]);words[4]=add(struct.unpack('<'+'I'*(tw*th),rgba));words[5]=tw;words[6]=th;words[7]=len(tris);words[8]=len(nodes)
data=struct.pack('<'+'I'*len(words),*words)
(out/'avatar.dwp').write_bytes(struct.pack('<8sIIQQ',b'DWAVATR\0',1,len(words),0,m.fnv(data))+data)
receipt={'completed':True,'collisionShapes':len(shapes),'collisionBoxes':sum(len(s['boxes']) for s in shapes),'positionOverrides':len(overrides),'avatarBones':[b['name'] for b in avatar],'avatarTriangles':len(tris),'avatarTextureSha256':hashlib.sha256(texture.read_bytes()).hexdigest(),'avatarSource':'Existing fitted ThirdPersonBodyModel with authored winter garments and pack' if dressed else 'ThirdPersonBodyModel.createRoot(false)', 'avatarClothed':dressed,'avatarFirstPerson':len(avatar)==7,'textureSourceSha256':{name:hashlib.sha256((root/'src/client/resources/assets/darkwinter'/name).read_bytes()).hexdigest() for name in textures},'paletteHash':str(palette)}
(out/'player-assets.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt))
