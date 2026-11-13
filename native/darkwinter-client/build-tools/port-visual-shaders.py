#!/usr/bin/env python3
"""Port the fixed DarkWinter visual shaders without changing their algorithms.

Uses only the Python standard library. Run from any working directory; no
compiler, process, network, source-tree mutation, or third-party package is used.
Include guards and macro scope are preserved for the GLSL preprocessor.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GENERATOR = "native/darkwinter-client/build-tools/port-visual-shaders.py"
SOURCE = "modules/darkwinter-visuals/src/client/resources/assets/darkwinter_visuals/shaders"
INCLUDE = SOURCE + "/include"
NATIVE = "WKE/runtime/shaders/visuals"
NATIVE_STAGES = (
    "fullscreen.vert", "celestial.vert", "celestial.frag",
    "star.vert", "star.frag", "cloud_resolve.frag", "ambient.frag", "ui_composite.frag",
)
OUTPUT = "build/core/visual-shaders"
STAGES = (
    "post/surface.fsh", "post/volume.fsh", "post/composite.fsh",
    "post/sky_clouds.fsh", "post/sky_merge.fsh", "post/sky_merge_fused.fsh",
    "post/film.fsh", "core/weather_sky_background.fsh",
    "core/weather_sky_clouds.fsh",
)
SAMPLERS = {
    "MainSampler": 0, "DepthSampler": 1, "ShadowSampler": 2,
    "VolumeSampler": 3, "AtmosphereSampler": 3, "SkySampler": 4,
    "NativeSkySampler": 5, "NativeCloudSampler": 6,
    "PreviousSampler": 7, "HeldSampler": 9,
}
SCENE_FIELDS = (
    "Inv0", "Inv1", "Inv2", "Inv3", "Light0", "Light1", "Light2", "Light3",
    "Sun", "Air", "Shadow", "Climate", "WindTime", "Flags", "CameraInfo",
    "Options", "Look", "Analog", "FogLayer", "FogStyle", "FogRange",
    "WeatherShape", "WeatherTint", "CloudDrift", "Film", "FilmTiming",
)
BLOCKS = {
    "DarkWinterScene": (8, 416, tuple(("vec4", name) for name in SCENE_FIELDS)),
    "WeatherSkyProjection": (10, 80, (
        ("mat4", "WeatherViewProjection"), ("vec4", "WeatherDepthConvention"))),
}
LIMITS = {
    "input_files": 64,
    "input_file_bytes": 512 * 1024,
    "total_input_bytes": 4 * 1024 * 1024,
    "output_file_bytes": 4 * 1024 * 1024,
    "total_output_bytes": 16 * 1024 * 1024,
    "manifest_bytes": 2 * 1024 * 1024,
    "include_depth": 16,
    "include_expansions_per_stage": 128,
    "provenance_events_per_stage": 4096,
}
DIRECTIVE = re.compile(r"^\s*#\s*([A-Za-z_]\w*)\b(.*)$")
INCLUDE_NAME = re.compile(r"<darkwinter_visuals:([A-Za-z_][A-Za-z0-9_]*\.glsl)>")
SAMPLER_DECL = re.compile(r"^(\s*)uniform\s+sampler2D\s+([A-Za-z_]\w*)\s*;\s*$")
BLOCK_HEADER = re.compile(
    r"^\s*(layout\s*\(\s*std140\s*\))\s+uniform\s+([A-Za-z_]\w*)\s*\{\s*$")
BLOCK_BODY = re.compile(
    r"\blayout\s*\(\s*std140\s*\)\s+uniform\s+([A-Za-z_]\w*)\s*\{([^}]*)\}\s*;",
    re.DOTALL)
MEMBER = re.compile(r"\b(vec4|mat4)\s+([A-Za-z_]\w*)\s*;")
MACRO_DIRECTIVES = frozenset(("define", "undef", "if", "ifdef", "ifndef", "elif", "else", "endif"))


class PortError(Exception):
    pass


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def project_path(relative: str) -> Path:
    """All callers supply fixed paths or a validated single include basename."""
    path = ROOT / relative
    if path.resolve() != path:
        raise PortError("Refusing symlinked or redirected project path: " + relative)
    try:
        path.relative_to(ROOT)
    except ValueError as error:
        raise PortError("Path escapes project: " + relative) from error
    return path


def mask_comments(text: str) -> str:
    """Retain columns/newlines so declaration replacements use original spans."""
    def blank(match: re.Match[str]) -> str:
        return "".join("\n" if char == "\n" else " " for char in match.group())
    return re.sub(r"/\*.*?\*/|//[^\n]*", blank, text, flags=re.DOTALL)


class Inputs:
    def __init__(self) -> None:
        self.files: dict[str, bytes] = {}
        self.total = 0

    def read(self, relative: str) -> bytes:
        if relative not in self.files:
            if len(self.files) >= LIMITS["input_files"]:
                raise PortError("Input file count limit exceeded")
            with project_path(relative).open("rb") as handle:
                data = handle.read(LIMITS["input_file_bytes"] + 1)
            if len(data) > LIMITS["input_file_bytes"]:
                raise PortError("Input file too large: " + relative)
            self.total += len(data)
            if self.total > LIMITS["total_input_bytes"]:
                raise PortError("Total input size limit exceeded")
            data.decode("utf-8")  # Fail closed on non-UTF-8 inputs.
            self.files[relative] = data
        return self.files[relative]

    def text(self, relative: str) -> str:
        return self.read(relative).decode("utf-8").replace("\r\n", "\n").replace("\r", "\n")

    def manifest(self) -> list[dict]:
        return [
            {"path": path, "sha256": digest(data), "bytes": len(data)}
            for path, data in sorted(self.files.items())
        ]


class Stage:
    def __init__(self, inputs: Inputs, source: str, output: str) -> None:
        self.inputs = inputs
        self.source = source
        self.output = output
        self.data = bytearray()
        self.ids: dict[str, int] = {}
        self.includes: list[dict] = []
        self.macros: list[dict] = []
        self.adapters: list[dict] = []
        self.bindings: dict[int, str] = {}
        self.version_count = 0
        self.events = 0

    def event(self, target: list[dict], record: dict) -> None:
        self.events += 1
        if self.events > LIMITS["provenance_events_per_stage"]:
            raise PortError("Provenance event limit exceeded: " + self.source)
        target.append(record)

    def emit(self, text: str) -> None:
        encoded = text.encode("utf-8")
        if len(self.data) + len(encoded) > LIMITS["output_file_bytes"]:
            raise PortError("Expanded shader too large: " + self.source)
        self.data.extend(encoded)

    def source_id(self, relative: str) -> int:
        if relative not in self.ids:
            self.ids[relative] = len(self.ids) + 1
        return self.ids[relative]

    def bind(self, binding: int, name: str) -> None:
        previous = self.bindings.get(binding)
        if previous is not None and previous != name:
            raise PortError("Conflicting aliases at binding %d in %s: %s / %s" %
                            (binding, self.source, previous, name))
        self.bindings[binding] = name

    def check_blocks(self, text: str, relative: str) -> None:
        found = set()
        for match in BLOCK_BODY.finditer(text):
            name, body = match.groups()
            if name not in BLOCKS:
                raise PortError("Unknown uniform block in " + relative + ": " + name)
            fields = tuple(MEMBER.findall(body))
            if MEMBER.sub("", body).strip() or fields != BLOCKS[name][2]:
                raise PortError("Changed std140 field contract in " + relative + ": " + name)
            size = sum(64 if kind == "mat4" else 16 for kind, _ in fields)
            if size != BLOCKS[name][1]:
                raise PortError("Changed std140 byte size in " + relative + ": " + name)
            found.add(name)
        headers = {match.group(2) for line in text.splitlines()
                   if (match := BLOCK_HEADER.fullmatch(line))}
        if headers != found:
            raise PortError("Unsupported or incomplete uniform block in " + relative)

    def adapt_projection(self, source: str, relative: str) -> str:
        if relative != INCLUDE + "/weather_sky_ray.glsl":
            return source
        original = """vec3 dwWeatherRay(vec2 uv) {
    // Both samples lie on the same projective ray on either clip-depth convention.
    return normalize(dwWeatherUnproject(uv,0.0)-dwWeatherUnproject(uv,1.0));
}"""
        replacement = """vec3 dwWeatherRay(vec2 uv) {
    // Vulkan adapter: homogeneous subtraction also handles infinite reverse-Z (far.w=0).
    mat4 inverseVP=mat4(Inv0,Inv1,Inv2,Inv3);
    vec4 farPoint=inverseVP*vec4(uv*2.0-1.0,0.0,1.0);
    vec4 nearPoint=inverseVP*vec4(uv*2.0-1.0,1.0,1.0);
    return normalize(farPoint.xyz*nearPoint.w-nearPoint.xyz*farPoint.w);
}"""
        if source.count(original) != 1:
            raise PortError("Weather ray source changed; review infinite reverse-Z adapter")
        self.event(self.adapters, {
            "path": relative, "line": source[:source.index(original)].count("\n") + 1,
            "kind": "infinite_reverse_z_ray",
            "from": original, "to": replacement,
            "reason": "Equivalent homogeneous far-minus-near direction, valid at far.w=0",
        })
        return source.replace(original, replacement)

    def expand(self, relative: str, stack: tuple[str, ...] = ()) -> None:
        if relative in stack:
            raise PortError("Include cycle: " + " -> ".join(stack + (relative,)))
        if len(stack) >= LIMITS["include_depth"]:
            raise PortError("Include depth limit exceeded: " + relative)
        source = self.adapt_projection(self.inputs.text(relative), relative)
        masked = mask_comments(source)
        self.check_blocks(masked, relative)
        source_id = self.source_id(relative)
        self.emit("#line 1 %d\n" % source_id)
        # Keep conditional directives and macro scope exactly as GLSL defines them.
        # Inactive includes are still validated: no foreign/traversing imports hide
        # behind a conditional. No C-preprocessor or macro reimplementation is used.
        for number, (line, code) in enumerate(zip(source.splitlines(), masked.splitlines()), 1):
            location = {"path": relative, "line": number}
            directive = DIRECTIVE.fullmatch(code)
            if directive:
                command, argument = directive.groups()
                argument = argument.strip()
                if code.rstrip().endswith("\\"):
                    raise PortError("Continued preprocessor directives are unsupported: " + relative)
                if command == "include":
                    include = INCLUDE_NAME.fullmatch(argument)
                    if include is None:
                        raise PortError("Only fixed-directory darkwinter_visuals includes are allowed: " +
                                        relative + ":" + str(number))
                    if len(self.includes) >= LIMITS["include_expansions_per_stage"]:
                        raise PortError("Include expansion limit exceeded: " + self.source)
                    target = INCLUDE + "/" + include.group(1)
                    self.event(self.includes, {**location, "target": target, "depth": len(stack) + 1})
                    self.expand(target, stack + (relative,))
                    self.emit("#line %d %d\n" % (number + 1, source_id))
                    continue
                if command == "version":
                    if relative != self.source or argument != "330":
                        raise PortError("Expected one root #version 330: " + relative)
                    self.version_count += 1
                    self.event(self.adapters, {**location, "kind": "version",
                                              "from": line, "to": "#version 450 (output header)"})
                    self.emit("\n")
                    continue
                if command == "extension":
                    if not re.fullmatch(r"GL_ARB_separate_shader_objects\s*:\s*require", argument):
                        raise PortError("Unsupported extension directive in " + relative)
                    self.event(self.adapters, {**location, "kind": "remove_redundant_extension",
                                              "from": line, "to": ""})
                    self.emit("\n")
                    continue
                if command not in MACRO_DIRECTIVES:
                    raise PortError("Unsupported preprocessor directive #" + command + " in " + relative)
                self.event(self.macros, {**location, "directive": line, "handling": "preserved"})
            elif re.search(r"\buniform\b", code):
                sampler = SAMPLER_DECL.fullmatch(code)
                block = BLOCK_HEADER.fullmatch(code)
                if sampler:
                    name = sampler.group(2)
                    if name not in SAMPLERS:
                        raise PortError("Unknown sampler in " + relative + ": " + name)
                    binding = SAMPLERS[name]
                    self.bind(binding, name)
                    indent = len(sampler.group(1))
                    replacement = line[:indent] + ("layout(set=0,binding=%d) " % binding) + line[indent:]
                elif block and block.group(2) in BLOCKS:
                    name = block.group(2)
                    binding = BLOCKS[name][0]
                    self.bind(binding, name)
                    start, end = block.span(1)
                    replacement = line[:start] + ("layout(std140,set=0,binding=%d)" % binding) + line[end:]
                else:
                    raise PortError("Unsupported uniform declaration in " + relative + ":" + str(number))
                self.event(self.adapters, {**location, "kind": "descriptor_binding",
                                          "name": name, "set": 0, "binding": binding,
                                          "from": line, "to": replacement})
                line = replacement
            self.emit(line + "\n")

    def build(self) -> bytes:
        self.emit("#version 450\n")
        self.expand(self.source)
        if self.version_count != 1:
            raise PortError("Expected exactly one #version 330 in " + self.source)
        return bytes(self.data)

    def manifest(self) -> dict:
        return {
            "input": self.source, "output": self.output,
            "source_ids": [{"id": number, "path": path} for path, number in self.ids.items()],
            "includes": self.includes, "macros": self.macros, "adapters": self.adapters,
            "used_bindings": [{"set": 0, "binding": binding, "name": name}
                              for binding, name in sorted(self.bindings.items())],
        }


def write_output(relative: str, data: bytes) -> None:
    destination = project_path(relative)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = project_path(relative + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(destination)


def main() -> int:
    if len(sys.argv) != 1:
        raise PortError("No arguments accepted; input stages and output paths are fixed")
    inputs = Inputs()
    inputs.read(GENERATOR)
    outputs: dict[str, bytes] = {}
    stages = []
    for stage_name in STAGES:
        source = SOURCE + "/" + stage_name
        output = OUTPUT + "/src/" + Path(stage_name).stem + ".frag"
        stage = Stage(inputs, source, output)
        outputs[output] = stage.build()
        stages.append(stage.manifest())
    # Fixed native integration adapters are copied byte-for-byte. No shader
    # algorithm is selected by a caller-provided path or command.
    native_stages = []
    for name in NATIVE_STAGES:
        source = NATIVE + "/" + name
        data = inputs.read(source)
        if data.decode("utf-8").splitlines()[0].strip() != "#version 450":
            raise PortError("Native adapter must declare GLSL 450: " + source)
        output = OUTPUT + "/src/" + name
        outputs[output] = data
        native_stages.append({"input": source, "output": output, "handling": "exact byte copy"})
    manifest = {
        "schema": 1, "generator": GENERATOR, "target": "GLSL 450 for Vulkan",
        "source_root": SOURCE, "output_root": OUTPUT, "limits": LIMITS,
        "include_policy": {
            "directory": INCLUDE, "namespace": "darkwinter_visuals",
            "guards": "Preserved verbatim for the GLSL preprocessor",
            "cycles": "Rejected", "conditional_imports": "Validated even in inactive branches",
        },
        "adapters": [
            "Replace the root #version 330 with #version 450",
            "Remove GL_ARB_separate_shader_objects : require",
            "Bind only allowlisted sampler2D declarations at descriptor set 0",
            "Bind and validate the two original std140 uniform blocks",
            "Expand fixed-directory includes with numeric #line source provenance",
            "Normalize fragment source line endings to LF; preserve source functions and macros except the recorded projection adapter",
            "Replace weather-ray division at far depth zero by its homogeneous equivalent for infinite reverse-Z",
            "Copy the fixed native fullscreen, celestial, stars, cloud-resolve and UI adapters byte-for-byte",
        ],
        "bindings": [
            {"set": 0, "binding": binding, "names": sorted(
                name for name, value in SAMPLERS.items() if value == binding),
             "type": "combined_image_sampler"}
            for binding in sorted(set(SAMPLERS.values()))
        ] + [
            {"set": 0, "binding": spec[0], "name": name, "type": "uniform_buffer",
             "layout": "std140", "bytes": spec[1],
             "fields": [{"type": kind, "name": field} for kind, field in spec[2]]}
            for name, spec in BLOCKS.items()
        ],
        "inputs": inputs.manifest(),
        "outputs": [
            {"path": path, "sha256": digest(data), "bytes": len(data)}
            for path, data in sorted(outputs.items())
        ],
        "hash_scope": "Exact input bytes and generated shader bytes; manifest excludes its own digest",
        "stages": stages,
        "native_stages": native_stages,
        "native_replacements": [{
            "shader": "ambient.frag",
            "purpose": "Retain the native ambient-occlusion setting after replacing BVH rendering",
            "origin": "Native raster adapter; not an original DarkWinter mod shader",
            "method": "Eight screen-space depth taps, reconstructed positions and derivative normal",
            "radius_blocks": 0.85, "maximum_darkness": 0.25,
            "off_behavior": "Exact source color sample",
            "guards": ["Sky and frame edges", "Discontinuous derivative quads",
                       "World-space sample distance", "Zero-length normals"],
            "parity": "No claim of equivalence to the former ray-traced AO",
            "control": {"set": 0, "binding": 12, "byte_offset": 32, "component": 0},
        }],
    }
    encoded = (json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    if len(encoded) > LIMITS["manifest_bytes"]:
        raise PortError("Manifest size limit exceeded")
    total = len(encoded) + sum(len(data) for data in outputs.values())
    if total > LIMITS["total_output_bytes"]:
        raise PortError("Total output size limit exceeded")
    # Validate every destination before creating any output.
    manifest_path = OUTPUT + "/port-manifest.json"
    for path in (*outputs, manifest_path):
        project_path(path)
        project_path(path + ".tmp")
    for path, data in outputs.items():
        write_output(path, data)
    write_output(manifest_path, encoded)
    print("%s: %d shaders, %d inputs" % (manifest_path, len(outputs), len(inputs.files)))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (PortError, OSError, UnicodeError) as error:
        print("visual shader port: " + str(error), file=sys.stderr)
        raise SystemExit(1)
