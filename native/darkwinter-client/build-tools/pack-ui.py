"""Reuse source GUI artwork and fonts verbatim; build-only PNG decoding."""
import sys,struct,json,shutil,hashlib
from pathlib import Path
from importlib.machinery import SourceFileLoader
m=SourceFileLoader("dw_ui_assets",str(Path(__file__).with_name("export-client-assets.py"))).load_module()
root,out=map(Path,sys.argv[1:3]);base=root/'src/client/resources/assets/darkwinter'
names=['northern_lake','pause_shelter','wordmark','instrument_icons','story_cabin','free_valley','loading_north','aurora_veil','mist_bank','wordmark_glow']
data=bytearray();sources={}
for name in names:
 p=base/'textures/gui/cinematic'/(name+'.png');w,h,rgba=m.png(p);data.extend(struct.pack('<II',w,h));data.extend(rgba);sources[str(p.relative_to(root))]=hashlib.sha256(p.read_bytes()).hexdigest()

# Conversion uses the mod's actual SVG parser, including curves, arcs and stroke widths.
menu_names=['play','connections','settings-menu','interface','exit-menu','info']
vector_out=root/'build/core/ui-vectors'
def append_vector(group,name):
 p=base/'svg'/group/(name+'.svg');w,h,rgba=m.png(vector_out/group/(name+'.png'))
 data.extend(struct.pack('<II',w,h));data.extend(rgba)
 sources[str(p.relative_to(root))]=hashlib.sha256(p.read_bytes()).hexdigest()
for glyph in menu_names:append_vector('menu',glyph)

p=base/'textures/gui/cinematic/optical_grain.png';w,h,pixels=m.png(p);data.extend(struct.pack('<II',w,h));data.extend(pixels);sources[str(p.relative_to(root))]=hashlib.sha256(p.read_bytes()).hexdigest()
extra_menu=['video','audio','controls','accessibility','language']
hud_names=['health','stamina','satiety','temperature','hydration','rest','wind-needle','settings','exit','signal','compass-face']
for glyph in extra_menu:append_vector('menu',glyph)
for glyph in hud_names:append_vector('hud',glyph)
world_menu=['story','free']
for glyph in world_menu:append_vector('menu',glyph)
pack_art=['unknown_pack','vanilla_pack']
for name in pack_art:
 p=vector_out/'packs'/(name+'.png');w,h,rgba=m.png(p);data.extend(struct.pack('<II',w,h));data.extend(rgba)
 sources['MinecraftBuild/packs/'+name+'.png']=hashlib.sha256(p.read_bytes()).hexdigest()
out.mkdir(parents=True,exist_ok=True);(out/'ui.dwu').write_bytes(struct.pack('<8sIIQ',b'DWUIART\0',1,len(names)+len(menu_names)+1+len(extra_menu)+len(hud_names)+len(world_menu)+len(pack_art),m.fnv(data))+data)
for name in ['ui_regular.ttf','ui_symbols.ttf','license.txt']:shutil.copyfile(base/'font'/name,out/name)
(out/'ui-sources.json').write_text(json.dumps({'sourceSha256':sources,'font':'Roboto Condensed, source DarkWinter, OFL 1.1','scope':'Original menu backgrounds, wordmark and instrument glyphs; native menu/pause/time HUD and material browser'},indent=2)+'\n')

# Package only UI metadata from the project's already resolved Minecraft assets.
import runpy
runpy.run_path(str(Path(__file__).with_name('export-ui-languages.py')))['export'](root,out)
runpy.run_path(str(Path(__file__).with_name('export-native-notices.py')))['export'](root,out)
