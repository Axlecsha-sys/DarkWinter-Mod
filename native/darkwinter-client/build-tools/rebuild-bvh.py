"""Binned surface-area BVH construction over unchanged shared shape triangles."""
import array,sys,struct,json,math,hashlib,os
from pathlib import Path
stable=len(sys.argv)>3 and sys.argv[3]=='refs';padding=float(sys.argv[4]) if len(sys.argv)>4 else 1e-6
requested,out=map(Path,sys.argv[1:3]);source=requested.parent if requested.suffix=='.dwa' else requested/'build/core/world-client';out.mkdir(parents=True,exist_ok=True)
b=(source/'assets.dwa').read_bytes();magic,version,count,binding,check=struct.unpack_from('<8sIIQQ',b)
assert magic==b'DWASSET\0' and version==2 and len(b)==32+count*4 and sys.byteorder=='little'
words=array.array('I');words.frombytes(b[32:])
fw=lambda x:struct.unpack('<I',struct.pack('<f',x))[0]
fp=lambda x:struct.unpack('<f',struct.pack('<I',x))[0]
def fnv(data):
 h=14695981039346656037
 for x in data:h=((h^x)*1099511628211)&0xffffffffffffffff
 return h
assert fnv(b[32:])==check
nodes=array.array('I');refs=array.array('I');triangles=array.array('I');descs=array.array('I');first=0
def union(a,b):return [min(a[0],b[0]),min(a[1],b[1]),min(a[2],b[2]),max(a[3],b[3]),max(a[4],b[4]),max(a[5],b[5])]
def area(b):
 if b is None:return 0.
 x,y,z=b[3]-b[0],b[4]-b[1],b[5]-b[2]
 return 2*(x*y+x*z+y*z)
depths=[]
for sid in range(words[0]):
 d=list(words[words[1]+sid*8:words[1]+sid*8+8]);n=d[2];items=[]
 for j in range(first,first+n):
  at=words[3]+j*24
  p=[fp(words[at+k]) for k in range(3)];e1=[fp(words[at+4+k]) for k in range(3)];e2=[fp(words[at+8+k]) for k in range(3)]
  v=[p,[p[k]+e1[k] for k in range(3)],[p[k]+e2[k] for k in range(3)]]
  bounds=[min(q[k] for q in v) for k in range(3)]+[max(q[k] for q in v) for k in range(3)]
  center=[sum(q[k] for q in v)/3 for k in range(3)];items.append((j,bounds,center))
 first+=n;begin=len(nodes)//12
 def build(group,depth):
  depths.append(depth);index=len(nodes)//12;nodes.extend([0]*12)
  box=group[0][1]
  for it in group[1:]:box=union(box,it[1])
  start=count=0
  if len(group)<=8:
   start=len(refs) if stable else len(triangles)//24;count=len(group)
   for it in sorted(group,key=lambda it:it[0]):
    if stable:refs.append(it[0])
    else:at=words[3]+it[0]*24;triangles.extend(words[at:at+24])
  else:
   choice=None;BINS=12
   if depth<30:
    for axis in range(3):
     lo=min(it[2][axis] for it in group);hi=max(it[2][axis] for it in group)
     if hi-lo<1e-10:continue
     scale=BINS/(hi-lo);counts=[0]*BINS;boxes=[None]*BINS
     for it in group:
      k=min(BINS-1,int((it[2][axis]-lo)*scale));counts[k]+=1;boxes[k]=it[1] if boxes[k] is None else union(boxes[k],it[1])
     right=[];rb=None;rn=0
     for k in range(BINS-1,-1,-1):
      rn+=counts[k]
      if boxes[k] is not None:rb=boxes[k] if rb is None else union(rb,boxes[k])
      right.append((rn,rb))
     right.reverse();lb=None;ln=0
     for k in range(BINS-1):
      ln+=counts[k]
      if boxes[k] is not None:lb=boxes[k] if lb is None else union(lb,boxes[k])
      rn,rb=right[k+1]
      if ln and rn:
       cost=ln*area(lb)+rn*area(rb)
       if choice is None or cost<choice[0]:choice=(cost,axis,lo,scale,k)
   left=right=None
   if choice:
    _,axis,lo,scale,k=choice;left=[];right=[]
    for it in group:(left if min(BINS-1,int((it[2][axis]-lo)*scale))<=k else right).append(it)
   if not left or not right:
    axis=max(range(3),key=lambda k:box[k+3]-box[k]);group.sort(key=lambda it:it[2][axis]);middle=len(group)//2;left=group[:middle];right=group[middle:]
   build(left,depth+1);build(right,depth+1)
  pad=1e-6 if depth==0 else padding
  a=index*12;nodes[a:a+12]=array.array('I',[*map(fw,[x-pad for x in box[:3]]),start,*map(fw,[x+pad for x in box[3:]]),count,len(nodes)//12,0,0,0])
 if items:build(items,0)
 d[0]=begin if items else 0xffffffff;d[1]=len(nodes)//12;descs.extend(d)
if stable:triangles=words[words[3]:words[3]+words[7]*24]
assert len(triangles)//24==words[7] and max(depths)<33
result=array.array('I',words[:16]);result[14]=result[15]=0
def append(data):at=len(result);result.extend(data);return at
result[1]=append(descs);result[2]=append(nodes);result[3]=append(triangles)
td=words[words[4]:words[4]+words[8]*4];pixel_count=max(td[i]+td[i+1]*td[i+2] for i in range(0,len(td),4))
result[4]=append(td);result[5]=append(words[words[5]:words[5]+pixel_count])
result[6]=len(nodes)//12;result[9]=append(words[words[9]:words[9]+words[10]*5])
if stable:result[14]=append(refs);result[15]=1
data=result.tobytes();(out/'assets.dwa').write_bytes(struct.pack('<8sIIQQ',magic,version,len(result),binding,fnv(data))+data)
if not (out/'world.dwv').exists():os.link(source/'world.dwv',out/'world.dwv')
receipt={'completed':True,'method':'12-bin surface area heuristic, max 8 triangles per leaf','triangles':words[7],'oldNodes':words[6],'nodes':result[6],'maximumDepth':max(depths),'indexedLeaves':stable,'nodePadding':padding,'oldBytes':len(b),'bytes':len(data)+32,'sourceSha256':hashlib.sha256(b).hexdigest(),'resultSha256':hashlib.sha256((out/'assets.dwa').read_bytes()).hexdigest()}
(out/'bvh.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
