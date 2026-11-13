"""Balanced A/B/B/A renderer measurements using one native executable and frozen inputs."""
import os,sys,json,subprocess,statistics,hashlib
from pathlib import Path
from importlib.machinery import SourceFileLoader
root,out=map(Path,sys.argv[1:3]);study=root/'build/core/render-study';out.mkdir(parents=True,exist_ok=True)
env={k:v for k,v in os.environ.items() if k.upper() in ('SYSTEMROOT','WINDIR','TEMP','TMP','USERPROFILE','LOCALAPPDATA','APPDATA')}
env['PATH']=str(Path(os.environ['SystemRoot'])/'System32')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
m=SourceFileLoader('dw_png',str(Path(__file__).with_name('export-client-assets.py'))).load_module()
rows=[];summary=[]
for camera in ('ground','overview','negative'):
 captures={};results={'baseline':[],'optimized':[]}
 for index,name in enumerate(('baseline','optimized','optimized','baseline')):
  dist=study/'baseline' if name=='baseline' else root/'build/core/world-client'
  shader=study/'baseline.spv' if name=='baseline' else dist/'trace.comp.spv'
  report=out/(camera+'-'+str(index)+'-'+name)
  p=subprocess.run([str(study/'render-bench.exe'),str(dist),str(shader),str(report),camera,'240','0'],env=env,cwd=root,timeout=90,capture_output=True,text=True)
  assert p.returncode==0,(p.returncode,p.stdout,p.stderr)
  data=json.loads((report/'render.json').read_text());assert data['completed'] and data['frames']==240 and data['gpuSamples']==210
  data.update(variant=name,report=str(report));rows.append(data);results[name].append(data);captures.setdefault(name,report/'final.png')
  print(camera,name,data['gpuMedianMs'],flush=True)
 same=captures['baseline'].read_bytes()==captures['optimized'].read_bytes();changed=maximum=0
 if not same:
  w,h,a=m.png(captures['baseline']);w2,h2,b=m.png(captures['optimized']);assert (w,h)==(w2,h2)
  changed=sum(a[i:i+4]!=b[i:i+4] for i in range(0,len(a),4));maximum=max(abs(x-y) for x,y in zip(a,b))
 baseline=statistics.median(x['gpuMedianMs'] for x in results['baseline']);optimized=statistics.median(x['gpuMedianMs'] for x in results['optimized'])
 assert optimized<baseline,('Regression',camera,baseline,optimized)
 summary.append({'camera':camera,'baselineMedianMs':baseline,'optimizedMedianMs':optimized,'dispatchReductionPercent':100*(1-optimized/baseline),'baselineRunMedians':[x['gpuMedianMs'] for x in results['baseline']],'optimizedRunMedians':[x['gpuMedianMs'] for x in results['optimized']],'samePng':same,'changedPixels':changed,'maximumChannelDifference':maximum})
proof={'completed':True,'order':'A/B/B/A for each camera','resolution':[1280,720],'framesPerRun':240,'warmupFrames':30,'gpuSamplesPerRun':210,'sameExecutable':sha(study/'render-bench.exe'),'baselineWorld':sha(study/'baseline/world.dwv'),'optimizedWorld':sha(root/'build/core/world-client/world.dwv'),'baselineAssets':sha(study/'baseline/assets.dwa'),'optimizedAssets':sha(root/'build/core/world-client/assets.dwa'),'summary':summary,'runs':rows,'limitations':['GPU dispatch only; not whole-game FPS','Two runs per variant/camera; one machine/driver','Validation layers unavailable; driver overlays remain']}
assert proof['baselineWorld']==proof['optimizedWorld']
(out/'paired.json').write_text(json.dumps(proof,indent=2)+'\n');print(json.dumps(summary))
