"""Compare complete GPU captures, without hiding alpha-edge or shadow differences."""
import sys,json,hashlib
from pathlib import Path
from importlib.machinery import SourceFileLoader
m=SourceFileLoader('dw_png',str(Path(__file__).with_name('export-client-assets.py'))).load_module()
run=Path(sys.argv[1]);out=[]
for camera in ('ground','overview'):
 base=run/(camera+'-baseline');baseline=json.loads((base/'render.json').read_text());ref=base/'final.png'
 for folder in sorted(run.glob(camera+'-*')):
  if folder==base:continue
  result=json.loads((folder/'render.json').read_text());other=folder/'final.png';same=ref.read_bytes()==other.read_bytes();changed=maximum=0
  if not same:
   w,h,a=m.png(ref);w2,h2,b=m.png(other);assert (w,h)==(w2,h2)
   changed=sum(a[i:i+4]!=b[i:i+4] for i in range(0,len(a),4));maximum=max(abs(x-y) for x,y in zip(a,b))
  out.append({'camera':camera,'variant':folder.name[len(camera)+1:],'baselineMedianMs':baseline['gpuMedianMs'],'medianMs':result['gpuMedianMs'],'p95Ms':result['gpuP95Ms'],'samePng':same,'changedPixels':changed,'maximumChannelDifference':maximum,'baselinePngSha256':hashlib.sha256(ref.read_bytes()).hexdigest(),'pngSha256':hashlib.sha256(other.read_bytes()).hexdigest()})
(run/'comparison.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps(out))
