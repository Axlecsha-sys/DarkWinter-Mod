# DV-032: воспроизводимые проверки производительности

## Использование

Сбор выключен по умолчанию. В загруженном мире доступны локальные клиентские команды:

```text
/dwperf start 15
/dwperf stop
```

Продолжительность — 2–60 секунд. По истечении времени или по команде stop запись сохраняется в performance-reports внутри каталога запущенного клиента. Серверу ничего не отправляется; изменения игровых правил, FPS limit, VSync или JVM flags команда не выполняет. Ручной отчёт имеет непроверенный sceneHash и предназначен для диагностики; автоматическое A/B-сравнение требует проверенной сцены.

Публичный API: PerformanceClient.start/stop/export и общие PerformanceCapture/PerformanceComparison. Автоматический локальный стенд расположен в исключённых из публикации tests/dv032-client и tools/gradle/development.gradle. Он использует тот же production collector. Обычная сборка и команды не зависят от этих каталогов.

## Что измеряется

| Канал | Значение |
| --- | --- |
| frameInterval | Интервал между реально отрисованными кадрами в native logFrameDuration, включая pacing, present и клиентские тики. Пропущенные ветви renderFrame не считаются кадрами. |
| renderWall | Монотонное wall time вокруг GameRenderer.render с маркерами наблюдателя; это не чистое CPU busy time. |
| clientTickWall | Полный host span Minecraft.tick; отдельно от GameRenderer.render и ThreadMXBean CPU duration. |
| renderCpu | ThreadMXBean CPU time того же render scope, только когда счётчик способен его разрешить. |
| renderThreadCpuCapture | CPU time всего render thread за захват, включая client ticks, наблюдатель и presentation calls; не отдельный render scope. |
| gpuRender | Разность GPU timestamps вокруг команд GameRenderer.render. Не включает DXGI presentation, compositor или всю загрузку GPU. |
| serverTickWall | Wall span MinecraftServer.tickServer(BooleanSupplier), без внешнего waitUntilNextTick. |
| serverTickInterval | Интервалы начала реально продвинувшихся обычных серверных тиков. |
| sectionCompileQueue | Нативные queued/free-buffer counters; пустая очередь не означает готовую геометрию. |

CPU и GPU durations не выводятся друг из друга. На проверенной Windows ThreadMXBean меняется крупными шагами около 15.625 мс, что не позволяет измерять отдельные короткие render scopes. Такой канал получает UNAVAILABLE с причиной, без средних значений и процентилей. Capture-wide CPU counters сохраняются отдельно. Нулевые/отсутствующие GPU результаты также не превращаются в 0 мс.

Графические запросы принадлежат render thread: восемь пар, выборка каждого восьмого render scope, максимум две проверки готовности за poll. Занятые слоты не перезаписываются. Нет glFinish, device idle, fence wait или блокирующей timestamp calibration. После stop/смены мира/завершения клиента пулы закрываются; незавершённый хвост учитывается отдельно. При выходе из приложения незаконченный захват закрывается без автоматического экспорта файла.

F10 тестового острова включает отдельную панель TestingClient. Её периодические CPU/log запросы перенесены в фон после подтверждения задержек в JFR; это не переключатель /dwperf. [Исправление F10, native before/after и остаточные задержки](DV_032_F10_RU.md).

## Ограничения и сопоставимость

Один активный захват ограничен 60 секундами и 65536 образцами на канал. Используются фиксированные primitive arrays. Образцы сверх лимита учитываются как dropped, а не заменяют старые данные. Завершение создаёт неизменяемый snapshot; сериализация и атомарная запись выполняются после остановки, в фоне. Максимальный экспорт — 64 MiB.

Сохраняются хэши сцены, конфигурации и production source/assets/build configuration; реальные backend/GPU/driver/JVM/GC, viewport, эффективный FPS limit, inactivity policy, present mode и tick rate. Абсолютные пользовательские пути, профили, пароли и содержимое мира не включаются. Фактический DXGI/backend surface path и GPU/native memory отмечены как ненаблюдаемые.

generatePerformanceIdentity создаёт darkwinter-performance-build.json. Хэш не зависит от каталога checkout, нормализует разделители путей и CRLF в текстовых исходниках; private tests/tools в него не входят. Это source identity, не SHA готового JAR.

PerformanceComparison проверяет schema, одинаковые sampling limits, обязательные metadata, достаточную выборку, invalid/dropped samples и изменения условий. Смена viewport, FPS cap, камеры или мира инвалидирует соответствующий capture. Разрешаемая ось сравнения задаётся явно: backend либо hardware; sourceHash может отличаться между версиями кода. Разные сцены и настройки нельзя случайно объявить ускорением.

COMPARABLE означает возможность описательного сравнения, а не доказанное ускорение или статистическую значимость. p50/p95/p99 рассчитываются nearest-rank. 1% low определяется через среднее ceil(N×0.01) самых длинных frame intervals и его обратную величину; для других каналов это не FPS.

Observed tick-start rate вычисляется из интервалов, эквивалентно (N−1)/(lastStart−firstStart), а не N/длительность захвата. Frozen, sprint, stepping, pause и непродвинувшиеся ticks исключают обычное TPS-сравнение. SDK/GameTest отчёты должны сохранять свой harness и не используются для wall-clock TPS.

Heap/nonheap и MXBean GC/buffer-pool counters снимаются на границах захвата, а не каждый кадр. Collection time — приблизительное накопленное время сборки всего JVM, не распределение stop-the-world пауз. Direct/mapped pools не показывают всю native память, RSS или VRAM. Интегрированные клиент и сервер разделяют JVM counters.

## Проверено

- 220 детерминированных core assertions: статистика, остановка между потоками, limits, clock wrap, недоступность, испорченные отчёты, SDK/frozen и сравнение.
- Обычные native Loom clients, без Fabric GameTest scheduling: OpenGL и Vulkan × OIT OFF/ON × собственные visuals OFF/ON.
- На каждую комбинацию — две записи по 5 секунд после прогрева, 1280×720, RD8, фиксированная камера, загруженная геометрия, неизменный scene hash.
- Сервер работает: по 100 обычных тиков в каждой записи, примерно 20 стартов в секунду. Это проверка живого стенда, не AI/redstone stress test.
- OpenGL: 8 captures, 56234 frame intervals, 800 ticks, 7033 GPU samples.
- Vulkan: 8 captures, 46683 frame intervals, 800 ticks, 5840 GPU samples.
- Все восемь пар повторов COMPARABLE; смешивание разных OIT-конфигураций отклонено.
- 16 собственных query pools закрыты; invalid GPU samples и переполнение query ring в итоговых прогонах отсутствуют.
- В native fixture установлен MINIMIZED inactivity policy и проверен фактический uncapped limit 260. AFK cap 30 FPS не подменяет результаты постэффектов.
- Проверены выгруженные JSON/PNG и фактический размер изображений; экспорт выполняется вне measured intervals.

Оборудование проверки: Windows, Ryzen 5 8400F, Radeon RX 7600 XT, Java 25. Остальные GPU/драйверы/ОС не проверены; их поддержка не выводится из этой матрицы. Короткие записи проверяют измерительную систему и совместимость, но не заменяют длительные performance runs.

Первая версия измерительного контура завершена. Расширение аппаратной матрицы, отдельные stress-сцены и JFR pause analysis остаются дальнейшими проверками. Производительность игры этим пунктом сама по себе не увеличивается.

Опорные API: [GC counters](https://docs.oracle.com/en/java/javase/25/docs/api/java.management/java/lang/management/GarbageCollectorMXBean.html), [buffer-pool estimates](https://docs.oracle.com/en/java/javase/25/docs/api/java.management/java/lang/management/BufferPoolMXBean.html), [thread CPU time](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.management/com/sun/management/ThreadMXBean.html).

## Общая временная шкала

К существующему отчёту schema=1 добавлена `timeline.schema=1`. `epochSystemNanos` и временные колонки используют один домен System.nanoTime; signed timestamps, включая −1 и 0, допустимы. Отрицательный offset обозначает начало до capture. Вычитание сохраняет elapsed semantics при wrap; отсутствие endpoints задаётся null и кодом validity, а не числовым sentinel.

Новые API `frameAt`, `renderAt`, `tickAt`, `clientTickAt` и `queueAt` принимают фактические границы или точку наблюдения. Frame/render/server/client host spans имеют start/end offsets. Интервал серверных стартов отдельно помечен derived cadence: предыдущий старт получен из supplied current start и interval. Старые frame/render/tick/gpu/queue API и series сохранены; legacy-строки остаются observed/unanchored. `clientTickWall` измеряет полный Minecraft.tick, включая работу за пределами GameRenderer.render.

CPU duration не получает host endpoints: сохранены ссылка на соответствующую renderWall-строку и render ID, когда они доступны. GPU duration остаётся временем графических команд; timeline хранит лишь момент доставки readback и прежний frame ID. GPU start/end не восстанавливаются из readback−duration. ID −1 означает отсутствие связи с eligible render и не является запретом отрицательных timestamps. Очередь имеет самостоятельную точку наблюдения.

Порядок принятых образцов не сортируется. Каждая timeline-колонка совпадает по ordinal с соответствующей legacy series; rejected/dropped счётчики совпадают. Непродвинувшиеся серверные тики по-прежнему исключены. Записываются только завершённые API-вызовы, принятые до finish/expiry. Незавершённый закрывающий тик не выдумывается; in-flight count отмечен NOT_TRACKED. Неизменяемый snapshot не дописывается поздними callbacks, включая их будущие rejected-счётчики.

Колонки предвыделены как primitive arrays: `188×capacity+160` байт, около 11,75 MiB при 65536, без заголовков объектов, старых stores и завершённых prefix copies. Приёмка использует один supplied monotonic clock read; новых объектов на образец, JMX и UTC-clock запросов нет. Finish копирует только принятые префиксы в закрытые immutable primitive-backed списки; boxing откладывается до чтения/фоновой сериализации. Общий freeze доверяет только этим конкретным final типам; обычные mutable List копируются по-прежнему. Максимум timeline — 2621440 скалярных значений; оценка compact JSON вместе со старыми series — менее 49 MB. Лимит фонового экспорта увеличен с 16 до 64 MiB.

Сборка и проверки завершены: прежние 220 core assertions и 577 timeline assertions, включая signed/wrap clocks, bounds, alignment, CPU/GPU domains, invalid anchors, immutability и stop-before-client/server-completion. Это проверка диагностического контура, не устранение микрофризов и не доказательство прироста FPS. Приёмка нативных Vulkan hooks фиксируется отдельно от headless-проверок.

## Приёмка временных меток на Vulkan

Production source identity: `2c2e4a4974521efbe924c2eef94a7c59a34e8575b99ff9f64822d420b3e5bc86`. Восемь native записей, OIT OFF/ON и собственные эффекты OFF/ON с двумя повторами, прошли 95004 независимые проверки. Согласованы 42853 интервала кадров, 800 клиентских и 800 серверных тиков, 5361 GPU-результат. Полные JSON-экспорты совпали с исходными отчётами; начальные render/queue без предыдущего frame interval и просроченные хвосты сохранены с правильными границами.

Отдельный 20-секундный полёт создал 507 новых чанков на маршруте 213,36 м. Проверены 7999 интервалов, по 400 клиентских и серверных тиков, 1000 GPU-результатов и 2404 worker-компиляции. Из них 2390 полностью внутри окна; 14 пограничных не отброшены. Независимый аудит завершил 44342 проверки без несоответствий. Исходный мир и графический snapshot не изменены.

Длительные задержки сохраняются: в статической матрице найден интервал 301,71 мс, в полёте — 70,73 мс. Общая шкала локализует их относительно измеренных областей, но причина не устанавливается одним пересечением. В отдельном JFR-полёте эти пики не повторились; подтверждены ожидание Vulkan semaphore 15,67 мс, DXGI present 15,03 мс и waitFrame 12,62 мс. Наблюдавшийся стек удаления ресурсов внутри submit не означает, что удаление заняло весь submit.

Материалы: `build/dv032-timeline-audit-20261102/`, `build/dv032-generation-timeline-audit-20261102/` и `build/dv032-generation-jfr-audit-20261103/`. Новая версия измерений прошла функциональную проверку; сравнительный прирост FPS, причина пиков 301/70 мс и устранение микрофризов не заявляются.

## Холодный захват и фактический профиль JVM

Дополнительная восьмифазная JFR-запись воспроизвела другой длинный кадр: 360,44 мс. Внутри него зарегистрирована остановка G1 Pause Remark 357,72 мс, включая Class Unloading 353,70 мс. Это причинное объяснение именно данного кадра, а не автоматическое объяснение прежнего 301-мс интервала без JFR. Таймер DXGI waitFrame включал ту же остановку JVM; его длительность здесь не является доказательством задержки графического драйвера.

Штатный локальный runClient уже использует выбранные ZGC/4 ГБ/Vulkan. Отдельная native-матрица эти настройки не наследует и первоначально запускалась с системным G1. Для контроля добавлен отдельный явный тестовый профиль, без изменения основного клиента или публичного default. Фактические ZGC Minor/Major и max heap 4294967296 байт подтверждены аргументами JVM, JFR-конфигурацией, восемью metadata и шестнадцатью boundary-снимками.

Контроль профиля прошёл 46272 независимые проверки. Длинный G1 Remark в нём не повторился, однако остался кадр 62,88 мс, из которых 61,63 мс приходятся на DXGI present. Четыре длинных кадра не пересекались с зарегистрированными GC pause; среди оставшихся стеков также есть SDL_PollEvent. Причины этих ожиданий требуют отдельной проверки. Формального A/B-ускорения G1→ZGC нет: исходные heap-профили и наблюдатели различались.

Материалы: `build/dv032-matrix-jfr-audit-20261103/findings.md`, `build/dv032-matrix-zgc4g-audit-20261103/profile-verdict.md`. Полное устранение микрофризов и production-интеграция C++ освещения этим этапом не объявляются.


