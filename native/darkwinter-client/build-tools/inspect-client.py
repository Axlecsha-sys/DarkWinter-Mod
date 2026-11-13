"""Evidence for the standalone distribution: no-Java launch, PE imports, image parity, bad-input rejection."""
import argparse, hashlib, importlib.util, json, os, struct, subprocess, time
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('output',type=Path);args=p.parse_args()
    root=args.root.resolve();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    dist=root/'build/core/client';exe=dist/'DarkWinter.exe'
    spec=importlib.util.spec_from_file_location('assets_export',root/'native/darkwinter-client/build-tools/export-client-assets.py')
    exp=importlib.util.module_from_spec(spec);spec.loader.exec_module(exp)
    digest=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
    b=exe.read_bytes();pe=struct.unpack_from('<I',b,60)[0]
    if b[pe:pe+4]!=b'PE\0\0':raise ValueError('PE signature')
    sections=struct.unpack_from('<H',b,pe+6)[0];optional=pe+24
    if struct.unpack_from('<H',b,optional)[0]!=0x20b or struct.unpack_from('<H',b,optional+68)[0]!=2:raise ValueError('Expected x64 GUI executable')
    section_at=optional+struct.unpack_from('<H',b,pe+20)[0]
    def offset(rva):
        for i in range(sections):
            at=section_at+i*40;size,address,rawsize,raw=struct.unpack_from('<IIII',b,at+8)
            if address<=rva<address+max(size,rawsize):return raw+rva-address
        raise ValueError('PE RVA')
    imports=[];rva=struct.unpack_from('<I',b,optional+112+8)[0]
    if rva:
        at=offset(rva)
        while any(b[at:at+20]):
            name=offset(struct.unpack_from('<I',b,at+12)[0]);end=b.index(0,name)
            imports.append(b[name:end].decode());at+=20
    if any(any(x in n.lower() for x in ('jvm','java','jli','lwjgl','shaderc')) for n in imports):raise ValueError('Managed/build-tool runtime dependency')
    # Runtime deliberately has no JDK/JAVA_HOME/Python/Node search path.
    system=os.environ.get('SystemRoot',r'C:\Windows')
    env={k:os.environ[k] for k in ('SystemRoot','WINDIR','TEMP','TMP','USERPROFILE','LOCALAPPDATA','APPDATA') if k in os.environ}
    env['PATH']=str(Path(system)/'System32')
    world=dist/'world.dwv';before=digest(world)
    perf=out/'performance'
    subprocess.run([str(exe),'--hidden','--frames','600','--no-vsync','--report',str(perf)],env=env,cwd=dist,check=True,timeout=45)
    performance=json.loads((perf/'viewer.json').read_text())
    if not performance['completed'] or performance['jvm'] or any(n.lower() in ('jvm.dll','java.dll','jli.dll') for n in performance['loadedModules']):raise ValueError('No-Java launch check')
    latest=json.loads((root/'build/core/latest-window.json').read_text());current=Path(latest['run'])
    original=root/'build/core/window-runs/20261106-222622-957'
    image_parity={}
    for name in ('initial.png','moved.png','final.png'):
        if (original/name).is_file():
            image_parity[name]=digest(original/name)==digest(current/name)
            if not image_parity[name]:raise ValueError('Texture deduplication changed pixels: '+name)
    # PNG decoder also checks CRC, row filters, extent and decompression bounds.
    w,h,pixels=exp.png(perf/'final.png')
    if w!=1280 or h!=720 or len(set(bytes(pixels[i:i+4]) for i in range(0,len(pixels),64)))<100:raise ValueError('Rendered image lacks expected variation')
    original_assets=(dist/'assets.dwa').read_bytes();bad=out/'malformed';bad.mkdir()
    def word(buf,word,value):struct.pack_into('<I',buf,32+word*4,value)
    a=list(struct.unpack('<'+'I'*((len(original_assets)-32)//4),original_assets[32:]))
    cases=[
        ('magic',lambda b:b.__setitem__(0,0)),
        ('checksum',lambda b:b.__setitem__(-1,b[-1]^1)),
        ('world_identity',lambda b:b.__setitem__(16,b[16]^1)),
        ('shape_root',lambda b:word(b,a[1]+8,0x7fffffff)),
        ('node_escape',lambda b:word(b,a[2]+8,0)),
        ('triangle_texture',lambda b:word(b,a[3]+3,0x7fffffff)),
        ('triangle_nan',lambda b:word(b,a[3],0x7fc00000)),
        ('override_source',lambda b:word(b,a[9]+3,0x7fffffff)),
    ]
    rejected=[]
    for name,mutate in cases:
        data=bytearray(original_assets);mutate(data)
        if name not in ('checksum','magic','world_identity'):struct.pack_into('<Q',data,24,exp.fnv(data[32:]))
        target=bad/(name+'.dwa');target.write_bytes(data)
        report=bad/name
        result=subprocess.run([str(exe),'--hidden','--frames','1','--assets',str(target),'--report',str(report)],env=env,cwd=dist,timeout=15)
        if result.returncode!=1 or not (report/'error.json').is_file():raise ValueError('Malformed asset was accepted: '+name)
        rejected.append({'case':name,'error':json.loads((report/'error.json').read_text())['error']})
    if digest(world)!=before:raise ValueError('Viewer modified its snapshot')
    receipt=json.loads((dist/'build-receipt.json').read_text())
    for name,expected in receipt['artifactSha256'].items():
        if digest(dist/name)!=expected:raise ValueError('Distribution differs from build receipt: '+name)
    proof={'completed':True,'noJavaPath':True,'peSubsystem':'Windows GUI','peImports':imports,'performance':performance,
           'textureDedupPixelParity':image_parity,'rejectedMalformedAssets':rejected,'snapshotUnchanged':True,
           'artifactSha256':receipt['artifactSha256'],'scope':'Standalone viewer only. Mouse raw-input and interactive quality still need direct observation.'}
    (out/'package-proof.json').write_text(json.dumps(proof,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'completed':True,'report':str(out/'package-proof.json'),'gpuTraceMedianMs':performance['gpuTraceMedianMs'],
                      'gpuTraceP95Ms':performance['gpuTraceP95Ms'],'rejected':len(rejected),'pixelParity':image_parity}))
if __name__=='__main__':main()
