# DVCPP-004: прототип распространения блочного света

Реализован и проверен C++17 kernel ограниченного increase-prefix. Он остаётся отдельным экспериментом: производственный light engine его не загружает. Полный путь текущей границы данных проигрывает Java, поэтому включение в игровые миры отклонено. Это не завершённая замена освещения.

## Проверенные реализации и выбор

Исследован [Moonrise mc/26.3](https://github.com/Tuinity/Moonrise/tree/bc1c65e1a716aed5aeeb511add2e8430a784c5d2), commit bc1c65e1a716aed5aeeb511add2e8430a784c5d2, GPLv3: StarLightEngine, BlockStarLightEngine, SkyStarLightEngine и SWMRNibbleArray. Его очереди работают вместе с владением чанками и light sections; перенос одного цикла не переносит этих гарантий.

Исторический [Starlight Fabric](https://github.com/PaperMC/Starlight/tree/cca03d62da48e876ac79196bad16864e8a96bbeb), LGPLv3, относится к 1.20.4. [Объяснение автора](https://gist.github.com/Spottedleaf/6cc1acdd03a9b7ac34699bf5e8f1b85c) описывает уже принятые vanilla идеи и связь Starlight с chunk ownership. Старые графики не используются как baseline 26.3. [LightBench](https://github.com/Spottedleaf/lightbench/tree/35693014f3181473c7b5d16bb74e39bd47b1dc78), LGPLv3/1.20-rc1, полезен для идей сценариев; [Luanti](https://github.com/luanti-org/luanti/blob/9c424c44ff523ebce076367e52222ac4d71e94c1/src/voxelalgorithms.cpp), LGPL-2.1-or-later, имеет другую игровую семантику.

Внешний мод не добавлен. Новый kernel получает численные массивы и воспроизводит выбранные правила фактически установленного Minecraft 26.3; исходники Mojang, извлечённые для локального анализа, не публикуются.

## Контракт прототипа

[Исходники и сборка](../../experiments/light-prefix/README.md): ABI1, 64-bit pointers, header56 bytes, queue/write/result16 bytes. Ограничены dimensions, объём, шаги и output capacities. Входы неизменяемые, spans непересекающиеся; отрицательный результат не меняет outputs.

Kernel обрабатывает целые FIFO entries, сохраняет stale/emission правила, направление распространения и порядок записей. Сложная shape, неизвестная граница или недостаточная ёмкость останавливают его до следующего целого entry. Возвращается полный оставшийся FIFO. Skylight, decreases, COW storage, publication и серверные PRE/POST остаются у Java.

Приватный Java wrapper проверяет реальные FFM scopes, доступ потока, lifetime, alignment, размеры и indirect payload scopes. Он не входит в игровой JAR. Нативная C API требует действительных доступных pointers; сама C++-функция не может проверить произвольный адрес.

## Корректность

Настоящий Vulkan SDK-клиент сравнил kernel с установленным BlockLightEngine и storage на detached immutable LightChunkGetter:

- 33 сценария: emission/stale/duplicates/direction masks, разные opacity, сложные shapes, отрицательные границы, known absent sections, capacities, independent concurrent engines;
- 9201 assertions, 3805 реально обработанных native entries, 20 физических FFM/native-negative guards;
- точные ordered writes, processed count и FIFO; native COW, affected sections, исходный Java tail, окончательная публикация, lazy storage и callbacks.

Проверки повторно прошли на артефакте из публичной задачи lightPrefixBuildNative: SHA-256 7d7cbdde27a3806cdce63b04f9852ac4863a4a4a38c1627ccaff840bb882b4ee. Receipt связывает source hashes, проверенный Zig0.14.1, flags и DLL. Сборка нового бинарника требует своей проверки; один sourceHash её не заменяет.

## Полная стоимость

Четыре подготовленные сцены, по четыре прогревочные и 32 измеренные пары, порядок AB/BA чередуется. Реальная Java increase drain сравнивается с capture 110592 cells/очереди, transfer/preflight, FFM/kernel, validated readback, cleanup, storage apply и заменой FIFO. Fixture setup, hashing, private canaries/ordered-write recording и Java tail/publication исключены из обоих timed spans. Проверки результата выполнены отдельно после каждой пары. Это CPU-side wall time внутри одного Vulkan SDK-процесса, не FPS или профиль реального сервера.

| Бюджет / фактически обработано | Java prefix, мс | Полный C++ путь, мс | Capture, мс |
| --- | ---: | ---: | ---: |
| 1 / 1 | 0,00205 | 1,06870 | 0,92910 |
| 64 / 64 | 0,00760 | 1,10465 | 0,93350 |
| 512 / 512 | 0,05880 | 1,58400 | 1,15095 |
| 4096 / 3303 | 0,26275 | 1,72915 | 0,95015 |

На самой тяжёлой сцене FFM+kernel занимают 0,19925 мс, но полный путь дороже Java приблизительно в 6,58 раза. Ускорение одного ядра не окупает текущую границу данных. Самый маленький бюджет особенно невыгоден. Полный путь проиграл во всех 128 парах; даже учётный остаток после вычитания capture не дал побед. Это разбор измеренных этапов, не испытание новой реализации без capture. Одного устранения снимка для доказательства выигрыша недостаточно. Эти числа относятся к подготовленному fixture и не означают, что любой C++ light engine будет медленнее.

Основное evidence: build/run/dvCpp004Light-vulkan-20261101-083259-003/dvcpp004-evidence/report.json и build/light-prefix/bin/build-receipt.json. Локальные tests/evidence и DLL не входят в репозиторий или JAR.

## Проверка Java-альтернативы

Отдельно испытано объединение повторных уведомлений affected sections без переноса light state в C++. Прототип запоминает 27-битный footprint последнего центра; повтор допускается пропустить только после успешного native callback. Изменения очереди, COW, publication, skylight и уровни света не подменяются. Функциональная матрица прошла 59 проверок и 3633 assertions.

Для измерения подготовлены отдельные JVM VANILLA→COALESCED→COALESCED→VANILLA. В control отсутствуют candidate fields/handlers и private mode switch. Совпали captured source/build identity, конфигурация, все входы, FIFO, COW, ordered callbacks и результаты. В четырёх запусках — 512 измерений, 2422656 элементов очереди и 6912 публикаций.

Медианы полного runLightUpdates, мкс:

| Сцена | VANILLA1 | COALESCED1 | COALESCED2 | VANILLA2 |
| --- | ---: | ---: | ---: | ---: |
| Один внутренний источник | 296,60 | 282,95 | 259,80 | 293,90 |
| Четыре внутренних источника | 1230,80 | 1211,55 | 1070,95 | 1207,00 |
| Непрозрачная плоскость | 374,70 | 399,95 | 355,30 | 368,85 |
| Источник на углу секций | 317,85 | 320,20 | 285,10 | 315,55 |

В двух сценах направление улучшения повторяется, в двух меняется. У объединённой выборки плоскости медиана даже немного хуже: 372,40→373,05 мкс. Поэтому общей подтверждённой пользы нет; прототип остаётся private и не регистрируется в production. Это CPU wall detached-стенда, не FPS/TPS и не завершённый DV-010/DVCPP-004. Evidence: build/dv010-light-evidence/independent-abba-audit.json; исходные отчёты сохраняют captured source identity 92f3fae700d8503d81e4e3acae2d1d40442e3ed331c91a83416571b8e1fe80f9.

## Владение и связь с визуальной системой

Перед production нужны другая стоимость получения данных и доказанный протокол input generation/cancellation. Владение световой очередью не запрещает серверу менять BlockState. Volatile PalettedContainer.Data не отслеживает in-place изменения storage; простая copy без epoch не доказывает согласованный снимок. Нельзя досрочно публиковать свет или завершать POST_UPDATE/lightCorrect при оставшихся очередях. Live source removal, concurrent edits, unload/reload и серверные POST futures пока не проверены; skylight/decrease native kernels отсутствуют.

При выполнении этих условий результат должен идти через штатные DataLayer COW, light packets и UV2/lightmap в существующий terrain renderer. AstronomicalLightmapMixin сохраняет block light и меняет skyFactor. Солнце, shadow/volume, weather, haze и film продолжают использовать текущую цепочку и согласованные DV-030 входы SceneFrame416. Солнечные/погодные коэффициенты не записываются в постоянные игровые массивы света.

Скалярное распространение уровней 0–15 сохраняет освещение мира. Визуальное развитие существующих shaders/post/fog/weather — следующий связанный этап после успешной production-интеграции, не отдельный shader pack. Сейчас это условие не выполнено, художественные изменения не заявляются.


## Размер нативного кэша световых секций: Java-эксперимент отклонён

Отдельно проверено увеличение FIFO-кэша DataLayerStorageMap с двух до четырёх секций.
Изменены согласованно обе длины массивов, предел поиска, начало сдвига и предел очистки;
штатные правила null, явной инвалидации, COW, удаления источников, skylight и публикации сохранены.
В control width-mixin полностью исключён до transformation. Это Java-эксперимент внутри
существующего light engine, а не ускорение C++ kernel.

На detached actual Block/Sky engine стенде выполнены четыре отдельные JVM 2→4→4→2:
52 функциональных случая, 768 полных измерений runLightUpdates и 10976 независимых проверок.
Все 12 парных медиан улучшились; суммарное время конечного шестисценарного корпуса снизилось
на 10,49% и 14,65%. Но corner p99 (фактически максимум при N=32) вырос на 61,51% и 55,84%;
не все mean/tail показатели улучшились. Эти числа не являются ускорением FPS или распределением
нагрузки реального мира. Evidence: build/dv010-layer-cache-audit-20261102/verdict.md,
audit.json SHA-256 fed500c0d7931e8b5f04b86e142da7f246b71a1bc87456b8c0372a653b6b4e2c.

Следующая серия проверила фактическую генерацию новых чанков на Vulkan: четыре свежих JVM
2→4→4→2 с одинаковым дополнительным observer и выключенным SectionLightCache AO/vertex-кэшем.
Каждый маршрут составил 213,361796 м и подтвердил 507 genuine новых координат/успешных TERRAIN
futures. Actual runtime width подтверждена startup property, физическими массивами, actual API,
0/4 merged handlers и реальными map shapes. Production configHash не содержит этот частный
внешний treatment; для сравнения использованы отдельные supplemental input/startup/runtime/final
receipts с идентичным overlay source/class identity.

| Изменение 2→4 | Первая пара A→B | Повторная пара D→C |
| --- | ---: | ---: |
| Среднее время кадра | +2,52% | +1,38% |
| p99 времени кадра | +64,92% | +1,39% |
| Среднее худшего 1% кадров | +55,22% | +10,17% |
| Среднее время серверного тика | +4,44% | +7,09% |
| Среднее GPU-время | +0,06% | +0,14% |

У объединённой выборки среднее время кадра выросло с 2,47331 до 2,52185 мс,
p99 — с 4,1803 до 5,3810 мс; 1% low снизился со 143,61 до 118,15 FPS.
Обе пары ухудшили основные frame/server показатели. Причина ухудшения этой серией не доказана.
Default4 отклонён: production сохраняет нативные два слота. Положительный microbench
не стал полезной оптимизацией живой генерации.

Macro evidence: build/dv010-layer-cache-macro-audit-20261102/verdict.md;
audit.json SHA-256 7abd159a9a5eab0c22e84a2dc773af74da59dbff388f139cbe8d73e239334c62.
Исходные owned runs под build/run:
- A2: dvcpp001GenerationCache-OFF-first-20261102-223021-357
- B4: dvcpp001GenerationCache-OFF-first-20261102-223333-201
- C4: dvcpp001GenerationCache-OFF-repeat-20261102-223852-299
- D2: dvcpp001GenerationCache-OFF-repeat-20261102-224204-766

Корректность живого width4 проверена отдельно от macro: прежние 12 световых изменений
и переходы сохранений A/B/A завершились, новая геометрия загружена во всех 12 случаях.
Supplemental observer подтвердил 9 server LOAD и 3 client level change. На сервере updating map
принадлежит light worker: observer читал только final ссылки/длины массивов и volatile опубликованную
map с отключённым кэшем; mutable updating contents не читались и не изменялись.
Evidence: build/run/dvcpp001CacheLive-20261102-224525-342/cache-live-evidence/report.json,
SHA-256 60bd02f391d118652e399fa837ead1b5bbd939d3c78a88cc45ae6e80dd866268;
supplemental final SHA-256 fa238f0ec9ef29e74f474b8c0e79eac696441ada886d2c7195bdbd82edbd0db1.

Функциональный PASS не отменяет отрицательную performance-проверку. Production draft и его
отдельный default/disable smoke оставлены SOURCE_ONLY и не применялись/не запускались.
DVCPP-004 остаётся незавершённой production-задачей; связь с визуальной системой не объявляется
готовой на основании этого Java-эксперимента.
