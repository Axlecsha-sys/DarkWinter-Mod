# DarkWinter standalone voxel core

C++20 stage-one world storage, bounded native executor, occupancy hierarchy and DWV snapshot format.

- include/dw_core.h — C ABI, opaque owners and explicit job lifecycle.
- src/jobs.hpp — bounded executor and dependencies.
- src/core.cpp / src/world.hpp — immutable section revisions and native preparation.
- src/format.cpp — bounded deterministic DWV reader and exclusive writer.
- src/cli.cpp — autonomous inspect/benchmark executable, no JVM.
- checks/cpp/checks.cpp — independent native correctness checks.
- ../../modules/darkwinter-core/adapter/java — optional Java 25 FFM adapter and closed-copy Anvil importer.

See [the current scope, checks and measurements](../../docs/development/CORE_STAGE1_RU.md).

Build and verify from the repository root:

~~~powershell
.\gradlew.bat coreStage1Verify
.\gradlew.bat coreVerifyClient
~~~

The normal mod build does not invoke a C++ compiler or bundle the native library.
This module does not yet replace the Minecraft renderer, simulation or integrated server.
