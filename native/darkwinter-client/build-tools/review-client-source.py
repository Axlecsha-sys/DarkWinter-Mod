"""Read-only source and archive audit; never stages, commits, resets or changes Git."""
import argparse, hashlib, json, shutil, subprocess, zipfile
from pathlib import Path

def main():
    ap=argparse.ArgumentParser();ap.add_argument('root',type=Path);ap.add_argument('out',type=Path);args=ap.parse_args()
    root=args.root.resolve();out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    paths=[Path('gradle/core-client.gradle'),Path('gradle/core.gradle'),Path('Start-DarkWinter-Core.cmd')]
    paths+=sorted(p.relative_to(root) for p in (root/'native/darkwinter-client').rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    hashes={};trailing=[]
    for p in paths:
        data=(root/p).read_bytes();hashes[p.as_posix()]=hashlib.sha256(data).hexdigest()
        for i,line in enumerate(data.decode('utf-8').splitlines(),1):
            if line.rstrip(' \t')!=line:trailing.append(p.as_posix()+':'+str(i))
    if trailing:raise ValueError('Trailing source whitespace: '+', '.join(trailing[:10]))
    git=shutil.which('git')
    if not git:raise ValueError('Git unavailable for source review')
    def call(*a):
        return subprocess.run([git,*a],cwd=root,text=True,encoding='utf-8',errors='replace',capture_output=True,check=False,timeout=15)
    status=call('status','--porcelain=v1','-b')
    if status.returncode:raise ValueError(status.stderr)
    diff=call('diff','--check','--',*[p.as_posix() for p in paths])
    if diff.returncode:raise ValueError(diff.stdout+diff.stderr)
    ignored=call('check-ignore','--no-index','--',*[p.as_posix() for p in paths])
    if ignored.returncode not in (0,1) or ignored.stdout.strip():raise ValueError('Source excluded by ignore rules: '+ignored.stdout+ignored.stderr)
    dist=root/'build/core/client';receipt=json.loads((dist/'build-receipt.json').read_text())
    zip_path=root/'build/core/package/DarkWinter-Core-Windows-x64.zip'
    with zipfile.ZipFile(zip_path) as archive:
        if archive.testzip():raise ValueError('ZIP CRC failure')
        names=archive.namelist()
        if any(n.startswith(('/', '\\')) or '..' in Path(n).parts for n in names):raise ValueError('ZIP path')
        for name,expected in receipt['artifactSha256'].items():
            if hashlib.sha256(archive.read(name)).hexdigest()!=expected:raise ValueError('ZIP binary mismatch: '+name)
        if json.loads(archive.read('build-receipt.json'))!=receipt:raise ValueError('ZIP receipt mismatch')
        if any(n.lower().endswith(('.dll','.jar','.class')) for n in names):raise ValueError('Unexpected bundled runtime')
    latest=json.loads((root/'build/core/latest-package-check.json').read_text())
    previous=json.loads((Path(latest['run'])/'package-proof.json').read_text())
    if previous['artifactSha256']!=receipt['artifactSha256']:raise ValueError('Executable/assets differ from the tested distribution')
    result={'completed':True,'branch':status.stdout.splitlines()[0],'sourceSha256':hashes,
            'diffCheck':True,'noIgnoredSources':True,'zipEntries':names,
            'zipSha256':hashlib.sha256(zip_path.read_bytes()).hexdigest(),'verifiedArtifactsUnchanged':True,
            'scope':'Only the listed source paths were audited; unrelated existing worktree changes are preserved.'}
    (out/'source-audit.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'completed':True,'branch':result['branch'],'sources':len(paths),'zipEntries':len(names),'verifiedArtifactsUnchanged':True,'report':str(out/'source-audit.json')}))
if __name__=='__main__':main()
