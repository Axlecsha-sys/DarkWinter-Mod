# WinterKore Engine — WKE

WKE отделён от кода DarkWinter. Текущий приоритет — Fabric-мод Minecraft 26.3 / Java 25 / Windows x86-64 / Vulkan. Самостоятельная игра отложена.

## Текущая цель и статус проверки

Цель текущего этапа: **2560×1440, 144 FPS, максимальные реально реализованные настройки**. Это кадр каждые 6,944 мс. Основная 3D-сцена и итоговый кадр строятся в native 1440p; уменьшение исходного разрешения и генерация промежуточных кадров не входят в эту цель. Тени и объёмные промежуточные targets имеют собственные разрешения, явно заданные профилем графа.

**144 FPS в настоящем лесу пока не достигнуты.** Исходная сборка в существующем DarkWinter Test показала 100,66 FPS стоя, 86,62 при повороте и 79,93 при ходьбе на native 2560×1440 HIGH, RD15/SD17, MIP4/AF8x. Прежние ~142 FPS относятся к небольшой сцене с 24 деревьями и не являются приёмкой этого биома.

Snow v2 сохраняет нынешние модовые скосы и корку, добавляя мелкую толщину, общую поверхность следов/опоры и сохраняемую историю погоды. Независимые проверки: 38 расчётных и 218 660 геометрических. Полная игровая проверка предыдущей итерации прошла; заключительная приёмка фоновой сборки выполняется отдельно. Обычный клиент без GameTest-барьера уже дал действительное сравнение: 82,55→80,68 FPS при ходьбе; 1% low 69,55→51,02. Профиль и начальная поза одинаковы, новая физика меняет последующую траекторию. 47 сборок заняли 148,35 мс на главном потоке для снимков и 1231 мс в worker; p99 клиентского тика 5,53 мс. Это не достижение цели 144 FPS и не общее улучшение плавности. Текущее доказательство и ограничения — в [отчёте Snow v2](../docs/development/WKE_SNOW_V2_20261108_RU.md). Историческая приёмка материалов, AA и звука сохранена в [отчёте сцены](../docs/development/WKE_SCENE_20261108_RU.md); остаточное мерцание растительности не объявлено устранённым.

## Реально работающие границы

| Часть | Контракт и управление | Исполнение и владелец данных |
| --- | --- | --- |
| Мир, тики, модели, видимость, освещение, подготовка геометрии | Minecraft и игровые модули DarkWinter | Minecraft; обычная игровая симуляция не перенесена в C++ core |
| Основной граф мира: небо, местность, сущности, прозрачность | Декларации Minecraft LevelRenderer / FrameGraph | Host планирует ресурсы и проходы; мировые GPU draw-команды записывает WKE |
| Дополнительные тени, дымка, облака, grade, AA и плёнка | Общие WKE shader resources, декларативный WkeEffectsContract и профили | Minecraft-адаптер WkePostGraphExecutor строит/исполняет PostChain через host FrameGraph; GPU-команды проходят через WKE backend |
| Параметры сцены | Общий ABI WkeSceneUniforms; игра задаёт время, климат, камеру и художественные значения | SceneFrame адаптирует входы; host владеет uniform buffers |
| Графические VkPipeline и мировые draw-команды | WKE C++ backend, предварительная проверка draw-пакета | Заимствованные Vulkan device/command buffer; собственность WKE на VkPipeline |
| Окно, device/queue, буферы/изображения, descriptors/layouts, barriers/clear/copy, submission/presentation | Minecraft graphics host | Minecraft; дополнительное окно, device или swapchain не создаются |
| Двумерный интерфейс | Host | Draw-вызовы вне мирового native scope |
| Снежная поверхность и акустические оценки | Независимые Java-политики WKE surface/audio | Host-адаптеры читают/изменяют загруженный мир, рисуют следы и обновляют существующие OpenAL-каналы |
| Прежний самостоятельный C++-клиент | Отдельный host WKE | Собственные окно и граф; полное совпадение всех эффектов двух hosts не заявляется |

Native WORLD-путь охватывает renderLevel (включая небо, сущности, местность, частицы и 3D-предметы/HUD), outline и постэффекты. В этих scopes WKE выбирает конвейеры и выполняет настоящие Vulkan draw-вызовы. Это не вызов второй игры и не только ускоритель упаковки вершин.

Скомпилированные конвейеры могут создаваться native уже при старте, чтобы поддержать заранее сохранённые CompiledRenderPipeline. Их наличие отдельно не доказывает native-отрисовку: статус WKE требует созданных конвейеров и фактических команд. FrameGraph и игровая симуляция остаются у host.

## Каталоги

| Каталог | Реализовано |
| --- | --- |
| core | Независимое C++20-ядро: задания, ревизии, снимки, материалы, занятость и DWV/C ABI. Обычная симуляция мода ещё не перенесена в это ядро. |
| kernels/voxel-faces | Видимость граней и пакетная упаковка BLOCK/Indigo-геометрии. |
| render/java | Общие графические параметры, проверяемый контракт эффектов/профилей, std140 serialization, алгоритмы стабилизации теней и выдержки, параметры spatial AA/TAA; без зависимости от Minecraft. |
| render/resources | Единственный активный источник общих эффектов: шейдеры, шесть post graphs и assets/wke/effects/pipeline_contract.json. Старые shader IDs сохранены для совместимости. |
| surface | Непрерывная толщина снега, физические контакты, хронологическая история осадков/оттепели, ограниченный перенос и CRC-хранение v3 с чтением v2; без host/game классов. |
| audio | Ограниченная модель передачи звука, высокочастотного затухания и отражающего окружения; без владения миром или аудиоустройством. |
| runtime/embedded | Независимый Vulkan backend: borrowed device, владение pipelines, 128-байтный ABI draw-пакетов, предварительная проверка, статистика. |
| runtime/src | Сцена и прежний самостоятельный Windows renderer, GPU-память и pipeline cache; material_mips.hpp — общий CPU-kernel RGBA/mip/alpha coverage. |
| runtime/shaders | Шейдеры прежнего самостоятельного host. |
| gradle | Сборка/упаковка библиотек и совместимость существующих диагностик. |

Java/Fabric-адаптеры находятся **снаружи** независимого ядра: modules/wke/adapter и modules/wke/surface. Minecraft-совместимый material frontend размещён в modules/wke/adapter/resources; общие исполняемые эффекты вынесены в WKE/render/resources. Игровые celestial PNG и регистрация игровых входов остаются в модуле DarkWinter. Пользовательские shader pairs и shadow overrides сохраняют shader/layout/state. Стандартная terrain pair сначала проходит зарегистрированный background-aware fog preprocessor, затем WKE клонирует полный metadata/state. Известная fog pair сохраняет свои стадии; точная stock pair без fog использует wke:core/terrain. Чужая vertex stage со штатной fragment stage не считается stock pair. Общий исполнитель vulkan_world_commands.hpp используется embedded backend и Renderer::drawGeometry самостоятельного host. Это общие команды, а не автоматическое совпадение всех материалов, генераторов, AA и графов двух клиентов.

Код игры, меню, генератор, движение, климат и календарь самостоятельного host остаются в native/darkwinter-client. Старые forwarding-заголовки не дублируют реализации.

## Общие материалы и эффекты

CPU material kernel выполняет alpha-weighted фильтрацию цвета в linear space, обработку прозрачного RGB и сохранение cutout coverage. C ABI из runtime/embedded/material_mips.h подключает его к host-загрузке mip-цепочек через FFM. Шейдерные правила выборки связаны с действительным terrain frontend. Канадский сеттинг, генерация текстур и игровые материалы не становятся зависимостью независимого ядра.

Декларация effects graph хранится в render/resources/assets/wke/effects/pipeline_contract.json. Она задаёт реальные профили LOW/BALANCED/HIGH, порядок проходов, доступные реализации, внешние targets и ABI. WkeEffectsContract проверяет порядок, зависимости и uniform layout фактически загружаемого графа, включая ресурсные overrides. WkePostGraphExecutor исполняет этот граф; VisualRenderer поставляет игровые входы и подключает захват теней.

Контракты данных разделены:

- Scene — 416 байт, прежние offsets и shader block сохранены.
- Temporal resolve — 176 байт: текущая обратная проекция, предыдущая проекция, camera delta, параметры rejection и jitter.
- Spatial AA — 16 байт, самостоятельные Controls.

Цепочка заканчивается **merge → temporal resolve → spatial fallback → raw history → film**. История получает разрешённое изображение до grade/lens/grain; изображение после плёнки не возвращается в AA/history. Temporal adapter владеет двумя full-resolution FP16 color/D32 depth targets, сбрасывает историю при разрывах и восстанавливает её на текущих кадрах. Две ограниченные R8-маски содержат текущие и предыдущие реальные границы прозрачных particle quads: они ограничивают накопление именно на частицах, а не объявляют любую мелкую текстуру движущимся объектом. Маски следуют фактическим матрицам кадров и освобождаются при resize/resource reset/disconnect. Текущий цвет реконструируется в стабильную native сетку, а выбранный depth texel использует собственный ray с явной offset-компенсацией. Это может слегка смягчать мельчайшие детали. Полные motion vectors всех сущностей и растительности не реализованы; проверенные сценарии NPC/частиц не заменяют проверку любых движений.

Низкое/среднее/высокое качество эффектов, AA и плёнка имеют отдельные работающие настройки. Они сохраняют неизвестные и художественные параметры файла. Профиль эффектов не подменяет native Minecraft graphics preset. Поддержка эффектов берётся из общего контракта и существующих ресурсов; `VisualRenderer.effectsStatus()` сообщает проверенный граф, реальные host submissions и состояние истории. Эти счётчики не названы завершёнными GPU-проходами или GPU time.

Прежние копии шейдеров/графов в modules/darkwinter-visuals оставлены для миграции, но исключены из активной упаковки. Производство использует WKE-источники; исходные ShadowGrid/MotionBlurEnvelope также исключены после переключения на общие реализации.

## Снег и звук: реализованная область

Независимый WKE surface хранит толщину в миллиметрах; шаг представления — 6,25 мм, а обновление близкой сетки срабатывает при накопленном изменении 0,5 мм. Сильная метель калибрована на 12,5–25 мм за 6000 активных серверных тиков при snowfall 1–2. Количество видимых частиц не начисляет физический снег повторно. История осадков и температуры сохраняется по страницам; позднее обнаруженная поверхность догоняет прошедшую активную погоду без повторного начисления. Это ленивый пересчёт при наблюдении, а не обход всей незагруженной карты или симуляция закрытой игры.

Minecraft-адаптер предоставляет реальные опоры, авторские скосы/корку, форму и проницаемость укрытий и направление ветра. Ограничены resident cache (256 чанков), обход (64 колонки за тик) и перенос свежего дополнительного снега между соседями. Рост мелкой толщины не переписывает blockstate и не требует полной пересборки чанка каждые 6,25 мм.

Ближняя поверхность использует общий snow atlas и настоящие углубления с формой подошвы, мягкой кромкой и постепенным заполнением. Поддерживается полный SNOW_BLOCK. Отрисовка, контакт и опора используют опубликованные форму, общие граничные высоты и контакты одной сетки. Чистая сборка геометрии вынесена в один ограниченный worker: снимок берётся на главном потоке, готовая GPU-сетка публикуется там же, старый результат остаётся до замены. Epoch/revision и отмена отбрасывают устаревшие результаты после правок, unload и reset.

Коллизия остаётся консервативным адаптером VoxelShape с выборкой общей поверхности под площадью сущности; это не произвольная треугольная физика. Локальное затемнение углубления приближённое, а не точная копия штатного Indigo AO. Вход для будущих колёс/лыж не означает наличие готового транспорта.

Audio adapter использует существующий host OpenAL context и positional channels. Ограниченные геометрические запросы дают transmission/high-frequency attenuation и оценку помещения; при доступном EFX применяются source filters и общий room reverb. При недоступном EFX остаётся positional backend. Чтение мира выполняется на client thread, операции OpenAL — на audio executor. Это подключённая ограниченная акустика, а не полный wave/path tracing или готовая полная звуковая система игры.

## Запуск и проверка в моде

Обычный JAR содержит wke_core.dll, wke_voxel.dll, **wke_render.dll**, SHA-256, engine-manifest.json, Java-адаптер, shader frontend и клиентские mixins. DLL извлекаются в кеш с именем по содержимому; контрольная сумма проверяется до загрузки.

Java 25 для native запускается с:

    --enable-native-access=ALL-UNNAMED

runClient и SDK уже передают этот параметр. Внешний лаунчер должен передать его как аргумент JVM.

Renderer по умолчанию AUTO. На совместимом Windows/Vulkan host с native access он выполняет мировую отрисовку через WKE; при недоступности native остаётся host-путь с причиной в диагностике. Отказ draw-пакета происходит до первой команды, поэтому fallback не дублирует геометрию.

В загруженном мире:

    /wkerender status
    /wkerender native
    /wkerender host
    /wkerender auto

effective=WKE и растущие nativeDrawCalls подтверждают активную запись команд. WORLD включает вложенный TERRAIN; счётчики dispatch описывают запись, а не GPU completion/FPS. HOST переключает выполнение draw-команд; ранее созданные GPU-конвейеры могут оставаться в штатном кеше.

**/dwmesh — отдельная подсистема подготовки чанков.** Её сохранённый VANILLA не выключает WORLD renderer. CPP_PACKED выбирает native-упаковку, сохраняя модели/свет/байты Minecraft. Упакованное wke_core само по себе не переносит игровую симуляцию: оно также доступно диагностическим командам и инструментам.

## Сборка и проверки

Из корня проекта:

    .\gradlew wkeVerifyRendererNative
    .\gradlew wkeRendererVerifyClient
    .\gradlew wkeVerifyPackage build

| Задача | Что проверяет |
| --- | --- |
| visualsDoctor | Общий ресурсный контракт, зависимости шести graphs и ABI Scene/TAA/AA; без GPU-приёмки. |
| wkeVerifyMaterialsNative | Независимый CPU ABI и material mip/coverage kernel; без GPU-приёмки. |
| wkeVerifySurface | Независимая модель толщины, истории погоды, переноса, контактов и хранения v2/v3. |
| wkeVerifySurfaceGeometry | Скосы/корка, мелкое смещение и форма подошвы; без настоящего GPU. |
| wkeSnowV2PinWorld | Закрепляет только нормально закрытый исходный мир с проверкой lock и SHA каждого файла. |
| wkeSnowV2PinArchivedBaselineWorld | Явно закрепляет проверенный архив старого baseline, совместимый с его старым JAR; обе версии затем получают одинаковые копии. |
| wkeSnowV2BaselineNativeBenchmark / wkeSnowV2CandidateNativeBenchmark | Обычный свободный игровой цикл без GameTest-барьера: точные JAR, одинаковые копии закреплённого мира, три 10-секундных окна, без изображений и дополнительных probes. Проверяет ограничения/окно/профиль и сохранность источника. |
| wkeSnowV2BaselineClient / wkeSnowV2CandidateBenchmarkClient | Контролируемый GameTest для диагностики и изображений; его cadence может зависеть от барьера клиента/сервера, поэтому он не является окончательной FPS-приёмкой. |
| wkeSnowV2CandidateSurfaceClient | Скосы/следы/опора, смена native-блока во время pending job с GPU D32 witness, 6000 тиков метели, поздний чанк и save/close/reopen; только одноразовая копия, без FPS-окон. |
| wkeSnowV2CandidateClient | Прежний полный GameTest с диагностическими окнами и той же функциональной приёмкой; FPS проверяется отдельным native benchmark. |
| wkeSnowV2CandidateProbesClient | Отдельные GPU-проверки haze, сброса TAA и cutout shadow; без FPS-приёмки. |
| checkMotionBlurEnvelope | Общий WKE-контроллер выдержки, физическое время и reset; без визуальной приёмки. |
| wkeVerifyRendererNative | C ABI, владение, команды и полный отказ до записи через контролируемые Vulkan callbacks; без настоящего GPU. |
| wkeRendererVerifyClient | Настоящий Minecraft/Vulkan: native pipelines и WORLD draws, независимые Java WORLD/TERRAIN observers, color/depth, NPC, снег, изменение мира, resize/reload/disconnect/re-entry и фактический device-close. |
| wkeSceneVerifyClient | Реальный native 1440p: MAIN mip sampling, FP16 temporal, парные одинаковые мировые точки и Halton phases, NPC/particles, отдельные FPS windows, surface save/reopen и реальные AL bindings. |
| wkeSceneCloudFidelityClient | Дополнительно сравнивает исходный и оптимизированный cloud shader на одинаковых GPU uniform slices одного кадра вне измерительных окон. |
| wkeSceneGpuPassTimingClient | Опциональные настоящие per-pass timestamps; инструментированный запуск не является baseline FPS. |
| renderSettingsVerifyClient | Реальные UI controls качества/AA/MIP/film, persistence и GPU film parity. |
| wkeVerifyClient | Предыдущая независимая проверка C++ упаковки секций; не заменяет renderer SDK. |
| wkeVerify | Нативные контракты, независимая сборка, геометрия, packaged FFM loading и JAR. |
| wkeVerifyPackage | Фактический JAR: три DLL/хеши, shader frontend, mixins и регистрация; приватных renderer SDK-классов нет. |

Результат renderer SDK: build/WKE/latest-renderer-client.json и исходные кадры/raw depth в отдельном build/run/wkeRenderer-*.
Результаты packing/core: build/WKE/latest-client.json и latest-embedded.json. Библиотеки: build/WKE/bin.
Наличие empty getLastDebugMessages в Minecraft 26.3 не считается GPU validation proof. Новая квитанция систем — build/WKE/latest-scene-client.json; фактический JAR и SHA — build/WKE/package-verification.json. Приёмка temporal-стабильности выполнена; целевая частота 144 FPS пока не выдержана во всех измеренных сценариях. Снижение качества ради положительного FPS-вердикта не применялось.

Независимая сборка без Minecraft, Java, Gradle и CMake:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File WKE/build.ps1 -Verify -Renderer

Требуются уже проверенный Zig/Clang 0.14.1 и Vulkan headers. Их пути задаются -Compiler и -VulkanHeaders; -OutputDirectory задаёт результат. Без -Renderer строятся core/kernels. Скачивания отсутствуют.

Альтернативный CMake-путь embedded backend:

    cmake -S WKE -B build/WKE/cmake -DWKE_BUILD_EMBEDDED_RENDERER=ON -DWKE_VULKAN_INCLUDE_DIR="путь/к/include"
    cmake --build build/WKE/cmake --config Release
    ctest --test-dir build/WKE/cmake -C Release --output-on-failure

CMake/Ninja на текущей машине не установлены; этот путь описан, но не заявляется проверенным. Старый WKE_BUILD_WINDOWS_RUNTIME описан отдельно в runtime/README_RU.md.

## Документы

- [Общая сцена, native 1440p, проверенная стабильность и незавершённая цель 144 FPS](../docs/development/WKE_SCENE_20261108_RU.md).
- [Текущее подключение renderer](../docs/development/WKE_RENDERER_20261108_RU.md).
- [MIP, графические настройки и переключатель плёнки](../docs/development/MIP_FILM_20261108_RU.md). Проверка host UI/атласа/grade: .\\gradlew renderSettingsVerifyClient.
- [Согласованные цели](ROADMAP_RU.md).
- [Исторический этап выделения и packing](../docs/development/WKE_INTEGRATION_20261108_RU.md).

Физика предметов, готовый транспорт, полноценный AI, редактор, полная акустическая модель и моддинг из обсуждения остаются целями. Реализованные ограниченные surface/audio адаптеры описаны выше. Path tracing, глобальная offline-симуляция снега и полные motion vectors не заявляются. Общий backend не подменяет реализацию игровых систем.
