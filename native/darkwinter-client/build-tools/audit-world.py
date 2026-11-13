"""Native package, source persistence, bad-input and no-Java performance checks."""
import os,sys,json,struct,subprocess,hashlib,shutil,zipfile
from pathlib import Path
root,out=map(Path,sys.argv[1:3]);dist=Path(sys.argv[3]) if len(sys.argv)>3 else root/'build/core/world-client';out.mkdir(parents=True,exist_ok=True)
env={k:v for k,v in os.environ.items() if k.upper() in ('SYSTEMROOT','WINDIR','TEMP','TMP','USERPROFILE','LOCALAPPDATA','APPDATA')}
env['PATH']=str(Path(os.environ['SystemRoot'])/'System32')
required=['DarkWinter.exe','world.dwv','assets.dwa','ui.dwu','trace.comp.spv','camera.txt','ui_regular.ttf','ui_symbols.ttf','ui-languages.tsv','ui-text.tsv','ui-game-rules.tsv','ui-language-sources.json','attribution.html','licenses.html','accessibility.html','notices-sources.json','notices/project-LICENSE.txt','notices/font-license.txt','license.txt','biome.json','ui-sources.json','build-receipt.json','README_RU.txt','bvh.json','collision.dwc','avatar.dwp','player-assets.json','footsteps.dws']
required += ['raster.vert.spv','raster.frag.spv','shadow.vert.spv','shadow.frag.spv']
required += ['visuals/'+name+'.spv' for name in ["ambient.frag","celestial.frag","celestial.vert","cloud_resolve.frag","composite.frag","film.frag","fullscreen.vert","sky_clouds.frag","sky_merge.frag","sky_merge_fused.frag","star.frag","star.vert","surface.frag","ui_composite.frag","volume.frag","weather_sky_background.frag","weather_sky_clouds.frag"]]
required += ['visuals/'+name for name in ['celestial.dwc','stars.dws','celestial-manifest.json','stars-manifest.json','port-manifest.json']]
required += [str(p.relative_to(dist)).replace('\\','/') for p in (dist/'licenses').rglob('*') if p.is_file()]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
before={n:sha(dist/n) for n in required}
run=out/'no-java-world';run.mkdir()
p=subprocess.run([str(dist/'DarkWinter.exe'),'--hidden','--play','--no-vsync','--frames','360','--report',str(run)],env=env,cwd=dist,timeout=120)
assert p.returncode==0,(p.returncode,run)
result=json.loads((run/'stage6.json').read_text());assert result['completed'] and not result['jvm']
verified=json.loads((Path(sys.argv[4]) if len(sys.argv)>4 else root/'build/core/latest-world.json').read_text())['result']
assert verified['completed'] and verified['fullscreen'] and verified['windowRestored'] and verified['pause'] and verified['reload'] and verified['generatedColumns']>81 and verified['residentSections']==1183 and verified['oracleRays']>300
assert result['gpuMedianMs']>0 and result['frames']==360
tests=[]
for name,target,offset in [('world-header','world.dwv',0),('asset-header','assets.dwa',0),('asset-integrity','assets.dwa',50),('ui-header','ui.dwu',0),('ui-integrity','ui.dwu',40),('collision-header','collision.dwc',0),('collision-integrity','collision.dwc',60),('avatar-header','avatar.dwp',0),('avatar-integrity','avatar.dwp',120),('sound-header','footsteps.dws',0),('sound-integrity','footsteps.dws',120)]:
 case=out/name;case.mkdir()
 for n in required:
  if n!=target:
   (case/n).parent.mkdir(parents=True,exist_ok=True);os.link(dist/n,case/n)
 b=bytearray((dist/target).read_bytes());b[offset]^=1;(case/target).write_bytes(b)
 report=case/'result'
 p=subprocess.run([str(case/'DarkWinter.exe'),'--hidden','--play','--frames','2','--report',str(report)],env=env,cwd=case,timeout=30)
 assert p.returncode==1,(name,p.returncode)
 error=json.loads((report/'error.json').read_text());assert not error['completed']
 tests.append({'case':name,'rejected':True,'error':error['error']})
assert before=={n:sha(dist/n) for n in required},'Package inputs modified'
sources=['gradle/core-game.gradle','docs/development/CORE_STAGE6_RU.md','.run/DarkWinter_Core_Check.run.xml','.run/DarkWinter_Core.run.xml','gradle/core-player.gradle','gradle/core-world.gradle','gradle/core-render.gradle','gradle/core.gradle','Start-DarkWinter-Core.cmd','docs/development/CORE_STAGE3_RU.md','docs/development/CORE_STAGE4_RU.md','docs/development/CORE_STAGE5_RU.md']
sources += [str(p.relative_to(root)).replace(os.sep,'/') for directory in ('native/darkwinter-client','WKE/core','WKE/runtime') for p in (root/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
# Obsolete local include from stage 1; no Gradle/CMake target consumes it.
compat='WKE/core/tests/checks.cpp'
if compat in sources:
 assert (root/compat).read_text().strip().endswith('#include "../checks/cpp/checks.cpp"')
 sources.remove(compat)
ignored=subprocess.run(['git','check-ignore','--',*sources],cwd=root,capture_output=True,text=True)
assert ignored.returncode==1 and not ignored.stdout,ignored.stdout
diff=subprocess.run(['git','diff','--check','--',*sources],cwd=root,capture_output=True,text=True)
assert diff.returncode==0,diff.stdout+diff.stderr
archive=root/'build/core/package'/(sys.argv[5] if len(sys.argv)>5 else 'DarkWinter-World-Windows-x64.zip');archive.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for n in required:z.write(dist/n,n)
with zipfile.ZipFile(archive) as z:
 assert z.testzip() is None and sorted(z.namelist())==sorted(required)
 for n in required:assert hashlib.sha256(z.read(n)).hexdigest()==before[n]
proof={'completed':True,'noJavaEnvironment':True,'performance':result,'badInputs':tests,'packageInputsUnchanged':True,'sourcePaths':sources,'sourceSha256':{n:sha(root/n) for n in sources},'gitDiffCheck':True,'ignoredSources':[],'archive':str(archive),'archiveSha256':sha(archive),'artifactSha256':before,'limitations':['GPU timestamps cover native raster and visual passes, excluding presentation/capture; scene-specific, not a Minecraft FPS comparison','Validation-layer availability is reported by the runtime; overlays may remain','Eleven invalid inputs, not a general security fuzzing result']}
(out/'audit.json').write_text(json.dumps(proof,indent=2)+'\n')
print(json.dumps({k:v for k,v in proof.items() if k not in ('sourcePaths','sourceSha256','artifactSha256','performance')}))
