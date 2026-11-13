# DarkWinter Biome Editor
C++20 / Qt 6 Widgets / Vulkan. Java 25 / Fabric Minecraft 26.3 is an isolated exact-generation worker. No Rust and no OpenGL renderer.

## Accepted workflow
Procedural terrain plus manual brushes; center preview, left tools, right properties/layers; adjustable preview starts at 5x5 chunks (80x80 blocks). Every rebuild uses actual Minecraft generation, not an approximate terrain mock. Real assets, day/night and optional combined weather/postprocessing/fog. Surface terrain, caves, tunnels and block editing. Drawn rivers plus drainage, rule/brush vegetation, one biome plus neighbor transitions. Manual save and Undo/Redo; no automatic branches. Export/connect does not regenerate saved chunks.

## IPC v1 (owned local worker directory only)
The C++ UI atomically replaces request.json. The worker atomically replaces state.json and frame.json. Revisions are monotonically increasing unsigned JSON-safe integers. Newer generate/camera requests supersede stale work. No process accepts arbitrary commands from a request.

Request:
{schema:1,revision:1,kind:"generate",profile:{...},camera:{...},environment:{...}}
kind can be generate, camera, environment, stop. Camera/environment requests reference sceneRevision. A generation result identifies both its sceneRevision and latest request revision.

State: {schema:1,revision:1,sceneRevision:1,status:"starting|generating|ready|failed|stopped",progress:0..1,message:"...",error:"..."}
Frame: {schema:1,revision:1,sceneRevision:1,width:1280,height:720,format:"RGBA8",file:"frame-N.rgba"}
Raw pixels are bounded at 4096x4096, row-major TOP-LEFT RGBA8; the file name is a single relative filename. UI never displays a stale scene as the new one. The preceding image may remain explicitly marked while exact generation runs.

## Profile v1
{schema:1,name:"Новый биом",id:"custom_biome",seed:"19021",biome:"darkwinter:darkwinter_test",chunkX:0,chunkZ:0,chunks:5,
terrain:{baseHeight:76,broadAmplitude:8,detailAmplitude:2,noiseScale:1.5},
operations:[],
vegetation:{trees:true,grass:true,bushes:true,decor:true,density:1},
environment:{time:6000,weather:"clear",effects:false},
camera:{targetX:40,targetY:76,targetZ:40,yaw:45,pitch:54.73561,span:112}}
All world coordinates are blocks. Seed is a decimal string without floating-point conversion. chunks is 3..15. Camera span is the vertical orthographic extent, yaw/pitch degrees. World bounds retain native minY/height.

Operations are ordered, individually enabled and undoable:
{kind:"raise|lower|flatten|smooth|mountain|plateau|river|lake|cave|tunnel|set_block|paint|vegetation",enabled:true,
x:40,y:76,z:40,radius:8,strength:1,height:80,width:5,depth:4,waterLevel:63,falloff:0.65,
points:[[x,z],...],block:"minecraft:stone"}
Unused fields may be omitted. Terrain operators use deterministic shared Java code for both worker and exported worldgen. C++ records/edit operations; it does not substitute its own approximate world generator.

## Source ownership during implementation
- UI/document/process client: EditorWindow.*, Document.*, WorkerClient.*, main.cpp, CMakeLists.txt.
- Vulkan viewport: PreviewViewport.*, shaders/*, viewport-specific tests.
- Exact worker/common worldgen: root coordinates Java sources and Gradle integration.
- Product docs live in ../docs/biome-editor. This contract is a development interface.
