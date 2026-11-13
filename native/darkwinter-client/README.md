# DarkWinter standalone client

Windows x64 C++20 / Win32 / Vulkan game using the autonomous DarkWinter core.
The runtime needs Windows system libraries and a Vulkan driver; it does not run
Minecraft, Fabric, JVM, LWJGL or a third-party game engine.

Current entry point: src/world_main.cpp. Build without gameplay tests: gradlew.bat coreBuildGame.
Launch: Start-DarkWinter-Core.cmd or build/core/game-client-v13/DarkWinter.exe.
See [controls and limits](README_WORLD_RU.txt) and
[comparison with the running mod](../../docs/development/CORE_NATIVE_COMPARISON_RU.md).
Older stage reports describe historical viewer/player milestones.

The world uses 16³ sections, bounded asynchronous streaming and shared source-model
geometry grouped by section and shape for Vulkan rasterization. It preserves owned
polygon templates and UVs; CPU shape queries remain an independent verification oracle. Versioned native terrain generation v4 does not reproduce
Minecraft generation. Climate readings and basic weather profiles are shared by HUD/rendering. The original sky/cloud/fog/film graph now runs through native graphics passes. The v13 forest uses grounded vegetation, deterministic natural leader crowns, exact snow-to-trunk joins, clean snow-bank side walls, authored bark and filtered texture minification. Existing v1/v2/v3 saves retain their generator version. Gameplay/visual checks follow the user's first manual run; compilation alone does not establish visual parity. Precipitation, survival needs and inventory remain pending.

The build exports the project's owned assets, compiles GLSL with existing
LWJGL/shaderc and C++ with verified Zig 0.14.1. Build tools are not distributed.
Pinned Vulkan headers and their licenses are included as documented by the audit.
