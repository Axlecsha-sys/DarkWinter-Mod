# Кеш признака снежного биома и задержка обновления блоков

## Причина

После переноса пересборки секций в native workers исчез обязательный тяжёлый вызов на render thread, но прежний mesh оставался видимым до завершения компиляции. Диагностический прогон разделил enqueue, worker, SectionCompiler, dedup, staging и принятую mesh publication: основное время занимал SectionCompiler, около 144–150 мс. Очередь и staging не объясняли это ожидание.

JFR показал повторный расчёт цвета биома из TestSnowTerrainModels и TestSnowCrust. Пять предикатных ColorResolver возвращали 0xFFFFFF для разрешённого биома. При включённом biome blending ClientLevel.calculateBlockTint возвращает ARGB.color(r,g,b), поэтому сплошной белый становится 0xFFFFFFFF, то есть -1. BlockTintCache использует -1 как пустую ячейку: каждое обращение снова вычисляло окрестность биома. При blend radius 0 raw 0xFFFFFF с этим sentinel не совпадает; этот случай проверен отдельно.

## Изменение

SnowBiomePresentation предоставляет один зарегистрированный TERRAIN_MEMBERSHIP resolver для грунта, снега, полублоков, корней и елей. Истинный маркер — 0xFEFEFE; все потребители, включая снежную корку, сравнивают его через matchesTerrainTint. Маркер используется только как предикат и не записывается в цвет вершин.

Для N выборок и k разрешённых биомов floor(254*k/N)==254 тогда и только тогда, когда k=N. Это та же граница допуска, что у прежнего белого признака. Сохраняются native cache invalidation при загрузке чанков и clearTintCaches, публикация immutable набора биомов при смене клиентского мира и очистка при disconnect. Мир и снимки секций не сохраняются в новом кеше.

-Ddarkwinter.render.snowMaskCache=false возвращает белый маркер для диагностики. Shared resolver остаётся общим и в этом контроле: это изоляция sentinel collision, а не двоичный запуск прежних пяти resolver. По умолчанию исправление включено. Worker rebuild остаётся включённым; journal durability, серверная симуляция и GPU ownership не менялись.

## Проверка native кеша

tests/block-break-frame/cpu/run_snow_membership.py компилирует текущий production helper и использует настоящие BlockTintCache и ARGB. Оба режима прошли по 79 433 assertions: повторные белые промахи, кешируемый новый признак, raw radius-0 white, false/black, все смешанные k/N для N=1…225, отрицательные координаты, разные высоты, invalidateForChunk/invalidateAll и параллельные обращения. Receipt: build/snow-membership-cpu/receipt.json. Игра и GPU в этой проверке не создаются.

## Проверка настоящих игровых действий

Minecraft 26.3, Fabric, Java 25, Vulkan, RX 7600 XT. Независимые копии одного закреплённого darkwinter_test, 1280×720, RD/SD 6, VSync OFF, лимит 140 FPS, OIT ON. Только QA JVM: ZGC 4 GiB maximum / 3 GiB soft; обычный профиль 8/4 GiB не изменён. Движение часов, случайные тики и спавн выключены в копиях; scheduled block/fluid callbacks сохраняются. Погода фиксирована clear, ветер 0. ON включает weather, precipitation, visuals и atmosphere вместе; фактические masks 0/58 проверяются.

На режим — восемь реальных разрушений и восемь реальных установок через key bindings, prediction packets и native server handlers. Проверяются cumulative ACK, authoritative AIR/STONE, ровно одна native EDIT journal operation на действие, fresh task/mesh identity, готовые vertex/index GPU slices и восстановление исходной geometry signature. Первое обновление геометрии наблюдается в native frame-log независимо от ожидания ACK. Это не timestamp физической мыши, pixel readback или scanout.

Средние времена в мс:

| Режим | Компиляция до → после | Разрушение: send → mesh до → после | Установка: send → mesh до → после |
| --- | --- | --- | --- |
| День OFF | 150,01 → 8,73 | 161,55 → 20,88 | 161,78 → 20,46 |
| День ON | 149,34 → 8,45 | 160,04 → 20,77 | 159,31 → 21,06 |
| Ночь OFF | 147,87 → 8,61 | 158,32 → 20,85 | 161,63 → 21,19 |
| Ночь ON | 147,40 → 8,28 | 158,32 → 20,87 | 157,91 → 23,19 |

В обоих JVM: 32 разрушения и 32 установки, ноль compileSync в потоке кадра, все четыре режима PASS, потерянных/незавершённых trace events нет. Наибольшая наблюдаемая новая задержка установки — 38,09 мс. Source world, production sources/resources и основной graphics config защищены SHA до/после.

Контроль: build/run/blockBreakFrame-candidate-20261105-180057-618.
Исправление: build/run/blockBreakFrame-candidate-20261105-180351-170.
Оба имени candidate относятся к включённому worker rebuild; snowMaskCache в client.json различает белый и исправленный маркер.

## Границы результата

Это сравнение задержки конкретных действий на одном закреплённом стенде. sceneHash остаётся unverified, поэтому результаты не объявляются строгим FPS A/B или общим ускорением всех сцен. Геометрические сигнатуры/загрузка срезов проверены; отдельного побайтового сравнения всех vertex colors и визуальной приёмки всех моделей не было.

В NIGHT_OFF исправленного прогона остался отдельный кадр 52,61 мс в SETTLE, примерно через две секунды после последней публикации. В этом кадре нет sync/async submissions, очередь пуста, свободны шесть буферов; в ближайшем интервале нет edit/compile events. Его причина не установлена, полное устранение всех микрофризов не заявляется.

Новая приоритетная очередь, дополнительный worker, упрощение геометрии, перенос этой проверки в C++ и ослабление WAL не потребовались. Частичная перестройка произвольного section mesh и сценарий непрерывного высокочастотного burst остаются отдельными направлениями.
