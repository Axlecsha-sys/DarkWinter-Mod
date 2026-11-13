"""Generate native UI notices from real repository and packed provenance inputs.

Public entry point: export(root: pathlib.Path, out: pathlib.Path).
Run after the UI art/language packers. No network or game process is involved.
"""
import argparse
import hashlib
from html import escape
import json
import os
from pathlib import Path
import re
import struct
import tempfile
from urllib.parse import urlsplit


def read(path, limit=2*1024*1024):
    if not path.is_file() or path.stat().st_size > limit:
        raise ValueError("Missing or oversized notice input: " + str(path))
    return path.read_bytes()


def decoded(raw):
    value = raw.decode("utf-8-sig")
    if "\0" in value:
        raise ValueError("NUL in notice text")
    return value


def replace(path, raw):
    if path.is_file() and path.read_bytes() == raw:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix="notice-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(raw)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def font_names(raw):
    """Extract bounded SFNT name metadata; no font package is required."""
    if len(raw) < 12 or raw[:4] not in (b"\x00\x01\x00\x00", b"OTTO", b"true"):
        raise ValueError("Unsupported notice font container")
    count = struct.unpack_from(">H", raw, 4)[0]
    if count > 256 or 12 + 16*count > len(raw):
        raise ValueError("Invalid SFNT directory")
    table = None
    for index in range(count):
        tag, checksum, offset, size = struct.unpack_from(">4sIII", raw, 12+16*index)
        if offset+size > len(raw):
            raise ValueError("SFNT table outside font")
        if tag == b"name":
            if table is not None:
                raise ValueError("Duplicate SFNT name table")
            table = raw[offset:offset+size]
    if table is None or len(table) < 6:
        raise ValueError("Font name metadata is absent")
    format_id, count, strings = struct.unpack_from(">HHH", table)
    if format_id not in (0, 1) or count > 4096 or 6+12*count > len(table):
        raise ValueError("Invalid font name records")
    names = {0:"copyright", 1:"family", 4:"fullName", 8:"manufacturer",
             9:"designer", 13:"license", 14:"licenseUrl"}
    result = {}
    for index in range(count):
        platform, encoding, language, name, size, offset = struct.unpack_from(
            ">HHHHHH", table, 6+12*index)
        if name not in names:
            continue
        if strings+offset+size > len(table):
            raise ValueError("Font name leaves its table")
        value = table[strings+offset:strings+offset+size]
        if platform in (0, 3):
            value = value.decode("utf-16-be")
        elif platform == 1 and encoding == 0:
            value = value.decode("mac_roman")
        else:
            continue
        value = value.replace("\0", "").strip()
        if value:
            result.setdefault(names[name], set()).add(value)
    return {key:sorted(value) for key,value in sorted(result.items())}


def link(url, label=None):
    if not isinstance(url, str):
        return ""
    parsed = urlsplit(url)
    shown = escape(label if label is not None else url)
    if parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username:
        return shown
    return '<a href="%s" rel="noreferrer">%s</a>' % (escape(url, quote=True), shown)


CSS = """
@font-face{font-family:DW;src:url('ui_regular.ttf') format('truetype');font-display:swap}
:root{color-scheme:dark;--bg:#08121b;--panel:#12232f;--ink:#dce8eb;--muted:#aac0ca;--line:#466675;--link:#a9dae7}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:18px/1.6 DW,system-ui,sans-serif}
header,main,footer{max-width:1000px;margin:auto;padding:28px 32px}header{border-bottom:1px solid var(--line)}
.kicker{letter-spacing:.16em;color:var(--muted);font-size:.8rem;text-transform:uppercase}h1{font-size:2.35rem;line-height:1.15;margin:.35em 0}
h2{font-size:1.45rem;line-height:1.3;margin:1.7em 0 .65em}h3{font-size:1.1rem}p{max-width:78ch}
nav{display:flex;gap:20px;flex-wrap:wrap;margin-top:24px}a{color:var(--link);text-underline-offset:.22em}
a:focus-visible,summary:focus-visible{outline:3px solid #d09a59;outline-offset:6px}nav a[aria-current=page]{color:var(--ink);font-weight:bold}
.card{background:var(--panel);padding:1px 24px 18px;border:1px solid var(--line);margin:24px 0}
table{border-collapse:collapse;width:100%;font-size:.95rem}th,td{padding:11px;text-align:left;vertical-align:top;border-bottom:1px solid var(--line)}
th,dt{color:var(--muted)}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.6 ui-monospace,monospace;background:var(--panel);padding:18px;border:1px solid var(--line)}
code{font-size:.85em;overflow-wrap:anywhere}summary{cursor:pointer;color:var(--link);padding:10px 0}dt{font-weight:bold}dd{margin:0 0 12px;overflow-wrap:anywhere}
footer{color:var(--muted);font-size:.85rem;border-top:1px solid var(--line)}.skip{position:absolute;left:12px;top:-100px}.skip:focus{top:12px;background:var(--panel);padding:12px}
@media(max-width:600px){header,main,footer{padding:20px}h1{font-size:1.9rem}.card{padding:1px 16px 12px}table{font-size:.85rem}th,td{padding:7px}}
@media print{:root{color-scheme:light;--bg:#fff;--panel:#f4f5f6;--ink:#111;--muted:#333;--line:#aaa;--link:#123c55}header,main,footer{max-width:none}nav,.skip{display:none}}
"""


def page(title, filename, body, project):
    links = [("attribution.html","Авторы и источники"), ("licenses.html","Лицензии"),
             ("accessibility.html","Доступность")]
    nav = "".join('<a href="%s"%s>%s</a>' % (name, ' aria-current="page"' if name == filename else "", text)
                  for name,text in links)
    return ('<!doctype html>\n<html lang="ru"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>%s · DarkWinter</title><style>%s</style></head><body>'
            '<a class="skip" href="#content">К содержимому</a>'
            '<header><div class="kicker">DarkWinter · C++</div><h1>%s</h1>'
            '<p>%s · версия проекта %s</p><nav aria-label="Документы">%s</nav></header>'
            '<main id="content">%s</main><footer>'
            'Документы собраны из файлов проекта. Точные источники и контрольные суммы: '
            '<a href="notices-sources.json">реестр источников</a>.'
            '</footer></body></html>\n') % (escape(title),CSS,escape(title),escape(project["name"]),
                                           escape(project["version"]),nav,body)


def export(root, out):
    root, out = Path(root).resolve(), Path(out).resolve()
    sources = {}
    def source(relative, limit=2*1024*1024):
        path = (root/relative).resolve()
        if not path.is_relative_to(root):
            raise ValueError("Notice source leaves project")
        raw = read(path, limit)
        sources[relative] = {"sha256":hashlib.sha256(raw).hexdigest(),"bytes":len(raw)}
        return raw
    metadata = json.loads(decoded(source("src/main/resources/fabric.mod.json")))
    properties = decoded(source("gradle.properties"))
    version = str(metadata.get("version", ""))
    if version == "$" + "{version}":
        match = re.search(r"(?m)^\s*version\s*=\s*(\S+)\s*$", properties)
        if not match:
            raise ValueError("Unresolved project version")
        version = match[1]
    authors = metadata.get("authors", [])
    if not isinstance(authors, list) or not authors:
        raise ValueError("Project authors missing from manifest")
    author_names = []
    for author in authors:
        name = author if isinstance(author,str) else author.get("name") if isinstance(author,dict) else None
        if not isinstance(name,str) or not name.strip():
            raise ValueError("Invalid author metadata")
        author_names.append(name)
    license_id = metadata.get("license")
    if isinstance(license_id,list):
        license_id = ", ".join(license_id)
    if not isinstance(license_id,str) or not license_id:
        raise ValueError("Project license missing from manifest")
    project = {"id":metadata.get("id",""),"name":str(metadata.get("name","DarkWinter")),
               "version":version,"authors":author_names,"license":license_id}
    project_license = source("LICENSE")
    font_license = source("src/client/resources/assets/darkwinter/font/license.txt")
    builder = decoded(source("tools/ui/build_ui_symbols.py"))
    if "distributed under the project's license" not in builder:
        raise ValueError("Review changed UI Symbols license statement")
    for relative in ("native/darkwinter-client/src/ui.cpp",
                     "native/darkwinter-client/src/ui_catalog.hpp",
                     "native/darkwinter-client/src/world_main.cpp"):
        source(relative)
    fonts = {}
    for name in ("ui_regular.ttf","ui_symbols.ttf"):
        raw = source("src/client/resources/assets/darkwinter/font/"+name,32*1024*1024)
        fonts[name] = {"sha256":hashlib.sha256(raw).hexdigest(),"bytes":len(raw),"embeddedNames":font_names(raw)}
    provenance, manifests = {}, {}
    for name in ("ui-sources.json","ui-language-sources.json"):
        raw = read(out/name)
        data = json.loads(decoded(raw))
        if not isinstance(data,dict):
            raise ValueError("Invalid UI provenance")
        manifests[name] = data
        provenance[name] = {"sha256":hashlib.sha256(raw).hexdigest(),"bytes":len(raw)}
    language, artwork = manifests["ui-language-sources.json"], manifests["ui-sources.json"]
    if language.get("completed") is not True:
        raise ValueError("Language provenance incomplete")
    for name in ("ui-languages.tsv","ui-text.tsv","ui-game-rules.tsv"):
        expected = language.get("files",{}).get(name,{})
        raw = read(out/name,64*1024*1024)
        if expected.get("sha256") != hashlib.sha256(raw).hexdigest() or expected.get("bytes") != len(raw):
            raise ValueError("Packed UI table differs from provenance: "+name)
    art_sources = artwork.get("sourceSha256",{})
    if not isinstance(art_sources,dict) or any(not isinstance(k,str) or not re.fullmatch("[0-9a-f]{64}",str(v))
                                              for k,v in art_sources.items()):
        raise ValueError("Invalid artwork source hashes")
    contacts = []
    for key,label in (("homepage","Страница проекта"),("sources","Исходники"),("issues","Сообщить об ошибке")):
        value = metadata.get("contact",{}).get(key)
        if value:
            contacts.append(link(value,label))
    font_sections = []
    for name, record in fonts.items():
        info = record["embeddedNames"]
        details = "".join("<dt>%s</dt><dd>%s</dd>" % (escape(caption),"<br>".join(escape(v) for v in info.get(key,[])))
                          for key,caption in (("family","Семейство"),("fullName","Название"),("copyright","Copyright"),
                                               ("designer","Дизайнер"),("manufacturer","Производитель"),("license","Встроенное уведомление"))
                          if info.get(key))
        urls = " · ".join(link(url) for url in info.get("licenseUrl",[]))
        font_sections.append('<section class="card"><h3>%s</h3><dl>%s</dl>%s</section>'
                             % (escape(name),details,"<p>"+urls+"</p>" if urls else ""))
    source_discrepancies = []
    font_document_note = ""
    embedded_license = " ".join(fonts["ui_regular.ttf"]["embeddedNames"].get("license", []))
    if "apache" in embedded_license.lower() and "SIL OPEN FONT LICENSE" in decoded(font_license).upper():
        source_discrepancies.append({
            "font": "ui_regular.ttf",
            "embeddedLicense": embedded_license,
            "adjacentDocument": "src/client/resources/assets/darkwinter/font/license.txt",
            "adjacentDocumentLicense": "SIL Open Font License 1.1",
            "resolution": "Both source notices are preserved; this exporter does not resolve the difference",
        })
        font_document_note = (
            "<p><strong>Различие в исходных документах.</strong> Встроенное уведомление ui_regular.ttf "
            "указывает Apache License 2.0, а соседний font/license.txt содержит SIL Open Font License 1.1. "
            "Оба источника приведены отдельно и сохранены без изменения; этот реестр не выбирает одну лицензию вместо другой.</p>"
        )
    art_rows = "".join("<tr><td><code>%s</code></td><td><code>%s</code></td></tr>"
                       % (escape(name.replace("\\","/")),digest) for name,digest in sorted(art_sources.items()))
    attribution = (
        "<h2>Проект</h2><p>Автор, указанный в манифесте: <strong>%s</strong>.</p><p>%s</p>"
        "<p>Объявленная лицензия проекта: <strong>%s</strong>.</p>"
        "<h2>Шрифты</h2><p>Ниже приведены сведения из реальных файлов шрифтов. Основной шрифт сопровождается "
        "SIL Open Font License. Стрелки DarkWinter UI Symbols созданы в проекте; их исходник указывает лицензию проекта.</p>%s"
        "<h2>Переводы и ресурсы интерфейса</h2><p>В эту сборку включены метаданные %d языков, %d строк переводов "
        "и %d игровых правил. Базовые тексты взяты из ресурсов Minecraft, дополнены переводами DarkWinter и обработаны "
        "таблицей переименований текущей версии. При отсутствии перевода используется en_us.</p>"
        "<p>Изображения меню и значки DarkWinter взяты из ресурсов проекта. Иконки наборов Minecraft отмечены отдельно "
        "в реестре происхождения. Перечень источников не меняет лицензии и авторство исходных материалов.</p>"
        "<details><summary>Изображения и SVG: источники и SHA-256</summary><table><thead><tr><th>Источник</th>"
        "<th>SHA-256</th></tr></thead><tbody>%s</tbody></table></details><p><a href=\"ui-sources.json\">Реестр оформления</a>"
        " · <a href=\"ui-language-sources.json\">Реестр переводов</a></p>"
    ) % (", ".join(escape(a) for a in author_names)," · ".join(contacts),escape(license_id),font_document_note+"".join(font_sections),
         int(language["languages"]),int(language["textRows"]),int(language["gameRules"]),art_rows)
    licenses = (
        "<p>Здесь сохранены тексты документов из репозитория и сведения самих шрифтов. Объявленная в манифесте "
        "лицензия проекта: <strong>%s</strong>.</p><h2>Лицензия проекта</h2>"
        "<p><a href=\"notices/project-LICENSE.txt\">Оригинальный текст LICENSE</a></p>"
        "<details><summary>Прочитать полный текст</summary><pre>%s</pre></details>"
        "<h2>Основной шрифт</h2>%s<p><a href=\"notices/font-license.txt\">Оригинальный font/license.txt</a></p>"
        "<details><summary>Прочитать полный текст</summary><pre>%s</pre></details>"
        "<h2>Символы DarkWinter</h2><p>tools/ui/build_ui_symbols.py создаёт собственные контуры стрелок "
        "и указывает распространение под лицензией проекта.</p>%s"
        "<h2>Источники ресурсов</h2><p>Эти уведомления относятся к указанным исходным документам. "
        "Данные Minecraft в <a href=\"ui-language-sources.json\">реестре переводов</a> и "
        "<a href=\"ui-sources.json\">реестре изображений</a> не объявляются собственными материалами DarkWinter. "
        "Этот генератор не добавляет разрешений на чужие материалы.</p>"
    ) % (escape(license_id),escape(decoded(project_license)),font_document_note+font_sections[0],escape(decoded(font_license)),font_sections[1])
    accessibility = """
<h2>Навигация по меню</h2><table><thead><tr><th>Действие</th><th>Клавиши</th></tr></thead><tbody>
<tr><td>Перейти между доступными элементами</td><td>Tab, Shift+Tab; стрелки вверх и вниз</td></tr>
<tr><td>Нажать выбранную кнопку</td><td>Enter</td></tr><tr><td>Вернуться на предыдущую страницу</td><td>Esc</td></tr>
<tr><td>Изменить выбранный параметр</td><td>Стрелки влево и вправо</td></tr>
<tr><td>Прокрутить список</td><td>Колесо мыши, Page Up, Page Down, полоса прокрутки</td></tr></tbody></table>
<p>Привязки игровых действий находятся в «Управление → Назначение клавиш».</p>
<h2>Ввод текста</h2><p>В полях доступны перемещение курсора, выделение, Home/End, Ctrl+A, Ctrl+C, Ctrl+X и Ctrl+V.
Поле пароля не копируется в буфер обмена. В списке языков и ряде длинных списков есть поиск.</p>
<h2>Размер и движение интерфейса</h2><p>Масштаб GUI находится в настройках графики. На странице «Интерфейс» доступны
режим анимации, сила эффектов и дымка меню. Для уменьшения движения выберите сокращённые эффекты или отключите их.</p>
<p>Язык выбирается на странице «Язык» и применяется кнопкой «Готово» или Enter.</p>
<h2>Текущий охват самостоятельной версии</h2><p>В этой C++ сборке встроенная озвучка меню и клавиши диктора пока
не подключены. Высокая контрастность, контрастная рамка блока, эффекты искажения и пульсация тьмы также пока
не управляют нативным изображением. Их значения могут сохраняться в настройках.</p>
<p>Полное соответствие средствам доступности Minecraft ещё не проверено. Покрытие всех алфавитов, сложное расположение
глифов и совместимость со сторонними экранными дикторами требуют отдельной проверки.</p>
<h2>Эта страница</h2><p>Документ открывается локально в обычном браузере. Здесь используются обычные заголовки, ссылки
и таблицы, видимый фокус клавиатуры и масштаб браузера. Скрипты, анимация и сетевые загрузки ресурсов не используются.</p>
"""
    outputs = {
        "attribution.html":page("Авторы и источники","attribution.html",attribution,project).encode("utf-8"),
        "licenses.html":page("Лицензии","licenses.html",licenses,project).encode("utf-8"),
        "accessibility.html":page("Доступность","accessibility.html",accessibility,project).encode("utf-8"),
        "notices/project-LICENSE.txt":project_license, "notices/font-license.txt":font_license,
    }
    receipt = {"schema":1,"completed":True,"project":project,"fonts":fonts,
               "sourceSha256":sources,"packedProvenance":provenance,
               "scope":"Actual manifest, repository LICENSE, font notices and packed UI provenance",
               "sourceDiscrepancies":source_discrepancies,
               "accessibilityAssessment":"Source-grounded description; not a complete runtime audit",
               "outputs":{name:{"sha256":hashlib.sha256(raw).hexdigest(),"bytes":len(raw)} for name,raw in outputs.items()}}
    outputs["notices-sources.json"] = (json.dumps(receipt,ensure_ascii=False,indent=2,sort_keys=True)+"\n").encode("utf-8")
    for name,raw in outputs.items():
        replace(out/name,raw)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root",type=Path);parser.add_argument("out",type=Path)
    args=parser.parse_args()
    result=export(args.root,args.out)
    print(json.dumps({"completed":result["completed"],"files":result["outputs"]},ensure_ascii=False))


if __name__ == "__main__":
    main()
