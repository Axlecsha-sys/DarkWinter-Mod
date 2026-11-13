"""Inspect/export original Minecraft flat-world UI icon inputs, without a client.

The resolved jars come from Loom launch.cfg, then the English resource digest
is checked against coreExportNativeUi's metadata. No downloads or world access.
Unknown model types are recorded, never replaced with invented cube artwork.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

MAX_ENTRY = 8 * 1024 * 1024
IDENTIFIER = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
PRESETS = {
    "classic_flat": "grass_block", "tunnelers_dream": "stone",
    "water_world": "water_bucket", "overworld": "short_grass",
    "snowy_kingdom": "snow", "bottomless_pit": "feather",
    "desert": "sand", "redstone_ready": "redstone", "the_void": "barrier",
}
LAYER_ITEMS = ("bedrock", "dirt", "grass_block", "stone", "deepslate", "gravel",
               "water_bucket", "snow", "cobblestone", "sandstone", "sand", "lava_bucket")
JAVA_SOURCES = (
    "com/mojang/blaze3d/platform/Lighting.java",
    "net/minecraft/client/gui/render/GuiItemAtlas.java",
    "net/minecraft/client/resources/model/cuboid/ItemTransform.java",
    "net/minecraft/client/resources/model/cuboid/FaceBakery.java",
    "net/minecraft/client/renderer/item/ItemStackRenderState.java",
    "net/minecraft/client/renderer/item/BlockModelWrapper.java",
    "net/minecraft/client/gui/screens/PresetFlatWorldScreen.java",
    "net/minecraft/client/gui/screens/CreateFlatWorldScreen.java",
    "net/minecraft/world/level/levelgen/flat/FlatLevelGeneratorPresets.java",
)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def resource_path(identifier, category, suffix):
    if ":" not in identifier:
        identifier = "minecraft:" + identifier
    if not IDENTIFIER.fullmatch(identifier) or ".." in identifier.split(":", 1)[1].split("/"):
        raise ValueError("Invalid resource identifier: " + identifier)
    namespace, path = identifier.split(":", 1)
    return f"assets/{namespace}/{category}/{path}{suffix}"


class Inputs:
    def __init__(self, root):
        self.root = Path(root).resolve()
        launch = self.root / ".gradle/loom-cache/launch.cfg"
        properties = dict(line.strip().split("=", 1) for line in
                          launch.read_text(encoding="utf-8-sig").splitlines()
                          if "=" in line and line.strip().startswith("fabric.gameJarPath"))
        self.jars = []
        self.source_jars = []
        self.inputs = {}
        self.models = {}
        for key in ("fabric.gameJarPath.client", "fabric.gameJarPath"):
            if key not in properties:
                raise ValueError("Missing resolved Loom jar: " + key)
            path = Path(properties[key]).resolve()
            path.relative_to(self.root)
            self.jars.append((path, zipfile.ZipFile(path)))
            sources = path.with_name(path.stem + "-sources.jar")
            if sources.is_file():
                self.source_jars.append((sources, zipfile.ZipFile(sources)))
        metadata = json.loads((self.root / "build/core/ui-vectors/ui-language-source.json").read_text(encoding="utf-8"))
        expected = metadata["sourceSha256"]["assets/minecraft/lang/en_us.json"]
        if (not isinstance(expected, list) or not expected or
                set(expected) != {sha(self.read("assets/minecraft/lang/en_us.json"))}):
            raise ValueError("Resolved Minecraft jar differs from verified UI language source")

    def close(self):
        for _, archive in self.jars + self.source_jars:
            archive.close()

    def read(self, path, optional=False, source=False):
        copies = []
        for filename, archive in self.source_jars if source else self.jars:
            try:
                entry = archive.getinfo(path)
            except KeyError:
                continue
            if entry.file_size > MAX_ENTRY:
                raise ValueError("Oversized resource: " + path)
            data = archive.read(entry)
            copies.append((filename, data))
        if not copies:
            if optional:
                return None
            raise ValueError("Missing resolved Minecraft resource: " + path)
        if any(data != copies[0][1] for _, data in copies):
            raise ValueError("Conflicting resolved Minecraft resource: " + path)
        data = copies[0][1]
        self.inputs[path] = {"sha256": sha(data), "bytes": len(data),
                             "jars": [p.relative_to(self.root).as_posix() for p, _ in copies]}
        return data

    def document(self, path):
        value = json.loads(self.read(path).decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("Expected resource object: " + path)
        return value

    def model(self, identifier, stack=()):
        if ":" not in identifier:
            identifier = "minecraft:" + identifier
        if identifier in stack or len(stack) > 20:
            raise ValueError("Cyclic or excessive model inheritance: " + identifier)
        if identifier in ("minecraft:builtin/generated", "minecraft:builtin/entity"):
            return {"builtin": identifier, "chain": []}
        if identifier in self.models:
            return self.models[identifier]
        path = resource_path(identifier, "models", ".json")
        child = self.document(path)
        parent = self.model(child["parent"], (*stack, identifier)) if "parent" in child else {}
        merged = dict(parent)
        for key, value in child.items():
            if key in ("textures", "display", "materials"):
                merged[key] = {**parent.get(key, {}), **value}
            elif key != "parent":
                merged[key] = value
        merged["chain"] = [*parent.get("chain", []), {"id": identifier, "document": child}]
        self.models[identifier] = merged
        return merged


def inspect(root, out):
    root, out = Path(root).resolve(), Path(out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    inputs = Inputs(root)
    result = {"schema": 1, "status": "source-inspection", "presets": PRESETS,
              "items": {}, "sourceSha256": inputs.inputs,
              "scope": "Original resolved Minecraft inputs; no rendered icon parity claim"}
    try:
        for name in sorted(set(PRESETS.values()) | set(LAYER_ITEMS)):
            path = resource_path(name, "items", ".json")
            item = inputs.document(path)
            entry = {"item": item}
            model = item.get("model", {})
            if model.get("type") in ("minecraft:model", "model") and isinstance(model.get("model"), str):
                entry["resolvedModel"] = inputs.model(model["model"])
            else:
                entry["unhandledModel"] = model.get("type", "missing")
            result["items"][name] = entry
        for name in JAVA_SOURCES:
            raw = inputs.read(name, optional=True, source=True)
            if raw:
                path = out / "sources" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
        # Shader names changed between Minecraft versions; inventory what this
        # resolved jar actually contains, rather than assuming old shader files.
        shader_names = sorted({n for _, archive in inputs.jars for n in archive.namelist()
                               if n.startswith("assets/minecraft/shaders/") and
                               ("light" in n or "rendertype_item" in n or "entity" in n)})
        result["shaderResources"] = shader_names
        for name in shader_names:
            if "/include/" in name or "rendertype_item" in name or name.endswith("entity.vsh"):
                raw = inputs.read(name)
                path = out / "sources" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
        slot = "assets/minecraft/textures/gui/sprites/container/slot.png"
        raw = inputs.read(slot)
        if raw[:8] != b"\x89PNG\r\n\x1a\n":
            raise ValueError("Minecraft slot resource is not PNG")
        (out / "slot.png").write_bytes(raw)
    finally:
        inputs.close()
    (out / "source-manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Flat UI icon source inspection: " + str(out / "source-manifest.json"))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--inspect-only", action="store_true")
    args = parser.parse_args()
    if not args.inspect_only:
        parser.error("Only --inspect-only is available until source rendering has been verified")
    inspect(args.root, args.out)


if __name__ == "__main__":
    main()
