#!/usr/bin/env python3
"""Export the 26.3/DarkWinter star geometry to a fixed offline SSBO asset.

The random sequence, candidate rejection and size policy follow the complete
pinned Java sources. JOML's documented right-handed rotateTowards basis is
evaluated equivalently; this is not a claim of byte-identical JVM transcendental
results. No processes, Java, network, dependencies or source edits are used.
"""
from __future__ import annotations

import hashlib
import json
import math
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GENERATOR = "native/darkwinter-client/build-tools/export-visual-stars.py"
OUTPUT = "build/core/visual-shaders/celestial"
REFERENCE = OUTPUT + "/reference"
SOURCE_MANIFEST = OUTPUT + "/celestial-manifest.json"
MIXIN = "modules/darkwinter-visuals/src/client/java/com/dark/winter/visuals/mixin/SmallStarsMixin.java"
MAGIC = b"DWSTR1\0\0"
SEED = 10842
CANDIDATES = 1500
STRIDE = 16
TRIANGLE_ORDER = (0, 1, 2, 2, 3, 0)
PINNED = {
    REFERENCE + "/SkyRenderer.java": "f5d6a1c932fbb1d721aa8a8996e4597bea35603be18b613d901f5c6dc3b2cea5",
    REFERENCE + "/RandomSource.java": "52fcecf357dbe4d41cf367a057326dc11df645582728cfdd0b978866229b66df",
    REFERENCE + "/SingleThreadedRandomSource.java": "1eb2853c0bb8237053e7ad245dd134529b36824e44ff9f7eee15852e8d524deb",
    REFERENCE + "/BitRandomSource.java": "0b9a8e5ac79d2fe9692aa1f8412bc251be7e8b3b45fecb4dd71afb41a785fa7f",
    REFERENCE + "/Mth.java": "521c811d6323efed4c6b551e18de3a4122eeb96fc63141a75f0d7dc04479297c",
    REFERENCE + "/RenderPipelines.java": "5a17178fb1871c74c0260768c36ae8b6265b244d7cbe2b6d5a762569048cce7f",
    MIXIN: "dcfa2becd89696cd0f3e1d6e642a763875d5c02ae18d91355154286a11d44ed3",
}
LIMITS = {
    "input_file_bytes": 2 * 1024 * 1024,
    "total_input_bytes": 8 * 1024 * 1024,
    "manifest_bytes": 1024 * 1024,
    "vertices": CANDIDATES * 6,
    "geometry_bytes": 16 + CANDIDATES * 6 * STRIDE,
}
# These source coordinates were read through IDE external-library symbols.
# They identify actual 1.10.9 methods, not an invented digest of an unread JAR.
JOML_REFERENCE = {
    "artifact": "org.joml:joml:1.10.9",
    "retrieval": "IDE external source symbols from the installed 1.10.9 sources JAR",
    "observed_source_cache_key": "f100d496eb78e06518f33bb3282db195d2703500",
    "full_method_bodies_read": [
        {"entry": "org/joml/Vector3f.java", "lines": [2011, 2017], "symbol": "normalize(float, Vector3f)"},
        {"entry": "org/joml/Vector3f.java", "lines": [1081, 1087], "symbol": "mul(Matrix3fc, Vector3f)"},
        {"entry": "org/joml/Matrix3f.java", "lines": [1957, 1982], "symbol": "rotateZ(float, Matrix3f)"},
        {"entry": "org/joml/Math.java", "lines": [206, 213], "symbol": "sin(float)"},
        {"entry": "org/joml/Math.java", "lines": [239, 249], "symbol": "cosFromSinInternal(float, float)"},
        {"entry": "org/joml/Math.java", "lines": [273, 275], "symbol": "invsqrt(float)"},
        {"entry": "org/joml/Math.java", "lines": [473, 477], "symbol": "fma(float, float, float)"},
    ],
    "basis": {
        "entry": "org/joml/Matrix3f.java", "symbol": "rotateTowards",
        "basis_derived_from_documented_contract": True,
        "contract": "+Z=normalize(direction); +X=normalize(up cross +Z); +Y=+Z cross +X; right-handed",
        "full_implementation_body_read": False,
    },
    "defaults_read": {
        "Options.FASTMATH": False, "Options.USE_MATH_FMA": False,
        "Runtime.HAS_Math_fma": "Options.USE_MATH_FMA && hasMathFma()",
    },
}


class ExportError(Exception):
    pass


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def project_path(relative: str) -> Path:
    path = ROOT / relative
    if path.resolve() != path:
        raise ExportError("Refusing symlinked or redirected path: " + relative)
    try:
        path.relative_to(ROOT)
    except ValueError as error:
        raise ExportError("Path escapes project: " + relative) from error
    return path


def read_inputs() -> tuple[list[dict], list[dict]]:
    inputs = []
    raw = {}
    total = 0
    for path in (*PINNED, SOURCE_MANIFEST, GENERATOR):
        with project_path(path).open("rb") as handle:
            data = handle.read(LIMITS["input_file_bytes"] + 1)
        total += len(data)
        if len(data) > LIMITS["input_file_bytes"] or total > LIMITS["total_input_bytes"]:
            raise ExportError("Input byte budget exceeded")
        actual = sha256(data)
        if path in PINNED and actual != PINNED[path]:
            raise ExportError("Source changed; review the star port before regenerating: " + path)
        raw[path] = data
        inputs.append({"path": path, "sha256": actual, "bytes": len(data)})
    manifest = json.loads(raw[SOURCE_MANIFEST].decode("utf-8"))
    if manifest.get("schema") != 1:
        raise ExportError("Unsupported celestial reference manifest")
    records = {record["output"]: record for record in manifest.get("references", [])}
    provenance = []
    for path, expected in PINNED.items():
        if path == MIXIN:
            continue
        record = records.get(path)
        if (record is None or record.get("complete") is not True or
                record.get("sha256") != expected or record.get("bytes") != len(raw[path])):
            raise ExportError("Missing or inconsistent complete source provenance: " + path)
        provenance.append(record)
    return inputs, provenance


def f32(value: float) -> float:
    return struct.unpack("<f", struct.pack("<f", value))[0]


def add(a: float, b: float) -> float:
    return f32(a + b)


def sub(a: float, b: float) -> float:
    return f32(a - b)


def mul(a: float, b: float) -> float:
    return f32(a * b)


def invsqrt(value: float) -> float:
    # JOML float overload: 1.0f / (float) java.lang.Math.sqrt(value).
    return f32(1.0 / f32(math.sqrt(value)))


def length_squared_left(vector: tuple[float, float, float]) -> float:
    # Mth.lengthSquared(float,float,float): (x*x + y*y) + z*z.
    x, y, z = vector
    return add(add(mul(x, x), mul(y, y)), mul(z, z))


def normalize_joml(vector: tuple[float, float, float], length: float) -> tuple[float, float, float]:
    # Actual Vector3f.normalize(length,dest), with default JOML FMA disabled.
    x, y, z = vector
    squared = add(mul(x, x), add(mul(y, y), mul(z, z)))
    scalar = mul(invsqrt(squared), length)
    return mul(x, scalar), mul(y, scalar), mul(z, scalar)


def cross(a: tuple[float, float, float], b: tuple[float, float, float]) -> tuple[float, float, float]:
    return (sub(mul(a[1], b[2]), mul(a[2], b[1])),
            sub(mul(a[2], b[0]), mul(a[0], b[2])),
            sub(mul(a[0], b[1]), mul(a[1], b[0])))


def normalize_basis(vector: tuple[float, float, float]) -> tuple[float, float, float]:
    squared = length_squared_left(vector)
    if squared <= 0.0:
        raise ExportError("Degenerate documented rotateTowards basis")
    scalar = invsqrt(squared)
    return tuple(mul(component, scalar) for component in vector)


def cos_from_sin(sine: float, angle: float) -> float:
    # Actual JOML cosFromSinInternal(float,float), FASTMATH=false.
    cosine = f32(math.sqrt(sub(1.0, mul(sine, sine))))
    pi = f32(math.pi)
    half_pi = f32(math.pi * 0.5)
    tau = f32(math.pi * 2.0)
    a = add(angle, half_pi)
    b = sub(a, mul(float(int(f32(a / tau))), tau))
    if b < 0.0:
        b = add(tau, b)
    return -cosine if b >= pi else cosine


def rotation_columns(center: tuple[float, float, float], z_rotation: float) -> tuple:
    # Equivalent evaluation of the documented rotateTowards(-center, upY).
    forward = normalize_basis(tuple(-component for component in center))
    right = normalize_basis(cross((0.0, 1.0, 0.0), forward))
    up = cross(forward, right)
    angle = -z_rotation
    sine = f32(math.sin(angle))
    cosine = cos_from_sin(sine, angle)
    # Actual Matrix3f.rotateZ post-multiplication: [right up forward] * Rz.
    first = tuple(add(mul(right[i], cosine), mul(up[i], sine)) for i in range(3))
    second = tuple(add(mul(right[i], -sine), mul(up[i], cosine)) for i in range(3))
    return first, second, forward


class SingleThreadedRandom:
    def __init__(self, seed: int) -> None:
        self.state = (seed ^ 25214903917) & 281474976710655
        self.calls = 0

    def next(self, bits: int) -> int:
        self.state = (self.state * 25214903917 + 11) & 281474976710655
        self.calls += 1
        return self.state >> (48 - bits)

    def next_float(self) -> float:
        return mul(float(self.next(24)), f32(5.9604645e-8))

    def next_double(self) -> float:
        upper = self.next(26)
        lower = self.next(27)
        combined = (upper << 27) + lower
        # BitRandomSource uses the float literal, promoted to double.
        return combined * f32(1.110223e-16)


def build_geometry() -> tuple[bytes, dict]:
    random = SingleThreadedRandom(SEED)
    vertices = bytearray()
    accepted = []
    trace = hashlib.sha256()
    minimum = f32(0.054)
    variation = f32(0.030)
    threshold = f32(0.010000001)
    for candidate in range(CANDIDATES):
        xyz = tuple(sub(mul(random.next_float(), 2.0), 1.0) for _ in range(3))
        # The fourth draw happens even when the candidate is later rejected.
        size = add(minimum, mul(random.next_float(), variation))
        squared = length_squared_left(xyz)
        keep = not (squared <= threshold) and not (squared >= 1.0)
        trace.update(struct.pack("<I5fB", candidate, *xyz, size, squared, int(keep)))
        if not keep:
            continue
        accepted.append(candidate)
        center = normalize_joml(xyz, 100.0)
        # Preserve Java's double expression with a float PI operand before cast.
        z_rotation = f32(random.next_double() * f32(math.pi) * 2.0)
        columns = rotation_columns(center, z_rotation)
        corners = []
        for x, y in ((size, -size), (size, size), (-size, size), (-size, -size)):
            # Actual Vector3f.mul(Matrix3fc,dest) then .add(center), FMA=false.
            point = tuple(add(add(mul(columns[0][i], x),
                                  add(mul(columns[1][i], y), mul(columns[2][i], 0.0))),
                              center[i]) for i in range(3))
            if not all(math.isfinite(value) and abs(value) <= 101.0 for value in point):
                raise ExportError("Invalid star vertex")
            corners.append(struct.pack("<4f", *point, 1.0))
        for corner in TRIANGLE_ORDER:
            vertices.extend(corners[corner])
    count = len(accepted) * 6
    if (not accepted or count > LIMITS["vertices"] or len(vertices) != count * STRIDE or
            random.calls != CANDIDATES * 4 + len(accepted) * 2):
        raise ExportError("Star count, vertex size or random draw invariant failed")
    result = struct.pack("<8s2I", MAGIC, count, STRIDE) + vertices
    if len(result) > LIMITS["geometry_bytes"]:
        raise ExportError("Geometry byte limit exceeded")
    return result, {
        "candidate_count": CANDIDATES, "accepted_stars": len(accepted),
        "accepted_candidate_indices": accepted, "vertex_count": count,
        "candidate_trace_sha256": trace.hexdigest(),
        "candidate_trace_encoding": "Per candidate: little-endian u32 index, float32 x/y/z/size/lengthSquared, u8 accepted",
        "random_next_calls": random.calls, "random_final_state": random.state,
    }


def write_output(relative: str, data: bytes) -> None:
    destination = project_path(relative)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = project_path(relative + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(destination)


def main() -> int:
    if len(sys.argv) != 1:
        raise ExportError("No arguments accepted; all source and output paths are fixed")
    inputs, provenance = read_inputs()
    geometry, statistics = build_geometry()
    output = OUTPUT + "/stars.dws"
    manifest_path = OUTPUT + "/stars-manifest.json"
    manifest = {
        "schema": 1, "inputs": inputs, "complete_vanilla_source_provenance": provenance,
        "joml_reference": JOML_REFERENCE, "limits": LIMITS,
        "algorithm": {
            "source": "SkyRenderer.buildStars, complete source lines 401-430",
            "random": "RandomSource.createThreadLocalInstance -> SingleThreadedRandomSource + BitRandomSource",
            "seed": SEED, "candidate_count": CANDIDATES,
            "size_policy": "SmallStarsMixin visuals-enabled policy: .054F + nextFloat() * .030F",
            "small_stars_policy": True, "runtime_brightness_multiplier": 0.48,
            "distance": 100.0, "triangle_corner_order": list(TRIANGLE_ORDER),
            "candidate_rejection": "float32 lengthSquared > float32(.010000001) and < 1",
            "camera_and_clock_rotation": "Not baked; renderer applies Y(-90 degrees) * X(starAngle)",
        },
        "precision": {
            "random_and_acceptance": "Integer LCG and explicit Java binary32 operation sequence",
            "geometry": "Explicit binary32 arithmetic; documented JOML basis and read method formulas",
            "transcendentals": "Host Python math.sin/sqrt followed by binary32 casts",
            "jvm_bitwise_identity_claimed": False,
        },
        "format": {
            "magic_hex": MAGIC.hex(), "header_bytes": 16, "vertex_stride": STRIDE,
            "header_fields": ["little-endian u32 vertexCount", "little-endian u32 stride"],
            "vertices": "little-endian float32 x,y,z,1; unindexed triangle list",
            "descriptor": {"set": 0, "binding": 16, "type": "readonly storage buffer", "element": "vec4"},
        },
        "statistics": statistics,
        "outputs": [{"path": output, "bytes": len(geometry), "sha256": sha256(geometry)}],
        "hash_scope": "Exact generator and project input bytes, candidate trace and geometry; manifest is not self-hashed",
    }
    encoded = (json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")
    if len(encoded) > LIMITS["manifest_bytes"]:
        raise ExportError("Manifest byte limit exceeded")
    for path in (output, manifest_path):
        project_path(path)
        project_path(path + ".tmp")
    write_output(output, geometry)
    write_output(manifest_path, encoded)
    print("%s: %d stars, %d vertices" %
          (manifest_path, statistics["accepted_stars"], statistics["vertex_count"]))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ExportError, OSError, ValueError, OverflowError, KeyError) as error:
        print("visual star export: " + str(error), file=sys.stderr)
        raise SystemExit(1)
