# Vulkan в DarkWinter

Основная платформа разработки и клиентских проверок — Vulkan. Прежние OpenGL-измерения сохраняются как история; новые OpenGL-прогоны не выполняются.

## Выбор и запуск

VulkanDefaultOptionsMixin задаёт Vulkan начальным значением новых Options, до штатного чтения options.txt. Сохранённый явный выбор загружается обычным кодом. Переключение API требует перезапуска. Текущий локальный профиль также использует Vulkan и остаётся вне Git/JAR.

Выбранный VULKAN теперь использует только VulkanBackend. При ошибке устройства Minecraft показывает штатную причину отказа, без скрытого перехода к OpenGL. Сохранённый Vulkan после нештатного завершения не понижается до DEFAULT. Остальные enum-значения и явные параметры запуска не подменяются.

## Аудит оставшихся OpenGL-путей

Проверены production Java/C++ DarkWinter, Visuals, фактические исходники Minecraft 26.3 и renderer/rendering/client-gametest части Fabric0.163.0. В собственных render/mesh/weather/postprocessing путях прямых LWJGL OpenGL-вызовов не найдено: GpuDevice, CommandEncoder, RenderPass и GpuQueryPool выбирают фактический backend.

В vanilla найдены и исправлены четыре конкретных места:

| Место | Прежнее поведение | Текущее поведение |
| --- | --- | --- |
| NativeLibrariesBootstrap.loadOpenGL | Безусловный prewarm GL.getFunctionProvider до выбора renderer. | Eager preload пропущен. GlBackend при явном выборе сам инициализирует provider штатным loadLibrary. |
| PreferredGraphicsApi.VULKAN | Кандидаты Vulkan, затем OpenGL. | Единственный кандидат — Vulkan. |
| Minecraft unclean startup | Сохранённый Vulkan сбрасывался на DEFAULT, который предпочитал OpenGL. | Только эта ветка сохраняет Vulkan; fullscreen recovery остаётся штатным. |
| TracyGpuProfiler | GpuApi.OPENGL даже при устройстве Vulkan. | GpuApi.VULKAN по фактическому DeviceInfo; generic timestamps не меняются. |

GLSL-директивы с GL_ARB не являются выполнением OpenGL API: штатный GlslCompiler компилирует ресурсы в SPIR-V для Vulkan. Они сохранены. Код GlBackend, драйверы и библиотеки ОС не переписываются. Native Vulkan surface и DXGI presentation остаются Vulkan-путями; DXGI fallback здесь означает исходную Vulkan surface.

## Проверки

Windows11/x64, Java 25.0.4.1, RX7600XT, Vulkan1.4.359/AMD 26.10.1. Два настоящих Vulkan-клиента прошли по три цикла открытия/закрытия принадлежащей стенду копии мира, resize/reload, действительные shadow/post passes и 19 resource/native-buffer lifecycle контрактов каждый.

- Чистый запуск с фактическим CLI VULKAN: 131664 native Vulkan draw calls, 1283 submits, один actual Tracy context probe с VULKAN; OpenGL provider preload и GlBackend boundaries — ноль.
- Нештатный предыдущий запуск с saved VULKAN без forced API: actual GameConfig NONE, исходный startedCleanly=false, итоговый VULKAN. 128127 Vulkan draws, нулевые проверяемые OpenGL calls, lifecycle и Tracy probe прошли.

Guard инструментирует действительные bootstrap/provider и GlBackend library/device/window границы, а также Vulkan create/pass/draw/submit. Это не утверждение обо всех внутренних библиотеках ОС/драйвера. Принудительная загрузка имён классов при Mixin audit не равна вызову OpenGL.

Основное private evidence: build/run/dv020Client-vulkan-guard-forced-20261101-190922-776 и dv020Client-vulkan-guard-unclean-20261101-192253-210, подпапки vulkan-backend-guard-evidence и dv020-evidence. Первый unclean стенд ошибочно унаследовал CLI backend от локального профиля; ошибка проверки сохранена, затем удалены только аргументы собственного проверочного запуска и получен настоящий NONE-case. Локальные tests/evidence не публикуются.

## Производительность

Функциональные guards не измеряют FPS. Контрольный естественный DV019/ZGC4GiB Vulkan-маршрут дал 4164 интервала, p99=8,030 мс, максимум 10,672 мс, без кадров>33,333 мс. Это одна запись лёгкого flat-маршрута с выключенными лабораторными эффектами, не доказательство устранения всех фризов или ускорения после изменений запуска.

## Фазы Vulkan и загрузка чанков

В двух дополнительных диагностических Vulkan/DXGI запусках записаны действительные encoder submit/await, vkWaitSemaphores, vkQueueSubmit2, DXGI frame-latency wait, blit/copy и Present. Ограниченные primitive collectors не меняют аргументы, порядок и синхронизацию этих вызовов. Это измерение CPU wall вокруг API, а не GPU duration; вложенные этапы нельзя складывать.

Первый маршрут: 4165 кадров, p99=8,1690 мс, максимум 14,8483 мс. Native semaphore wait имел максимум 0,3476 мс; редкий encoder submit2,5520 мс содержал лишь 0,0192 мс await и 0,0720 мс queue submission. Причина оставшегося времени не локализована. Отдельные client packet spans доходили до 10,2416 мс; это стало основанием следующего подробного захвата, но не доказательством лишнего GPU wait. JFR подтвердил все 4450 frame events, 84550 полей, нулевые несовпадения/DataLoss. Максимальная наблюдаемая GC-пауза —0,3855 мс.

Второй маршрут добавил наблюдение целых ListenerAndPacket.handle без дробления chunk packet или bundle. Все 4449 frame rows совпали между источниками;1463 enqueue=poll=завершённых records, начальный и конечный pending0. Временные метки позволили восстановить счётчики и суммы каждого кадра; расхождений, потерь, неизвестных polls и незавершённых scopes нет. Последний drain без следующего кадра учтён отдельным unframedTail.

Из 4159 route-кадров 125 содержали пакеты. Обработано 709 chunk-with-light,709 forget и 43 других records. Максимум целой пачки —2,2099 мс за 30 chunk records, соответствующий кадр —7,1399 мс. Максимум одного chunk packet —1,0007 мс. p99 интервала кадров —8,1367 мс, максимум 10,3033 мс; кадров>33,333 мс нет. Два худших кадра вообще не обрабатывали пакеты. Standalone LIGHT_UPDATE/BUNDLE и аварийные сценарии в этом capture не наблюдались. Время chunk handler не включает всю последующую очередь света.

JFR второго запуска независимо подтвердил 4449 packet и 4449 Vulkan events,151266 полей, нулевые несовпадения/DataLoss. Максимальная GC-пауза —0,4281 мс. Периодические 40-мс фризы на этих маршрутах не воспроизвелись. Запуски имеют разные source identity и наборы наблюдателей: различие чисел не является доказательством ускорения.

По полученным данным production packet budget не вводится: измеренная пачка укладывается в кадр, а дополнительное откладывание может замедлить появление чанков. Оснований удалять GPU fences, semaphore waits, ownership или DXGI lifetime barriers нет. Pipeline Guard и существующие shaders остаются в штатной графической цепочке.

Private evidence: build/vulkan-paths-evidence-20261101/vulkan-cpu-phase-summary.json и vulkan-packet-drain-summary.json; исходные captures — build/run/dv019Native-after-zgc-4g-20261101-193116-573 и -201133-962. Их raw JSON/JFR и observers не входят в публичный репозиторий или JAR.

## Точность прозрачности DarkWinter

В графических настройках добавлена строка «Прозрачность»: «Сбалансированная» / «Точная». Изменение сохраняется для следующего запуска; звёздочка и подсказка показывают необходимость перезапуска. Текущий режим указан отдельно. Штатная настройка Improved Transparency не меняется этим выбором.

BALANCED по умолчанию применяется только к уже разобранному явному VULKAN в GameConfig: четыре OIT-коэффициента вместо восьми. PRECISE оставляет штатные восемь. Для запуска без forced API, включая сохранённый native Vulkan, остаётся PRECISE, а строка выбора недоступна и объясняет ограничение. Такой запуск не понижается до OpenGL.

Собственный config/darkwinter/transparency.properties содержит единственную строку mode=BALANCED или mode=PRECISE. Отсутствующий файл означает BALANCED для поддержанного запуска; ошибочный UTF-8, неизвестные/повторные значения, превышение 4 КиБ или ошибка чтения дают PRECISE. Native options.txt ранний helper не читает. Позднее изменение файла не перестраивает активные OIT layouts.

Меню сохраняет файл атомарно с flush; при отказе прежний файл остаётся целым, OptionInstance и CycleButton откатываются, показывается локализованная ошибка. Callback не меняет graphics API, активные коэффициенты или native Improved Transparency. Проверены 64 startup-контракта, девять проверок записи и настоящие forced/unforced Vulkan-меню. Разбор производительности и границ: [вода и прозрачность](DV_014_RU.md).
