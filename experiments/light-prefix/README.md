# Detached block-light prefix experiment

This is an opt-in **prototype**, not an installed Minecraft lighting replacement.
The mod does not load this library, register a lighting hook, change ordinary
build dependencies, or include a DLL/SO/dylib in its JAR.

## Implemented scope

The C++17 scalar kernel processes a bounded FIFO prefix of **block-light
increase** entries from detached arrays. It preserves the selected Minecraft
26.3 queue-entry rules and direction order for cells whose occlusion shapes are
known to be simple and empty. Inputs are copied snapshots, never live Minecraft
objects. It does not process sky light, removal/decrease queues, arbitrary voxel
shapes, scheduling, section storage ownership, or GPU commands.

ABI version 1 uses 64-bit pointers and a 56-byte cells header, 16-byte queue/write
entries, and a 16-byte result. At most 48 cells per axis, 110592 cells, 4096 steps,
65536 queued entries, and 28672 ordered writes are accepted. Input/output spans
must be valid, accessible, aligned, and disjoint. Callers own buffer lifetime and
exclusive input access for the entire call.

A negative result changes no output bytes. A nonnegative result commits whole
queue entries only and returns the complete remaining FIFO. Boundary,
unsupported-shape, capacity, and step-limit results are explicit stopping
conditions. Output must not be applied to a live world without checking snapshot
freshness, cancellation, ownership, and the native publication barrier.

## Build explicitly

From the project root:

```text
gradlew lightPrefixBuildNative
```

On Windows the task uses only the already installed, verified portable Zig
0.14.1 at `build/voxel-faces/toolchains/zig-0.14.1/zig.exe`. It verifies the
installation marker and compiler hash and performs no downloads or tool
installation. If the compiler is absent, provision the project's existing
verified toolchain separately before running this task.

Other platforms require an installed `cmake` and C++17 compiler. The CMake path
is provided for portability; it has not been validated by the Windows tests.
No branch-name restriction is imposed by this experimental task.

Artifacts and `build-receipt.json` are written under `build/light-prefix/bin`.
Compiler caches follow the installed toolchain defaults.
The receipt records the ABI, source hashes, explicit command arguments,
toolchain identity, and produced library hash. A source hash does not prove that
an independently supplied library matches it: check the receipt and actual
artifact hash before any separate experiment. This task never overwrites the
private reference DLL under `build/dvcpp004-light-prefix`.

## Evidence and limits

The source copied here matches the private Windows x64 reference:

- `dw_light_prefix.h`: `181242bc94bc5d2f057e8b019c404e7335183839924ae451525fb130966d9c1a`
- `dw_light_prefix.cpp`: `bd050b3f47b8a3078882006e3a57a0d7f3497230c053aaafd3d522329814fa22`

The private Vulkan SDK fixture compared 33 cases against the actual Java 26.3
light engine and ran 20 physical FFM/native-negative-atomicity guards, with 9201
assertions passing and 3805 native queue entries processed. Those local fixtures and binary artifacts are not distributed
in the mod JAR. This is evidence for that exact prototype and tested platform,
not certification of a new build or general lighting correctness.

There is **no live-engine integration or FPS benefit established**. Full
snapshot/copy/FFM/prefix/tail/publication cost, retained memory, cancellation,
edits, unload/reload, and resource lifetime must be checked before promotion.
Native Vulkan world rendering remains independent of this CPU-only experiment.

The experiment follows the repository GNU GPLv3 license.
