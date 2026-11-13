"""Package exact UI language data from the project's Minecraft build inputs.

Usage: export-ui-languages.py PROJECT_ROOT OUTPUT_DIRECTORY
coreExportNativeUi must first produce build/core/ui-vectors/ui-language-source.json.
No network requests, executable vanilla code, or world files are involved.

The language/text files contain three JSON-quoted UTF-8 strings per line; the
rule catalog has eight. TAB separated, no headers. Locales are sparse and fall
back to en_us at runtime.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

MAX_JSON = 16 * 1024 * 1024
MAX_TABLE = 64 * 1024 * 1024
MAX_FIELD = 64 * 1024
MAX_ROWS = 500_000
CODE = re.compile(r"[a-z0-9_]{2,32}")
SHA1 = re.compile(r"[0-9a-f]{40}")


def read_json(path, limit=MAX_JSON):
    if not path.is_file() or path.stat().st_size > limit:
        raise ValueError("Missing or oversized UI input: " + str(path))
    raw = path.read_bytes()
    value = json.loads(raw.decode("utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError("Expected JSON object: " + str(path))
    return value, raw


def field(value):
    if not isinstance(value, str) or "\0" in value:
        raise ValueError("UI table fields must be strings without NUL")
    if len(value.encode("utf-8")) > MAX_FIELD:
        raise ValueError("UI table field exceeds 64 KiB")
    return json.dumps(value, ensure_ascii=False)


def table(rows, columns=3):
    lines = []
    size = 0
    for count, row in enumerate(rows, 1):
        if count > MAX_ROWS or len(row) != columns:
            raise ValueError("UI table row limit or schema")
        line = "\t".join(field(value) for value in row) + "\n"
        size += len(line.encode("utf-8"))
        if size > MAX_TABLE:
            raise ValueError("UI table exceeds 64 MiB")
        lines.append(line)
    return "".join(lines).encode("utf-8")


def write_changed(path, data):
    if path.is_file() and path.read_bytes() == data:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix="ui-language-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(handle, "wb") as output:
            output.write(data)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def asset_location(root, assets_dir=None, asset_index=None):
    if assets_dir is None or asset_index is None:
        launch = root / ".gradle/loom-cache/launch.cfg"
        lines = [line.strip() for line in launch.read_text(encoding="utf-8-sig").splitlines()]
        def argument(name):
            try:
                at = lines.index(name)
                value = lines[at + 1]
            except (ValueError, IndexError):
                raise ValueError("Loom launch.cfg has no " + name) from None
            if not value:
                raise ValueError("Loom launch.cfg has empty " + name)
            return value
        if assets_dir is None:
            assets_dir = Path(argument("--assetsDir"))
        if asset_index is None:
            asset_index = argument("--assetIndex")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", asset_index):
        raise ValueError("Invalid Loom asset index name")
    return Path(assets_dir), asset_index


def export(root, out, source=None, assets_dir=None, asset_index=None):
    root, out = Path(root).resolve(), Path(out).resolve()
    source = Path(source) if source is not None else root / "build/core/ui-vectors/ui-language-source.json"
    document, source_bytes = read_json(source)
    if document.get("schema") != 1:
        raise ValueError("Unsupported UI language source schema")
    prefixes = document.get("uiPrefixes")
    if not isinstance(prefixes, list) or not prefixes or any(not isinstance(p, str) or not p for p in prefixes):
        raise ValueError("Missing UI translation prefix contract")
    prefixes = tuple(prefixes)
    exact_keys = document.get("uiExactKeys", [])
    if not isinstance(exact_keys, list) or len(exact_keys) > 512:
        raise ValueError("Invalid exact UI translation key contract")
    for key in exact_keys:
        field(key)
        if not key:
            raise ValueError("Empty exact UI translation key")
    if len(set(exact_keys)) != len(exact_keys):
        raise ValueError("Duplicate exact UI translation key")
    exact_keys = frozenset(exact_keys)
    def ui_key(key):
        return isinstance(key, str) and (key.startswith(prefixes) or key in exact_keys)
    migrations = document.get("translationMigrations")
    if not isinstance(migrations, dict):
        raise ValueError("Native translation migration metadata is missing")
    removed, renamed = migrations.get("removed"), migrations.get("renamed")
    if not isinstance(removed, list) or not isinstance(renamed, list) or len(removed) + len(renamed) > 20_000:
        raise ValueError("Invalid native translation migrations")
    for key in removed:
        field(key)
    migration_inputs = set()
    for pair in renamed:
        if not isinstance(pair, list) or len(pair) != 2:
            raise ValueError("Invalid translation rename pair")
        for key in pair:
            field(key)
        if pair[0] in migration_inputs:
            raise ValueError("Duplicate translation rename source")
        migration_inputs.add(pair[0])
    unsupported_format = re.compile(r"%(\d+\$)?[\d.]*[df]")
    def wanted(key):
        return ui_key(key) or (isinstance(key, str) and key in migration_inputs)
    def migrate(values):
        # The metadata applies once, after en_us + selected locale + mod overlays.
        # A missing rename source removes its destination as in the native loader.
        result = dict(values)
        for key in removed:
            result.pop(key, None)
        for old, new in renamed:
            if old in result:
                result[new] = result.pop(old)
            else:
                result.pop(new, None)
        return {key: unsupported_format.sub(lambda match: "%" + (match[1] or "") + "s", value)
                for key, value in result.items() if ui_key(key)}
    def translations(values, description):
        if not isinstance(values, dict):
            raise ValueError("Invalid translation object: " + description)
        result = {}
        for key, value in values.items():
            if wanted(key):
                field(key)
                field(value)
                result[key] = value
        return result

    languages = document.get("languages")
    if not isinstance(languages, list) or not 2 <= len(languages) <= 512:
        raise ValueError("Invalid language metadata count")
    by_code = {}
    for language in languages:
        if not isinstance(language, dict):
            raise ValueError("Invalid language metadata row")
        code = language.get("code")
        if not isinstance(code, str) or not CODE.fullmatch(code) or code in by_code:
            raise ValueError("Invalid or duplicate language code")
        for name in ("name", "region"):
            field(language.get(name))
            if not language[name].strip():
                raise ValueError("Empty language " + name)
        if not isinstance(language.get("bidirectional"), bool):
            raise ValueError("Missing language direction")
        by_code[code] = language
    if "en_us" not in by_code or "ru_ru" not in by_code:
        raise ValueError("Native language metadata is incomplete")
    raw_english = translations(document.get("englishRaw"), "native en_us before migration")
    english = migrate(raw_english)
    if english != translations(document.get("english"), "native en_us after migration"):
        raise ValueError("Python and Java native English migration results differ")
    for key in ("options.language.title", "options.font.title"):
        if key not in english:
            raise ValueError("Missing native English UI key: " + key)

    assets, index_name = asset_location(root, assets_dir, asset_index)
    index, index_bytes = read_json(assets / "indexes" / (index_name + ".json"), MAX_TABLE)
    objects = index.get("objects")
    if not isinstance(objects, dict):
        raise ValueError("Invalid Loom assets index")
    mod_sources = {}
    asset_sources = {}
    mod_patterns = (
        "src/main/resources/assets/*/lang/{code}.json",
        "src/client/resources/assets/*/lang/{code}.json",
        "modules/*/src/main/resources/assets/*/lang/{code}.json",
        "modules/*/src/client/resources/assets/*/lang/{code}.json",
    )

    def mod_locale(code):
        files = set()
        for pattern in mod_patterns:
            files.update(root.glob(pattern.format(code=code)))
        result = {}
        owners = {}
        for path in sorted(files, key=lambda p: p.as_posix()):
            data, raw = read_json(path)
            relative = path.relative_to(root).as_posix()
            mod_sources[relative] = hashlib.sha256(raw).hexdigest()
            for key, value in translations(data, relative).items():
                if key in result and result[key] != value:
                    raise ValueError("Conflicting mod UI key " + key + ": " + owners[key] + " / " + relative)
                result[key], owners[key] = value, relative
        return result

    raw_english.update(mod_locale("en_us"))
    english = migrate(raw_english)
    localized = {"en_us": english}
    missing = []
    for code in sorted(by_code):
        if code == "en_us":
            continue
        virtual = "minecraft/lang/" + code + ".json"
        item = objects.get(virtual)
        if not isinstance(item, dict):
            missing.append(virtual)
            continue
        digest, size = item.get("hash"), item.get("size")
        if not isinstance(digest, str) or not SHA1.fullmatch(digest) or not isinstance(size, int) or not 0 <= size <= MAX_JSON:
            raise ValueError("Invalid language asset index record: " + virtual)
        path = assets / "objects" / digest[:2] / digest
        if not path.is_file():
            missing.append(virtual + " [" + digest + "]")
            continue
        data, raw = read_json(path)
        if len(raw) != size or hashlib.sha1(raw).hexdigest() != digest:
            raise ValueError("Language asset integrity mismatch: " + virtual)
        asset_sources[virtual] = {"sha1": digest, "size": size}
        selected = dict(raw_english)
        selected.update(translations(data, virtual))
        selected.update(mod_locale(code))
        localized[code] = {key: value for key, value in migrate(selected).items() if english.get(key) != value}
    if missing:
        raise ValueError("Native UI export needs cached Loom language assets; missing " + str(len(missing)) + ": " + ", ".join(missing[:12]))

    rules = document.get("gameRules")
    if not isinstance(rules, list) or not 1 <= len(rules) <= 512:
        raise ValueError("Native game-rule metadata is missing; rerun coreExportNativeUi")
    rule_ids = set()
    game_rule_rows = []
    for rule in sorted(rules, key=lambda entry: entry.get("id", "")):
        identifier = rule.get("id")
        category = rule.get("category")
        kind = rule.get("type")
        minimum, maximum = rule.get("min"), rule.get("max")
        default = rule.get("default")
        if not isinstance(identifier, str) or not re.fullmatch(r"(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+", identifier) or identifier in rule_ids:
            raise ValueError("Invalid or duplicate native game-rule ID")
        if not isinstance(category, str) or not re.fullmatch(r"[a-z0-9_./-]+", category):
            raise ValueError("Invalid native game-rule category")
        if type(minimum) is not int or type(maximum) is not int or not -(2**31) <= minimum <= maximum < 2**31:
            raise ValueError("Invalid native game-rule range")
        if kind == "boolean":
            if default not in ("true", "false") or (minimum, maximum) != (0, 1):
                raise ValueError("Invalid Boolean game-rule metadata")
        elif kind == "integer":
            if not isinstance(default, str) or not re.fullmatch(r"-?[0-9]+", default) or not minimum <= int(default) <= maximum:
                raise ValueError("Invalid integer game-rule metadata")
        else:
            raise ValueError("Unsupported native game-rule type")
        label, description = rule.get("labelKey"), rule.get("descriptionKey")
        if label not in english or not isinstance(description, str) or (description and description not in english):
            raise ValueError("Missing game-rule UI translation")
        rule_ids.add(identifier)
        game_rule_rows.append((identifier, category, kind, default, str(minimum), str(maximum), label, description))

    language_rows = [(code, by_code[code]["name"], by_code[code]["region"]) for code in sorted(by_code)]
    text_rows = [(code, key, value) for code in sorted(localized) for key, value in sorted(localized[code].items())]
    language_bytes, text_bytes = table(language_rows), table(text_rows)
    rule_bytes = table(game_rule_rows, columns=8)
    receipt = {
        "schema": 1,
        "completed": True,
        "format": "Three JSON-quoted UTF-8 fields per row, TAB separated, LF, no header",
        "languages": len(language_rows),
        "gameRules": len(game_rule_rows),
        "gameRuleScope": document.get("gameRuleScope", ""),
        "textRows": len(text_rows),
        "englishKeys": len(english),
        "uiExactKeys": sorted(exact_keys),
        "translationKeysByLanguage": {code: len(localized[code]) for code in sorted(localized)},
        "bidirectionalLanguages": [code for code in sorted(by_code) if by_code[code]["bidirectional"]],
        "fallback": "en_us",
        "translationMigrations": {"removed": len(removed), "renamed": len(renamed),
                                  "source": "assets/minecraft/lang/deprecated.json"},
        "sourceSha256": hashlib.sha256(source_bytes).hexdigest(),
        "classpathSources": document.get("sourceSha256", {}),
        "cachedAssetSources": document.get("cachedAssetSources", {}),
        "packArtSources": document.get("packArtSources", {}),
        "assetIndex": {"name": index_name, "sha256": hashlib.sha256(index_bytes).hexdigest()},
        "languageAssets": asset_sources,
        "modSourcesSha256": mod_sources,
        "files": {
            "ui-languages.tsv": {"sha256": hashlib.sha256(language_bytes).hexdigest(), "bytes": len(language_bytes)},
            "ui-text.tsv": {"sha256": hashlib.sha256(text_bytes).hexdigest(), "bytes": len(text_bytes)},
            "ui-game-rules.tsv": {"sha256": hashlib.sha256(rule_bytes).hexdigest(), "bytes": len(rule_bytes)},
        },
    }
    receipt_bytes = (json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")
    # Validate every input before replacing any output. Every file replacement is atomic.
    write_changed(out / "ui-languages.tsv", language_bytes)
    write_changed(out / "ui-text.tsv", text_bytes)
    write_changed(out / "ui-game-rules.tsv", rule_bytes)
    write_changed(out / "ui-language-sources.json", receipt_bytes)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--assets-dir", type=Path)
    parser.add_argument("--asset-index")
    args = parser.parse_args()
    result = export(args.root, args.out, args.source, args.assets_dir, args.asset_index)
    print(json.dumps({key: result[key] for key in ("completed", "languages", "gameRules", "textRows", "englishKeys", "files")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
