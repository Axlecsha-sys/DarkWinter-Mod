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

Один активный захват ограничен 60 секундами и 65536 образцами на канал. Используются фиксированные primitive arrays. Образцы сверх лимита учитываются как dropped, а не заменяют старые данные. Завершение создаёт неизменяемый snapshot; сериализация и атомарная запись выполняются после остановки, в фоне. Максимальный экспорт — 16 MiB.

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
