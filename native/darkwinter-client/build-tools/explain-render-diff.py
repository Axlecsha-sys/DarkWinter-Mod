"""Classify changed RGB pixels against full primary-hit triangle payloads."""
import sys,json,struct,array
from pathlib import Path
from importlib.machinery import SourceFileLoader
m=SourceFileLoader('dw_png',str(Path(__file__).with_name('export-client-assets.py'))).load_module()
root,run=map(Path,sys.argv[1:3]);paths=[root/'build/core/world-client',root/'build/core/render-study/sah']
data=[(p/'assets.dwa').read_bytes()[32:] for p in paths]
result=[]
for camera in ('ground','overview'):
 folders=[run/(camera+'-'+n) for n in ('baseline','sah')];pictures=[m.png(p/'final.png')[2] for p in folders]
 hits=[]
 for p in folders:
  a=array.array('I');a.frombytes((p/'final.png.hits').read_bytes());hits.append(a)
 counts={'changedRgb':0,'samePrimaryTriangle':0,'differentPrimaryTriangle':0,'samePrimaryDistanceBits':0,'maxDepthDelta':0.}
 for i in range(len(pictures[0])//4):
  if pictures[0][i*4:i*4+4]==pictures[1][i*4:i*4+4]:continue
  counts['changedRgb']+=1
  ids=[h[i*2] for h in hits];payload=[data[k][at*4:at*4+96] if at!=0xffffffff else None for k,at in enumerate(ids)]
  counts['samePrimaryTriangle' if payload[0]==payload[1] else 'differentPrimaryTriangle']+=1
  if hits[0][i*2+1]==hits[1][i*2+1]:counts['samePrimaryDistanceBits']+=1
  ds=[struct.unpack('<f',struct.pack('<I',h[i*2+1]))[0] for h in hits]
  if all(d<float('inf') for d in ds):counts['maxDepthDelta']=max(counts['maxDepthDelta'],abs(ds[0]-ds[1]))
 result.append({'camera':camera,**counts})
(run/'difference-explained.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
