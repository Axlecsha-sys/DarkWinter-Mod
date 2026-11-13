"""Read-only inventory for the native-world migration."""
from pathlib import Path,PurePosixPath
import gzip,hashlib,json,struct,subprocess,zipfile
root=Path(__file__).resolve().parents[3]

def export_minecraft_reference():
    """Copy only the fixed Java source allowlist; never extract arbitrary ZIP paths."""
    cache=root/'.gradle/loom-cache/minecraftMaven'
    out=root/'build/core/minecraft-reference'
    wanted={
        'WorldOptionsScreen.java':'net/minecraft/client/gui/screens/WorldOptionsScreen.java',
        'GameType.java':'net/minecraft/world/level/GameType.java',
        'Abilities.java':'net/minecraft/world/entity/player/Abilities.java',
        'LivingEntity.java':'net/minecraft/world/entity/LivingEntity.java',
        'LocalPlayer.java':'net/minecraft/client/player/LocalPlayer.java',
        'Player.java':'net/minecraft/world/entity/player/Player.java',
        'ServerPlayerGameMode.java':'net/minecraft/server/level/ServerPlayerGameMode.java',
    }
    limits={'jars':32,'jarBytes':256*1024*1024,'totalJarBytes':512*1024*1024,
            'entriesPerJar':65536,'sourceBytes':2*1024*1024}
    if not cache.resolve().is_relative_to(root) or not out.resolve().is_relative_to(root):
        raise RuntimeError('Minecraft reference paths must remain inside the project')
    out.mkdir(parents=True,exist_ok=True)
    def write_owned(name,data):
        target=out/name
        temporary=out/(name+'.tmp')
        if not target.resolve().is_relative_to(out.resolve()) or not temporary.resolve().is_relative_to(out.resolve()):
            raise RuntimeError('Minecraft reference output escapes its fixed directory')
        temporary.write_bytes(data)
        temporary.replace(target)
    manifest={'schema':1,'minecraftVersion':'26.3','completed':False,
              'sourceRoot':cache.relative_to(root).as_posix(),'limits':limits,'jars':[],'files':[]}
    try:
        jars=sorted(cache.glob('**/26.3/*-sources.jar'))
        if not jars or len(jars)>limits['jars']:
            raise RuntimeError('Expected 1..32 local Minecraft 26.3 source jars')
        selected={}
        total=0
        for jar in jars:
            if not jar.is_file() or not jar.resolve().is_relative_to(cache.resolve()):
                raise RuntimeError('Source jar escapes the fixed Loom cache')
            stat=jar.stat()
            total+=stat.st_size
            if stat.st_size>limits['jarBytes'] or total>limits['totalJarBytes']:
                raise RuntimeError('Minecraft source-jar size limit exceeded')
            jar_path=jar.relative_to(root).as_posix()
            with jar.open('rb') as handle:
                jar_sha=hashlib.file_digest(handle,'sha256').hexdigest()
                handle.seek(0)
                with zipfile.ZipFile(handle) as archive:
                    entries=archive.infolist()
                    if len(entries)>limits['entriesPerJar']:
                        raise RuntimeError('Minecraft source-jar entry limit exceeded')
                    manifest['jars'].append({'path':jar_path,'bytes':stat.st_size,'sha256':jar_sha})
                    for name,entry in wanted.items():
                        matches=[info for info in entries if info.filename==entry]
                        # Only this screen has had different package locations.
                        if not matches and name=='WorldOptionsScreen.java':
                            matches=[info for info in entries
                                     if info.filename.startswith('net/minecraft/')
                                     and PurePosixPath(info.filename).name==name
                                     and '..' not in PurePosixPath(info.filename).parts]
                        if not matches:
                            continue
                        if len(matches)!=1:
                            raise RuntimeError('Ambiguous Java entry: '+name+' in '+jar_path)
                        info=matches[0]
                        if info.is_dir() or not 0<info.file_size<=limits['sourceBytes']:
                            raise RuntimeError('Java source size limit exceeded: '+name)
                        with archive.open(info) as source:
                            data=source.read(limits['sourceBytes']+1)
                        if len(data)!=info.file_size:
                            raise RuntimeError('Java source size mismatch: '+name)
                        data.decode('utf-8')  # Validate text without changing its original bytes.
                        provenance={'jar':jar_path,'jarSha256':jar_sha,'entry':info.filename}
                        if name in selected:
                            if selected[name]['data']!=data:
                                raise RuntimeError('Conflicting Minecraft 26.3 sources: '+name)
                            selected[name]['sources'].append(provenance)
                        else:
                            selected[name]={'data':data,'sources':[provenance]}
            after=jar.stat()
            if (stat.st_size,stat.st_mtime_ns)!=(after.st_size,after.st_mtime_ns):
                raise RuntimeError('Source jar changed while being inspected: '+jar_path)
        missing=[name for name in wanted if name not in selected]
        if missing:
            manifest['missing']=missing
            raise RuntimeError('Missing Minecraft 26.3 sources: '+', '.join(missing))
        for name in wanted:
            source=selected[name]
            data=source['data']
            write_owned(name,data)
            manifest['files'].append({'path':(out/name).relative_to(root).as_posix(),
                                      'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),
                                      'sources':source['sources']})
        manifest['completed']=True
    except Exception as error:
        manifest['error']=str(error)
        raise
    finally:
        write_owned('manifest.json',(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n').encode('utf-8'))
    return {'manifest':(out/'manifest.json').relative_to(root).as_posix(),
            'completed':True,'files':len(manifest['files'])}

status=subprocess.run(['git','status','--porcelain=v1','-b'],capture_output=True,text=True,encoding='utf-8',check=True)
instructions={}
for base in [root,root/'native',root/'native/darkwinter-client',root/'WKE/core',root/'gradle']:
    p=base/'AGENTS.md'
    if p.is_file():instructions[str(p.relative_to(root))]=p.read_text(encoding='utf-8')
worlds=[]
for base in [root/'build/run',root/'build/biome-editor/runtime',root/'run']:
    pattern='*/saves/*/level.dat' if base.name=='run' and base.parent.name=='build' else 'saves/*/level.dat'
    for p in base.glob(pattern):
        try:
            raw=gzip.decompress(p.read_bytes())
            if b'darkwinter_test' not in raw:continue
            worlds.append({'path':str(p.parent),'mtime':p.stat().st_mtime,'gentle':b'test_gentle_broad_v2' in raw,'levelSha256':hashlib.sha256(p.read_bytes()).hexdigest()})
        except (OSError,ValueError):pass
worlds.sort(key=lambda x:x['mtime'],reverse=True)
art=[]
for prefix in ['src/client/resources/assets/darkwinter','src/main/resources/assets/darkwinter']:
    for p in (root/prefix/'textures/gui').rglob('*.png'):
        b=p.read_bytes()
        art.append({'path':p.relative_to(root).as_posix(),'size':list(struct.unpack_from('>II',b,16)),'bytes':len(b)})
reference=export_minecraft_reference()
result={'minecraftReference':reference,'branch':status.stdout.splitlines()[0],'dirtyEntries':len(status.stdout.splitlines())-1,'instructions':instructions,
        'testBiomeWorlds':worlds[:20],'guiArt':art,'workerSaves':[str(p) for p in (root/'build/biome-editor/runtime').glob('saves/*/level.dat')]}
out=root/'build/core/world-inputs.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
print(json.dumps({'minecraftReference':reference,'branch':result['branch'],'dirtyEntries':result['dirtyEntries'],'instructions':instructions,'worlds':worlds[:10],'art':art[:30]},ensure_ascii=False))
