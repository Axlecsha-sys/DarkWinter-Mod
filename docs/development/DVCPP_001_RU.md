# DVCPP-001: проверенная пакетная геометрия

Текущий этап укрепляет существующий эксперимент с финальной записью вершин. Он не заменяет модельный сборщик и не доказывает ускорение всей игры. Публичный default остаётся VANILLA.

## Подробные таймеры

На каждом enqueue раньше выполнялась пара nanoTime, в том числе вокруг копирования 128 bytes Indigo. Теперь component clocks включаются только startup property `darkwinter.mesh.detailedTiming=true`. По умолчанию они исключены из пути упаковки; полное compile wall измеряется по-прежнему. Недоступные компоненты записываются как -1/UNAVAILABLE. Флаг присутствует в report и metadata/configHash.

Проверено сохранение byte/layout, native calls, fallback и ownership. Примитивный VertexConsumer hook не заменялся без доказательства выигрыша; отдельный Indigo hook сохранён. Сложная схема прямой native staging/promotion не включена: полный packet prototype оказался медленнее компактного Java writer на выбранных размерах.

## Фактические проверки

- Standalone blockMeshVerify: 640 template/Indigo packets, физические guards, sequential/concurrent byte oracle; PASS.
- Private staging experiment: 40405 assertions, включая повторное использование изменяемого Indigo emitter, retire между enqueue/flush, Java repair и четыре независимых worker; PASS. Его microbenchmark исключает model/light/reserve/GPU и не считается FPS.
- Четыре настоящих Vulkan-клиента в порядке OFF→ON→ON→OFF. Восемь кампаний, 480 измеренных компиляций, по 80 на каждый writer и состояние таймеров; с контролем/прогревом — 704 replay-компиляции.
- Совпали записанные hashes пяти Java-файлов, DLL, исходного level.dat и вершин четырёх выбранных секций. Draw/visibility/block entities и GPU upload readiness корректны. На секцию 9472 грани; в каждой кампании CPP выполнил 200 native batches без fallback.

Медианы полного CPU-side compile wall, мс:

| Подробные таймеры | VANILLA | JAVA_PACKED | CPP_PACKED |
| --- | ---: | ---: | ---: |
| OFF | 5,1452 | 4,8880 | 4,9497 |
| ON | 4,9558 | 5,2902 | 5,2950 |

В объединённой выборке упаковочные пути без подробных часов дешевле. Разброс между JVM/кампаниями сопоставим с разницей; отдельные сравнения меняют направление. Универсальный процент ускорения не заявляется. C++ при OFF не превосходит Java, поэтому автоматического включения CPP_PACKED нет.

Windows CPU counter имел шаг 15,625 мс; его нули и медианы для отдельных compile непригодны. Используется wall time. GPU upload, hashing и первоначальное создание test buffer pack исключены из timed compile. Настоящие frame intervals не записывались; cap=140 не доказывает frame pacing.

Полный captured build identity и фактические heap/GC flags отсутствуют в этих raw reports; подтверждены перечисленные source/DLL/input hashes и единый fixture. Текущий workspace hash не подставлялся вместо отсутствовавшего captured identity.

Локальное evidence: build/dvcpp001-direct-staging/actual-section-abba-summary.json, verify-report.json, benchmark-report.json и build/run/dvcpp001Timing-*/dvcpp001-evidence/timing.json. Тесты и raw evidence не публикуются.

## Что остаётся

Доказать выигрыш полной сборки и задержки появления геометрии, а также совместимость за пределами выбранных сцен. Client AO/light sampling отличается от распространения серверного света DVCPP-004; эти расходы нельзя смешивать.

## Профиль настоящих работников Vulkan

Дополнительно записан JFR реальной асинхронной сборки с priority NONE, без replay-перехвата обычных задач. Две сцены, 32 волны allChanged на сцену, четыре целевые секции: все 256 целевых компиляций завершились. В записи 6543 compile scopes, незавершённых/ошибочных scopes и JFR DataLoss нет. Отдельные replay-кампании сохранили точное совпадение геометрии VANILLA/JAVA_PACKED/CPP_PACKED.

Внутри подтверждённых ordinary worker scopes получены 295 Java samples для каменного checkerboard и 660 для смешанной сцены с tint/листвой/оградой. Inclusive samples AO — 157/271, computeFace — 136/239, lightCoords — 85/118. Эти стеки вложены друг в друга, поэтому числа нельзя складывать или выдавать за доли CPU. Exclusive samples финального writer — только 1/7. На этой записи первоочередная цель находится до упаковки вершин.

Частый листовой RegularImmutableMap.get встречается в 25/179 samples; проверенные стеки ведут к ClientLevel.getUncachedNoiseBiome и registry lookup. Но allChanged очищает tint caches: это профиль холодных данных. Холодный tint сам по себе не означает отсутствующий biome chunk. В четырёх тёплых/холодных контрольных матрицах все 640 настоящих worker-компиляций завершились с нулём fallback-вызовов и registry lookups. Простой cache этого getter не является доказанным ускорением обычной сборки и в production не включён.

Private cache-прототип проверен отдельно: два владельца ClientLevel, повторные resource reload, 125 крайних координат в каждом режиме, повтор после неудачного lookup, 12 окон по 100000 прогретых getter-вызовов без registry lookup, совпадение tint и четырёх replay-кампаний VANILLA/JAVA/CPP. При настоящем временном drop соседнего чанка native LevelReader/tint API выполнил 155 registry lookups в control и ноль с cache, сохранив canonical Holder и tint bits. Чанк восстановлен штатным серверным packet handler до рендера. Этот контролируемый API-case не считается worker или performance результатом отсутствующего чанка. Прототип изолирован в private tests, основной JAR его не содержит.

Профиль сохранил actual source identity c3ad9bcec63832981a410ab41a60a094005bcb9d19cac263b5a26c56727b7c5e, ресурсный hash, DLL, seed, Java 25.0.4.1/G1/heap4185915392, Vulkan1.4.359/AMD 26.10.1, размер 1280×720, FOV70, AO и biome blend2. Execution/NativeMethodSample запрошены с периодом 1 мс; 11 native samples учитываются отдельно. Это диагностическая запись, а не FPS A/B.

Private evidence: build/run/dvcpp001Profile-20261101-192810-172/dvcpp001-profile/report.json и соседний JFR. Сохранённая идентичность относится к записанной сборке и не заменяется hash текущего workspace.
