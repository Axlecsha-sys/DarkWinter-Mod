"""Compile captured, uncullled mod/Minecraft templates; no JVM in the shipped client."""
import sys,json,struct,math,hashlib,shutil
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
from importlib.machinery import SourceFileLoader
base=SourceFileLoader("dw_assets",str(Path(__file__).with_name("export-client-assets.py"))).load_module()
fnv,fword,sword=base.fnv,base.fword,base.sword
src,out=map(Path,sys.argv[1:3]);scene=json.loads((src/'scene.json').read_text())
world,materials,cells=base.read_world(src/'world.dwv')
assert scene['completed'] and scene['biome']=='darkwinter:darkwinter_test'
# Keep stage-3/4 numeric state IDs stable across nondeterministic capture order.
order=json.loads((Path(__file__).parents[1]/'export/palette-order.json').read_text())
original=materials;lookup={name:(i,flags) for i,(name,flags) in enumerate(materials)}
assert set(order)==set(lookup), 'Palette additions require an explicit format migration'
remap={lookup[name][0]:i for i,name in enumerate(order)}
materials=[(name,lookup[name][1]) for name in order]
start=40
for _ in original:start+=12+struct.unpack_from('<I',world,start+8)[0]
body=bytearray()
for i,(name,flags) in enumerate(materials):
 encoded=name.encode();body+=struct.pack('<III',i,flags,len(encoded))+encoded
for offset in range(start,len(world),20+4096*4):
 body+=world[offset:offset+20]
 body+=struct.pack('<4096I',*(remap[v] for v in struct.unpack_from('<4096I',world,offset+20)))
header=bytearray(world[:40]);struct.pack_into('<QQ',header,24,len(body),fnv(body));world=bytes(header+body)
for shape in scene['shapes']:shape['material']=remap[shape['material']]
for row in scene['overrides']:row[3]=remap[row[3]]

nm=len(materials);raw=scene['shapes'];shapes=[None]*nm;rawmap={}
for i,s in enumerate(raw):
 m=s['material']
 if shapes[m] is None:rawmap[i]=m;shapes[m]=s
 else:rawmap[i]=len(shapes);shapes.append(s)
for i in range(nm):
 if shapes[i] is None:shapes[i]={'material':i,'quads':[]}
# Native water volume uses the actual Minecraft state height and still sprite.
# Dynamics, animated frames, refraction and cross-cell flow surfaces are a later system.
for i,(name,flags) in enumerate(materials):
 if name.startswith('minecraft:water['):
  height=scene['fluidHeights'][name];tid=scene['waterTexture'];assert tid>=0
  shapes[i]['quads']=[{'p':[[v[0]/16,v[1]/16*height,v[2]/16] for v in q['p']],'uv':[[v[0]/16,v[1]/16] for v in q['uv']],'texture':tid,'tint':0xffd6573d} for q in base.cube('water')]
overrides=[(*r[:4],rawmap[r[4]]) for r in scene['overrides'] if rawmap[r[4]]!=r[3]]
# Authored native materials replace only texture pixels, retaining source UVs,
# palette IDs and collision geometry. The generated master remains unchanged.
project=Path(__file__).resolve().parents[3]
material_manifest=Path(__file__).parents[1]/'export/material-overrides.json'
specs=json.loads(material_manifest.read_text())['materials']
by_sprite={spec['sprite']:spec for spec in specs}
for spec in specs:
 if spec.get('append'):
  if any(tex.get('name')==spec['sprite'] for tex in scene['textures']):raise ValueError('Appended material already captured')
  scene['textures'].append(dict(id=len(scene['textures']),name=spec['sprite'],width=spec['width'],height=spec['height']))
texture_pixels={};authored_materials=[]
for tex in scene['textures']:
 spec=by_sprite.get(tex.get('name',''))
 w,h=tex['width'],tex['height']
 if spec:
  path=(project/spec['source']).resolve()
  if not path.is_relative_to(project):raise ValueError('Authored material outside project')
  digest=hashlib.sha256(path.read_bytes()).hexdigest()
  if digest!=spec['sourceSha256']:raise ValueError('Authored material hash mismatch: '+spec['source'])
  iw,ih,rgba=base.png(path)
  if (w,h)!=(spec['width'],spec['height']):raise ValueError('Authored material UV contract')
  # Technical asset export to the existing texel grid; no procedural artwork.
  data=bytearray(w*h*4)
  for y in range(h):
   sy=min(ih-1,(2*y+1)*ih//(2*h))
   for x in range(w):
    sx=min(iw-1,(2*x+1)*iw//(2*w));source=(sy*iw+sx)*4
    at=(y*w+x)*4;data[at:at+4]=rgba[source:source+4]
  if any(alpha!=255 for alpha in data[3::4]):raise ValueError('Bark must remain opaque')
  authored_materials.append(dict(sprite=tex['name'],source=spec['source'],sourceSha256=digest,masterSize=[iw,ih],exportSize=[w,h]))
 else:data=(src/tex['file']).read_bytes()
 if len(data)!=w*h*4:raise ValueError('Texture payload size')
 texture_pixels[tex['id']]=(w,h,data)
if set(by_sprite)!={item['sprite'] for item in authored_materials}:raise ValueError('Authored material sprite missing from captured palette')
cutout={tid for tid,(_,_,data) in texture_pixels.items() if any(alpha<128 for alpha in data[3::4])}
forest=SourceFileLoader("dw_forest_models",str(Path(__file__).with_name("forest-models.py"))).load_module()
endgrain=next(tex['id'] for tex in scene['textures'] if tex['name']=='darkwinter:block/native_spruce_endgrain')
# A snowy terrace is a continuous snow bank. The captured side sprites contain
# repeated rectangular soil teeth; they are opaque artwork, not missing geometry.
# Reuse the authored snow albedo on upper bank walls; deep dirt and bottoms remain.
snow_bank_texture=next(tex['id'] for tex in scene['textures'] if tex['name']=='darkwinter:block/test_ground_snow')
snow_side_textures={tex['id'] for tex in scene['textures'] if tex['name'] in (
 'darkwinter:block/terrain/grass_side_snow_z5','darkwinter:block/terrain/slab_side_snow_z5')}
snow_bank_faces=0
for shape in shapes:
 name=materials[shape['material']][0]
 snowy_ground=(name=='minecraft:grass_block[snowy=true]' or
  (name.startswith('darkwinter:test_snowy_grass_slab[') and 'snowy=true' in name and 'type=bottom' in name and 'waterlogged=true' not in name))
 if not snowy_ground:continue
 for quad in shape['quads']:
  if quad['texture'] not in snow_side_textures:continue
  a,b,c=quad['p'][:3];u=[b[i]-a[i] for i in range(3)];v=[c[i]-a[i] for i in range(3)]
  ny=u[2]*v[0]-u[0]*v[2]
  if abs(ny)>1e-8 or max(p[1] for p in quad['p'])-min(p[1] for p in quad['p'])<1e-6:continue
  quad['texture']=snow_bank_texture;quad['tint']=0xffffffff;snow_bank_faces+=1
# Native generation uses a height field, not captured per-position bevel keys.
# Canonical flat snow boxes make the nominal layer height real at every shared edge.
flat_snow_defaults=0
for mid,(name,_flags) in enumerate(materials):
 height=None
 if name=='minecraft:snow_block':height=1.
 elif name.startswith('minecraft:snow[') and 'layers=' in name:
  layers=int(name.split('layers=',1)[1].split(',',1)[0].split(']',1)[0])
  if 1<=layers<=8:height=layers/8.
 if height is None:continue
 quads=[]
 for face in base.cube('_native_snow'):
  quads.append(dict(p=[[v[0]/16,v[1]/16*height,v[2]/16] for v in face['p']],
   uv=[[v[0]/16,v[1]/16] for v in face['uv']],texture=snow_bank_texture,tint=0xffffffff))
 shapes[mid]=dict(material=mid,quads=quads);flat_snow_defaults+=1
forest_receipt=forest.prepare_forest_shapes(shapes,materials,scene['textures'],endgrain)
forest_receipt['flatSnowDefaults']=flat_snow_defaults
forest_receipt['snowBankFaces']=snow_bank_faces
nodes=[];triangles=[];descs=[];extents=[0.,0.]
for sid,s in enumerate(shapes):
 tris=[]
 for q in s['quads']:
  p=q['p'];uv=q['uv']
  for ix in ((0,1,2),(0,2,3)):
   v=[p[k] for k in ix]
   assert all(math.isfinite(x) and -4.00001<=x<=5.00001 for a in v for x in a),('Shape exceeds neighboring-cell support',sid,v)
   extents[0]=min(extents[0],*(x for a in v for x in a));extents[1]=max(extents[1],*(x for a in v for x in a))
   e1=[v[1][j]-v[0][j] for j in range(3)];e2=[v[2][j]-v[0][j] for j in range(3)]
   n=[e1[1]*e2[2]-e1[2]*e2[1],e1[2]*e2[0]-e1[0]*e2[2],e1[0]*e2[1]-e1[1]*e2[0]];length=math.sqrt(sum(x*x for x in n))
   if length<1e-10:continue
   n=[x/length for x in n];u=[uv[k] for k in ix]
   words=[*map(fword,v[0]),q['texture'],*map(fword,e1),8 if q['texture'] in cutout else 0,*map(fword,e2),int(q['tint']),*map(fword,u[0]),*map(fword,[u[1][j]-u[0][j] for j in range(2)]),*map(fword,[u[2][j]-u[0][j] for j in range(2)]),0,0,*map(fword,n),0]
   tris.append((v,words))
 begin=len(nodes)
 def bvh(items):
  idx=len(nodes);nodes.append(None)
  lo=[min(t[0][k][j] for t in items for k in range(3))-1e-6 for j in range(3)]
  hi=[max(t[0][k][j] for t in items for k in range(3))+1e-6 for j in range(3)]
  first=count=0
  if len(items)<=8:first=len(triangles);count=len(items);triangles.extend(t[1] for t in items)
  else:
   axis=max(range(3),key=lambda j:hi[j]-lo[j]);items.sort(key=lambda t:sum(v[axis] for v in t[0]));half=len(items)//2;bvh(items[:half]);bvh(items[half:])
  nodes[idx]=[*map(fword,lo),first,*map(fword,hi),count,len(nodes),0,0,0]
 if tris:bvh(tris)
 descs.append([begin if tris else 0xffffffff,len(nodes),len(tris),s['material'],s.get('nativeEndgrain',0),s.get('nativeGround',0),s.get('nativeLogContext',0),0])
words=[0]*16
def append(items):at=len(words);words.extend(items);return at
words[0]=len(descs);words[1]=append([x for r in descs for x in r]);words[2]=append([x for r in nodes for x in r]);words[3]=append([x for r in triangles for x in r])
td=[];pixels=[]
for tex in scene['textures']:
 w,h,data=texture_pixels[tex['id']]
 td.extend([len(pixels),w,h,0]);pixels.extend(struct.unpack('<'+'I'*(w*h),data))
radius=max(1,math.ceil(max(-extents[0],extents[1]-1)-1e-5));words[12]=radius;words[13]=((2*radius+1)**3+31)//32
words[4]=append(td);words[5]=append(pixels);words[6]=len(nodes);words[7]=len(triangles);words[8]=len(td)//4;words[9]=append([sword(x) for r in overrides for x in r]);words[10]=len(overrides);words[11]=nm
at=40
for _ in materials:n=struct.unpack_from('<I',world,at+8)[0];at+=12+n
palette_hash=fnv(world[40:at]);data=struct.pack('<'+'I'*len(words),*words)
out.mkdir(parents=True,exist_ok=True);(out/'world.dwv').write_bytes(world)
(out/'assets.dwa').write_bytes(struct.pack('<8sIIQQ',b'DWASSET\0',2,len(words),palette_hash,fnv(data))+data)
camera=[.5,81.,16.5,0.,-.07]
(out/'camera.txt').write_text(' '.join(map(str,camera))+'\n')
receipt={'authoredMaterials':authored_materials,'forestModels':forest_receipt,'completed':True,'biome':scene['biome'],'seed':scene['seed'],'bounds':scene['bounds'],'sections':struct.unpack_from('<I',world,20)[0],'materials':nm,'shapes':len(shapes),'triangles':len(triangles),'textures':len(td)//4,'overrides':len(overrides),'shapeExtent':extents,'assetsBytes':len(data)+32,'emptyMaterials':[materials[i][0] for i in range(nm) if not shapes[i]['quads']],'sourceSha256':hashlib.sha256((src/'scene.json').read_bytes()).hexdigest(),'scope':'Finite snapshot of actual biome generation and actual uncullled model geometry; context variants retained; no native infinite generator yet'}
(out/'biome.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
