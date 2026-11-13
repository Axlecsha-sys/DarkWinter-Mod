#!/usr/bin/env python3
"""Export original celestial texels and full vanilla source references.

Only fixed project paths are read or written. Uses Python's standard library;
never runs a compiler/process, changes source assets, or accesses the network.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import struct
import sys
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GENERATOR = "native/darkwinter-client/build-tools/export-visual-celestials.py"
TEXTURES = "modules/darkwinter-visuals/src/client/resources/assets/minecraft/textures/environment/celestial"
CACHE = ".gradle/loom-cache/minecraftMaven"
OUTPUT = "build/core/visual-shaders/celestial"
PHASES = (
    "full_moon", "waning_gibbous", "third_quarter", "waning_crescent",
    "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous",
)
JAVA = (
    "SkyRenderer.java", "RenderPipelines.java", "MoonPhase.java",
    "RandomSource.java", "SingleThreadedRandomSource.java", "BitRandomSource.java",
    "Mth.java", "Timelines.java", "EasingType.java",
)
# Names verified in the full 26.3 RenderPipelines source: CELESTIAL uses
# core/position_tex; STARS uses core/stars. Other retained entries are references.
OPTIONAL_SHADERS = tuple(
    "assets/minecraft/shaders/core/" + name + "." + extension
    for name in ("position_tex", "position_tex_color", "position", "sky", "stars")
    for extension in ("vsh", "fsh")
)
MAGIC = b"DWCEL1\0\0"
LIMITS = {
    "png_file_bytes": 16 * 1024 * 1024,
    "total_png_bytes": 64 * 1024 * 1024,
    "png_chunks": 2048,
    "image_dimension": 4096,
    "image_pixels": 4 * 1024 * 1024,
    "total_rgba_bytes": 64 * 1024 * 1024,
    "cache_depth": 12,
    "cache_directories": 4096,
    "cache_entries": 100000,
    "jar_files": 32,
    "jar_file_bytes": 256 * 1024 * 1024,
    "total_jar_bytes": 1024 * 1024 * 1024,
    "zip_entries": 50000,
    "zip_directory_bytes": 16 * 1024 * 1024,
    "reference_file_bytes": 2 * 1024 * 1024,
    "reference_read_bytes": 64 * 1024 * 1024,
    "total_reference_bytes": 32 * 1024 * 1024,
    "manifest_bytes": 2 * 1024 * 1024,
    "total_output_bytes": 128 * 1024 * 1024,
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


def read_bounded(relative: str, limit: int) -> bytes:
    with project_path(relative).open("rb") as handle:
        data = handle.read(limit + 1)
    if len(data) > limit:
        raise ExportError("Input exceeds size limit: " + relative)
    return data


def png_rgba(data: bytes, label: str) -> tuple[int, int, bytes, dict]:
    """Decode noninterlaced 8-bit PNG with no color conversion or resampling."""
    def fail(reason: str) -> None:
        raise ExportError(label + ": " + reason)

    if data[:8] != b"\x89PNG\r\n\x1a\n":
        fail("invalid PNG signature")
    offset, chunks = 8, 0
    header = None
    palette = None
    transparency = None
    compressed = bytearray()
    saw_idat = False
    idat_ended = False
    saw_end = False
    while offset < len(data):
        chunks += 1
        if chunks > LIMITS["png_chunks"] or len(data) - offset < 12:
            fail("too many chunks or truncated chunk")
        length, kind = struct.unpack_from(">I4s", data, offset)
        if length > LIMITS["png_file_bytes"] or offset + 12 + length > len(data):
            fail("invalid or oversized chunk")
        if not re.fullmatch(rb"[A-Za-z]{4}", kind) or kind[2] & 32:
            fail("invalid chunk type")
        payload = data[offset + 8:offset + 8 + length]
        expected_crc = struct.unpack_from(">I", data, offset + 8 + length)[0]
        actual_crc = zlib.crc32(payload, zlib.crc32(kind)) & 0xffffffff
        if actual_crc != expected_crc:
            fail("CRC mismatch in " + kind.decode("ascii"))
        offset += length + 12
        if header is None and kind != b"IHDR":
            fail("IHDR must be the first chunk")
        if saw_idat and kind != b"IDAT":
            idat_ended = True
        if kind == b"IHDR":
            if header is not None or length != 13:
                fail("duplicate or invalid IHDR")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if not (1 <= width <= LIMITS["image_dimension"] and
                    1 <= height <= LIMITS["image_dimension"] and
                    width * height <= LIMITS["image_pixels"]):
                fail("image dimensions exceed limits")
            if depth != 8 or color not in (0, 2, 3, 4, 6):
                fail("only 8-bit grayscale, RGB, palette, GA and RGBA are supported")
            if compression != 0 or filtering != 0 or interlace != 0:
                fail("unsupported PNG compression, filtering or interlace")
            header = (width, height, color)
        elif kind == b"PLTE":
            if saw_idat or palette is not None or header[2] in (0, 4):
                fail("invalid PLTE placement or color type")
            if length == 0 or length > 768 or length % 3:
                fail("invalid palette size")
            palette = tuple(tuple(payload[index:index + 3]) for index in range(0, length, 3))
        elif kind == b"tRNS":
            if saw_idat or transparency is not None:
                fail("invalid tRNS placement")
            color = header[2]
            if color == 3:
                if palette is None or not (1 <= length <= len(palette)):
                    fail("palette tRNS requires a preceding palette")
            elif color == 0:
                if length != 2 or struct.unpack(">H", payload)[0] > 255:
                    fail("invalid 8-bit grayscale transparency")
            elif color == 2:
                if length != 6 or any(value > 255 for value in struct.unpack(">3H", payload)):
                    fail("invalid 8-bit RGB transparency")
            else:
                fail("tRNS is invalid for an alpha-bearing color type")
            transparency = payload
        elif kind == b"IDAT":
            if idat_ended or (header[2] == 3 and palette is None):
                fail("noncontiguous IDAT or missing palette")
            saw_idat = True
            compressed.extend(payload)
            if len(compressed) > LIMITS["png_file_bytes"]:
                fail("compressed image exceeds limit")
        elif kind == b"IEND":
            if length != 0 or not saw_idat or offset != len(data):
                fail("invalid IEND or trailing data")
            saw_end = True
            break
        elif kind in (b"acTL", b"fcTL", b"fdAT"):
            fail("animated PNG is unsupported")
        elif not (kind[0] & 32):
            fail("unknown critical chunk " + kind.decode("ascii"))
        # Ancillary metadata is CRC-checked but never used to modify texels.
    if header is None or not saw_end:
        fail("incomplete PNG")
    width, height, color = header
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color]
    stride = width * channels
    expected_size = (stride + 1) * height
    inflater = zlib.decompressobj()
    raw = inflater.decompress(bytes(compressed), expected_size + 1)
    if (len(raw) != expected_size or not inflater.eof or inflater.unconsumed_tail or
            inflater.unused_data):
        fail("invalid, truncated, trailing or oversized zlib stream")
    transparent_gray = struct.unpack(">H", transparency)[0] if color == 0 and transparency is not None else None
    transparent_rgb = struct.unpack(">3H", transparency) if color == 2 and transparency is not None else None
    palette_alpha = transparency or b""
    rgba = bytearray()
    previous = bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        mode = raw[start]
        if mode > 4:
            fail("invalid PNG filter byte")
        row = bytearray(raw[start + 1:start + stride + 1])
        if mode == 1:
            for x in range(channels, stride):
                row[x] = (row[x] + row[x - channels]) & 255
        elif mode == 2:
            for x in range(stride):
                row[x] = (row[x] + previous[x]) & 255
        elif mode in (3, 4):
            for x in range(stride):
                left = row[x - channels] if x >= channels else 0
                above = previous[x]
                if mode == 3:
                    prediction = (left + above) // 2
                else:
                    corner = previous[x - channels] if x >= channels else 0
                    prediction = left + above - corner
                    dl, da, dc = abs(prediction - left), abs(prediction - above), abs(prediction - corner)
                    prediction = left if dl <= da and dl <= dc else above if da <= dc else corner
                row[x] = (row[x] + prediction) & 255
        if color == 6:
            rgba.extend(row)
        elif color == 2:
            for x in range(0, stride, 3):
                rgb = (row[x], row[x + 1], row[x + 2])
                rgba.extend((*rgb, 0 if rgb == transparent_rgb else 255))
        elif color == 0:
            for gray in row:
                rgba.extend((gray, gray, gray, 0 if gray == transparent_gray else 255))
        elif color == 4:
            for x in range(0, stride, 2):
                rgba.extend((row[x], row[x], row[x], row[x + 1]))
        else:
            for index in row:
                if index >= len(palette):
                    fail("palette index out of range")
                rgba.extend((*palette[index], palette_alpha[index] if index < len(palette_alpha) else 255))
        previous = row
    if len(rgba) != width * height * 4:
        fail("decoded byte count mismatch")
    return width, height, bytes(rgba), {"bit_depth": 8, "color_type": color, "interlace": 0}


def cache_jars() -> list[str]:
    """Bound the traversal itself, not just the number of returned matches."""
    pending = [(CACHE, 0)]
    result = []
    directories = entries = 0
    while pending:
        relative, depth = pending.pop()
        directories += 1
        if directories > LIMITS["cache_directories"]:
            raise ExportError("Cache directory limit exceeded")
        directory = project_path(relative)
        children = []
        with os.scandir(directory) as iterator:
            for entry in iterator:
                entries += 1
                if entries > LIMITS["cache_entries"]:
                    raise ExportError("Cache entry limit exceeded")
                children.append(entry)
        for entry in sorted(children, key=lambda item: item.name):
            child = relative + "/" + entry.name
            if entry.is_symlink():
                raise ExportError("Symlink in fixed cache search: " + child)
            if entry.is_dir(follow_symlinks=False):
                if depth >= LIMITS["cache_depth"]:
                    raise ExportError("Cache depth limit exceeded")
                pending.append((child, depth + 1))
            elif directory.name == "26.3" and entry.name.endswith(".jar") and entry.is_file(follow_symlinks=False):
                project_path(child)
                result.append(child)
                if len(result) > LIMITS["jar_files"]:
                    raise ExportError("Cache JAR count limit exceeded")
    if not any(path.endswith("-sources.jar") for path in result):
        raise ExportError("No fixed-version 26.3 sources JAR found beneath " + CACHE)
    return sorted(result)


def zip_directory_bounds(handle, size: int, label: str) -> int:
    """Reject oversized central directories before ZipFile allocates them."""
    if size < 22:
        raise ExportError("Truncated JAR: " + label)
    handle.seek(max(0, size - 65557))
    tail = handle.read(min(size, 65557))
    position = tail.rfind(b"PK\x05\x06")
    while position >= 0:
        if position + 22 <= len(tail):
            fields = struct.unpack_from("<4s4H2IH", tail, position)
            _, disk, directory_disk, disk_count, count, directory_size, directory_offset, comment = fields
            if position + 22 + comment == len(tail):
                if disk or directory_disk or disk_count != count:
                    raise ExportError("Multidisk JAR unsupported: " + label)
                if (count == 65535 or directory_size == 0xffffffff or
                        directory_offset == 0xffffffff):
                    raise ExportError("ZIP64 JAR unsupported: " + label)
                if count > LIMITS["zip_entries"] or directory_size > LIMITS["zip_directory_bytes"]:
                    raise ExportError("JAR directory limits exceeded: " + label)
                end_offset = size - len(tail) + position
                if directory_offset + directory_size > end_offset:
                    raise ExportError("Invalid JAR directory bounds: " + label)
                return count
        position = tail.rfind(b"PK\x05\x06", 0, position)
    raise ExportError("Missing valid JAR directory record: " + label)


def extract_references() -> tuple[dict[str, dict], list[dict], list[str]]:
    references = {}
    jars = []
    jar_bytes = selected_bytes = 0
    for relative in cache_jars():
        with project_path(relative).open("rb") as handle:
            before = os.fstat(handle.fileno())
            if before.st_size > LIMITS["jar_file_bytes"]:
                raise ExportError("JAR file size limit exceeded: " + relative)
            jar_bytes += before.st_size
            if jar_bytes > LIMITS["total_jar_bytes"]:
                raise ExportError("Total JAR input size limit exceeded")
            count = zip_directory_bounds(handle, before.st_size, relative)
            handle.seek(0)
            hasher = hashlib.sha256()
            read_size = 0
            while True:
                part = handle.read(1024 * 1024)
                if not part:
                    break
                read_size += len(part)
                if read_size > LIMITS["jar_file_bytes"] or read_size > before.st_size:
                    raise ExportError("JAR grew during read: " + relative)
                hasher.update(part)
            if read_size != before.st_size:
                raise ExportError("JAR changed during read: " + relative)
            jar_hash = hasher.hexdigest()
            handle.seek(0)
            with zipfile.ZipFile(handle) as archive:
                infos = archive.infolist()
                if len(infos) != count:
                    raise ExportError("JAR entry count mismatch: " + relative)
                for info in infos:
                    name = info.filename
                    basename = name.rsplit("/", 1)[-1]
                    if relative.endswith("-sources.jar") and name.startswith("net/minecraft/") and basename in JAVA:
                        key = basename
                        output = OUTPUT + "/reference/" + basename
                    elif name in OPTIONAL_SHADERS:
                        key = name
                        output = OUTPUT + "/reference/shaders/core/" + basename
                    else:
                        continue
                    if ("\\" in name or any(part in ("", ".", "..") for part in name.split("/")) or
                            info.is_dir() or ((info.external_attr >> 16) & 0o170000) == 0o120000):
                        raise ExportError("Unsafe selected JAR entry: " + relative + ":" + name)
                    if (info.flag_bits & 1 or info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) or
                            info.file_size > LIMITS["reference_file_bytes"] or
                            info.compress_size > LIMITS["jar_file_bytes"] or
                            info.file_size > max(1, info.compress_size) * 1000):
                        raise ExportError("Unsupported or oversized selected JAR entry: " + name)
                    with archive.open(info) as member:
                        data = member.read(LIMITS["reference_file_bytes"] + 1)
                    if len(data) != info.file_size:
                        raise ExportError("JAR entry byte count mismatch: " + name)
                    # ZipFile checks the selected entry CRC when the full member is read.
                    if (zlib.crc32(data) & 0xffffffff) != info.CRC:
                        raise ExportError("JAR entry CRC mismatch: " + name)
                    selected_bytes += len(data)
                    if selected_bytes > LIMITS["reference_read_bytes"]:
                        raise ExportError("Selected JAR read budget exceeded")
                    provenance = {
                        "jar": relative, "jar_sha256": jar_hash, "entry": name,
                        "sha256": sha256(data), "bytes": len(data), "crc32": "%08x" % info.CRC,
                    }
                    if key in references:
                        if references[key]["data"] != data:
                            raise ExportError("Conflicting full vanilla sources for " + key)
                        references[key]["sources"].append(provenance)
                    else:
                        references[key] = {"data": data, "output": output, "sources": [provenance]}
            after = os.fstat(handle.fileno())
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise ExportError("JAR changed during extraction: " + relative)
            jars.append({"path": relative, "sha256": jar_hash, "bytes": read_size, "entries": count})
    missing = [name for name in JAVA if name not in references]
    if missing:
        raise ExportError("Missing required full vanilla Java sources: " + ", ".join(missing))
    if sum(len(value["data"]) for value in references.values()) > LIMITS["total_reference_bytes"]:
        raise ExportError("Total reference output size limit exceeded")
    return references, jars, [name for name in OPTIONAL_SHADERS if name not in references]


def write_output(relative: str, data: bytes) -> None:
    destination = project_path(relative)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = project_path(relative + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(destination)


def main() -> int:
    if len(sys.argv) != 1:
        raise ExportError("No arguments accepted; all inputs and outputs are fixed")
    generator = read_bounded(GENERATOR, 128 * 1024)
    texture_inputs = []
    images = []
    input_bytes = decoded_bytes = 0
    paths = (TEXTURES + "/sun.png",) + tuple(TEXTURES + "/moon/" + name + ".png" for name in PHASES)
    for index, relative in enumerate(paths):
        data = read_bounded(relative, LIMITS["png_file_bytes"])
        input_bytes += len(data)
        if input_bytes > LIMITS["total_png_bytes"]:
            raise ExportError("Total PNG input size limit exceeded")
        width, height, rgba, png = png_rgba(data, relative)
        decoded_bytes += len(rgba)
        if decoded_bytes > LIMITS["total_rgba_bytes"]:
            raise ExportError("Total decoded texture size limit exceeded")
        if index > 1 and (width, height) != images[1][:2]:
            raise ExportError("Moon phases have unequal dimensions; scaling is forbidden")
        images.append((width, height, rgba))
        texture_inputs.append({
            "path": relative, "sha256": sha256(data), "bytes": len(data),
            "width": width, "height": height, "rgba_sha256": sha256(rgba),
            "rgba_bytes": len(rgba), "png": png,
            "role": "sun" if index == 0 else "moon",
            "phase": None if index == 0 else PHASES[index - 1],
            "layer": None if index == 0 else index - 1,
        })
    sun_width, sun_height, sun_rgba = images[0]
    moon_width, moon_height, _ = images[1]
    header = struct.pack("<8s5I", MAGIC, sun_width, sun_height, moon_width, moon_height, len(PHASES))
    celestial = header + b"".join(image[2] for image in images)
    references, jars, missing_optional = extract_references()
    binary_path = OUTPUT + "/celestial.dwc"
    outputs = {binary_path: celestial}
    reference_manifest = []
    for key, value in sorted(references.items()):
        outputs[value["output"]] = value["data"]
        reference_manifest.append({
            "name": key, "output": value["output"], "complete": True,
            "bytes": len(value["data"]), "sha256": sha256(value["data"]),
            "sources": value["sources"],
        })
    moon_offset = len(header) + len(sun_rgba)
    moon_layer_bytes = moon_width * moon_height * 4
    manifest = {
        "schema": 1, "generator": {
            "path": GENERATOR, "sha256": sha256(generator), "bytes": len(generator)},
        "limits": LIMITS, "textures": texture_inputs, "source_jars": jars,
        "format": {
            "magic_hex": MAGIC.hex(), "header_bytes": len(header), "integer_encoding": "little-endian u32",
            "header_fields": ["sunWidth", "sunHeight", "moonWidth", "moonHeight", "phaseCount"],
            "sun_width": sun_width, "sun_height": sun_height, "sun_offset": len(header),
            "moon_width": moon_width, "moon_height": moon_height, "phase_count": len(PHASES),
            "pixels": "RGBA8, straight alpha, rows top to bottom, columns left to right",
            "conversion": "Exact 8-bit samples; no resizing, color management, premultiplication or redraw",
            "moon_layers": [
                {"layer": index, "phase": phase, "offset": moon_offset + index * moon_layer_bytes,
                 "bytes": moon_layer_bytes, "source": paths[index + 1],
                 "sha256": sha256(images[index + 1][2])}
                for index, phase in enumerate(PHASES)
            ],
        },
        "reference_policy": {
            "cache_root": CACHE, "version_directory": "26.3", "required_java": list(JAVA),
            "java_archives": "*-sources.jar", "optional_shader_archives": "*.jar",
            "duplicates": "Identical full contents are deduplicated with all provenance retained; conflicts fail",
            "content": "Full original entry bytes; no partial snippets or text normalization",
        },
        "references": reference_manifest,
        "missing_optional": missing_optional,
        "outputs": [
            {"path": path, "bytes": len(data), "sha256": sha256(data)}
            for path, data in sorted(outputs.items())
        ],
        "hash_scope": "Exact generator, PNG, JAR, extracted entry and output bytes; manifest is not self-hashed",
    }
    encoded = (json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    if len(encoded) > LIMITS["manifest_bytes"]:
        raise ExportError("Manifest size limit exceeded")
    if len(encoded) + sum(len(data) for data in outputs.values()) > LIMITS["total_output_bytes"]:
        raise ExportError("Total output size limit exceeded")
    manifest_path = OUTPUT + "/celestial-manifest.json"
    for path in (*outputs, manifest_path):
        project_path(path)
        project_path(path + ".tmp")
    for path, data in outputs.items():
        write_output(path, data)
    write_output(manifest_path, encoded)
    print("%s: 9 textures, %d complete references, %d missing optional shaders" %
          (manifest_path, len(references), len(missing_optional)))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ExportError, OSError, ValueError, zipfile.BadZipFile, zlib.error) as error:
        print("visual celestial export: " + str(error), file=sys.stderr)
        raise SystemExit(1)
