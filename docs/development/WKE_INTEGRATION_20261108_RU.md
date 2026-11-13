# WKE: выделение движка и подключение к Fabric-моду

Исторический этап выделения и native packing. Последующее подключение WORLD graphics backend и актуальная сборка описаны в [WKE_RENDERER_20261108_RU.md](WKE_RENDERER_20261108_RU.md). Хеши и ограничения ниже относятся к этому прежнему этапу.

Дата: 08.11.2026 (Asia/Vladivostok). Проект: F:/DarkWinter. Ветка: develop/с++.

## Результат

WinterKore Engine выделен в WKE. Текущий приоритет — мод Minecraft 26.3 / Fabric / Java 25 / Windows x86-64; разработка отдельной игры отложена.

Перенесены реальные исходники, реализации не скопированы в параллельное дерево:
- native/darkwinter-core -> WKE/core;
- experiments/voxel-faces/native -> WKE/kernels/voxel-faces;
- renderer/scene, технические заголовки и VMA translation unit -> WKE/runtime/src;
- исходники шейдеров -> WKE/runtime/shaders.

Для старых инструментов оставлены forwarding-заголовки. Общие SceneColumn и RenderVisualProvider отделяют публикацию данных и визуальные параметры от генератора, климата и календаря игры. Владельцем провайдера является Renderer. Сборочные пути и входы обновлены; сохранения и форматы ABI не менялись.

## Подключение к моду

Обычный JAR содержит wke_core.dll и wke_voxel.dll, их SHA-256, engine-manifest.json и Java-адаптер. Проверка контрольной суммы предшествует загрузке; кеш DLL использует имена по содержимому. Явные диагностические переопределения библиотек сохранены.

Фактический путь интеграции — существующая пакетная упаковка геометрии чанков. Minecraft определяет модели, видимость, освещение и конечный вывод. Дополнительный нативный игровой мир не создаётся. C++ вызывается пакетами; существующие scratch-буферы переиспользуются.

При отсутствии явного mesh-конфига доступны автоматический CPP_PACKED после ABI/byte-parity проверки и проверки native access, либо штатный путь при недоступности WKE. Существующие явные режимы сохраняются. Для внешнего лаунчера native-режим требует --enable-native-access=ALL-UNNAMED. runClient уже передаёт этот параметр.

Полная замена графического устройства и рендера Minecraft этим подключением не выполнена. Перенесённый Windows runtime пока владеет своим окном/Vulkan-устройством. В Scene сохранены совместимость с форматами DarkWinter и host-расширение prepareNative.

## Проверки

| Проверка | Результат |
|---|---|
| wkeVerify + build | PASS, Gradle job ef1097415db34df0924a5333afc503ad, exit 0 |
| Независимый WKE/build.ps1 -Verify | PASS: обе DLL и C++ checks EXE собраны без вызова Java/Minecraft/Gradle/CMake; 306547 native assertions |
| coreVerifyNative | PASS: 306547 native assertions |
| wkeVerifyEmbedded | PASS: 62 проверки ресурсов, хешей, собственного повреждённого кеша, native workers и BLOCK-пакета в новой JVM |
| wkeVerifyMesh | PASS: 640 последовательных/параллельных BLOCK/Indigo-пакетов, независимый byte oracle и проверки границ |
| wkeVerifyVisibility / wkeVerifyFallback | PASS: по 264 сцены |
| wkeVerifyPackage | PASS: проверен фактический JAR, DLL/хеши/адаптер присутствуют; приватных WKE SDK-классов в JAR нет |
| wkeVerifyClient | PASS, job 8daa868c66a14b8e98e88b286f072505, exit 0; Minecraft 26.3, Vulkan |
| coreCompileGame + coreCheckRasterScene | PASS, job 3a14484baa88473f9e6097bec113f3a5, exit 0; перенесённый C++ runtime компилируется, 8 проверок сцены |
| IntelliJ build_project / Java inspections | PASS, ошибок не найдено |
| git diff --check по затронутым исходникам | PASS; только предупреждения Git о нормализации CRLF |

SDK создал только собственный мир под build/run/wkeClient-20261108-075410-431. Пользовательские сохранения не открывались.

Подтверждено в Minecraft:
- библиотеки получены из classpath-ресурсов без путей к старым dev-DLL;
- nativeBatches вырос с 0 до 65, effective=CPP_PACKED;
- геометрия секции [0,6,0] загружена на GPU;
- frozen section сравнен с VANILLA и JAVA_PACKED: byteParity=true, nativeExercised=true;
- все 5 нативных повторов выполнены, nativeBatches=60, fallbackBatches=0;
- после отключения liveBytes/liveRegions/activeCalls/activeFrames/cachedArrayBytes равны 0.

Документы доказательств:
- build/WKE/latest-client.json;
- build/WKE/latest-embedded.json;
- build/WKE/independent/build-receipt.json;
- build/WKE/bin/engine-manifest.json;
- build/run/wkeClient-20261108-075410-431/performance-reports/chunk-mesh-fd91fa0d-6ede-448f-b71a-bf024c63c154.json;
- build/run/wkeClient-20261108-075410-431/screenshots/0000_wke-packaged-native-mesh.png.

SHA-256 проверенных в Minecraft DLL совпадает с текущими упакованными библиотеками:
- wke_core.dll: 4fe91683143f479933b61e55eeb4b21aabd7036567321eded19982f4fcedcf8c;
- wke_voxel.dll: ba0298fca9dd56d8a5caf21e4334f4ab02904a0384bc83d33bd14a8b0a2c0eae.

## Производительность и пределы проверки

Изменение устраняет разрозненные копии/пути сборки и добавляет проверяемый native host. Проверки геометрии подтверждают сохранение выходных данных, а не повышение FPS.

На одной замороженной секции, по 5 измеренным повторам:
- VANILLA: медиана wall 7.4524 мс;
- JAVA_PACKED: 9.8981 мс;
- CPP_PACKED: 7.9207 мс.

В этом образце C++ не превзошёл исходный vanilla-путь. Это локальное время сборки одной секции, не время кадра; перенос результата на FPS или другие сцены не обоснован. Цель 120 FPS не подтверждалась этим этапом.

Независимый PowerShell-путь проверен. Опциональные CMake-цели описаны, но CMake/Ninja на машине не установлены и этот путь не запускался.

Полный старый coreVerifyGame не объявляется прошедшим: ранее известные проблемы старого геометрического oracle/сценария отмены загрузки не входят в проверку интеграции WKE. Рябь растительности, дрожание хвои и снежные швы данным этапом не закрывались.

## Артефакты и продолжение

Мод: build/libs/dark-winter-1.0.1.jar, 83626193 bytes.
SHA-256 JAR: 8a8627a51b7a7e13fdad8c3a90d12899cf77f3d27153c8c1800ade8ae6f46492.

IDE-конфигурации: WKE Build, WKE Verify, WKE Mod Check. Команды и границы — WKE/README_RU.md. Согласованные цели дальнейшего развития — WKE/ROADMAP_RU.md.

Дальше: аудит активного рендера мода, действующие графические настройки и исправление визуальных дефектов. Реалистичные материалы/модели и новая самостоятельная игра остаются последующими этапами.
