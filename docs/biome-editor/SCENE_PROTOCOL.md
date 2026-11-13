# Retained native scene protocol v1

The default editor renders retained native terrain geometry in C++/Vulkan. Camera, brush cursor and viewport redraw never require a Minecraft image or camera request. Java owns exact world generation and native block-model compilation; C++ owns interactive drawing. The old RGBA-frame diagnostic path may remain optional.

## Publication and identity

The worker atomically publishes scene.json only after all immutable assets are complete. All filenames are flat relative names inside the owned IPC session; no links. Every asset has mandatory byte count and lowercase SHA-256. Files are never overwritten while a different generation may be reading them: use scene/resource-qualified names.

Manifest:
{
 "schema":1,"kind":"native-terrain-mesh","sceneRevision":1,"revision":1,"resourceEpoch":1,
 "origin":[0,0,0],"bounds":[0,-64,0,80,128,80],"cutY":320,
 "vertexCount":123,"indexCount":180,
 "vertices":{"file":"scene-1-vertices.bin","bytes":3936,"sha256":"..."},
 "indices":{"file":"scene-1-indices.bin","bytes":720,"sha256":"..."},
 "atlas":{"file":"atlas-1.rgba","bytes":33554432,"sha256":"...","width":4096,"height":2048,"format":"RGBA8"},
 "lightmap":{"file":"scene-1-lightmap.rgba","bytes":1024,"sha256":"...","width":16,"height":16,"format":"RGBA8"},
 "environment":{"time":6000,"weather":"clear","effects":false},
 "parts":[{"firstIndex":0,"indexCount":180,"layer":0,"bounds":[0,48,0,16,80,16]}],
 "omittedBlockEntities":0
}

revision is a publication request revision; sceneRevision identifies the generation and is the geometry cache/cancellation key. Camera/environment requests must not invalidate or re-read a mesh. resourceEpoch changes when actual Minecraft resources change. origin is an integer world origin (normally minimum profile X/Z, Y=0); all vertices and part bounds are relative to it. Rendering subtracts origin from the double-precision camera before float matrices.

## Exact binary ABI

All values are little endian. Vertex stride is 32 bytes, matching the actual native BLOCK prefix:
- offset 0: float32 x; 4: y; 8: z, relative to origin.
- offset 12: four bytes R,G,B,A. Native baked tint, AO and face shading are retained.
- offset 16: float32 u; offset 20: float32 v, normalized native block-atlas UV.
- offset 24: uint32 packed light: low uint16 block, high uint16 sky, native units 0..240. Lightmap texel coordinates are the components shifted right by 4.
- offset 28: uint32 reserved, zero. There are no native BLOCK normals; do not invent a normal convention.

Indices are uint32 little endian into the global vertex buffer. Each part is a triangle-list range, indexCount divisible by 3, firstIndex in index units. Native explicit SHORT/INT indices must be decoded and rebased; sequential quad topology is allowed only when MeshData has no explicit index buffer. Layer 0=SOLID, 1=CUTOUT, 2=TRANSLUCENT. Initial rendering is double-sided; native compilation has already removed hidden faces. Transparent part centers may be sorted locally without rebuilding geometry.

The atlas is the actual stitched block atlas in RGBA order. No UV rescaling or texture downsampling. Unused atlas pixels may be zero when reconstructing from native sprite preparations. Initial animated sprite frames must follow metadata. Lightmap is actual native 16x16 RGBA; it does not include the full postprocessing graph.

## Bounds and lifetime

Manifest <=1 MiB, parts <=16384, vertices+indices <=512 MiB, atlas <=256 MiB and dimensions <=8192, total asset bytes <=768 MiB. Also check the actual Vulkan device limit before allocation. Validate byte/count arithmetic, finite XYZ/UV and bounds, indices and supported layer values before publication to the renderer. Reject oversized scenes with a precise message rather than lowering detail silently.

The reader decodes and hashes once per scene on its existing single bounded I/O executor. It delivers shared_ptr<const SceneBundle> without a GUI payload copy. GPU meshes/atlas persist through local navigation. Old resources live until every using frame slot has completed; no per-navigation device wait.

## C++ interface shared by reader and viewport

SceneBundle.hpp defines global SceneVertex (ABI above), ScenePart (firstIndex/indexCount/layer, bounds), SceneBundle (sceneRevision/revision/resourceEpoch, origin, bounds, raw QByteArray vertex/index bytes, QVector<ScenePart>, atlas QByteArray/QSize, lightmap QByteArray/QSize, environment QJsonObject). Exact type spellings are maintained in that header by the reader owner; viewport includes it.

PreviewViewport: setScene(std::shared_ptr<const SceneBundle>), setEnvironment(QJsonObject), sceneAccepted(quint64), sceneDisplayed(quint64). Local camera always updates its matrix immediately. The previous mesh remains explicitly marked while a generation runs; brushes may accumulate edits using the displayed scene and explicit work plane.

Environment-only updates may atomically publish lighting.json with schema/sceneRevision/revision/resourceEpoch/environment and a lightmap asset of the same shape. They are small and never re-read the large mesh. Reader emits lightingReady(shared_ptr<const SceneLighting>); viewport accepts setLighting(shared_ptr<const SceneLighting>). SceneLighting carries these identities, lightmap bytes/size and environment.

Native full weather/postprocessing snapshots remain a separate optional reference view, not the default interactive camera. Terrain meshes do not include entity renderers or special block-entity renderers; report actual omissions.
