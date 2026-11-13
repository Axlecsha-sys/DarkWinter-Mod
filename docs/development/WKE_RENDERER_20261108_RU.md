# WKE: нативный рендер мира в Fabric-моде

Дата: 08.11.2026, Asia/Vladivostok. Minecraft 26.3 / Fabric 0.19.5 / Java 25 / Windows x86-64 / Vulkan.

## Результат

WKE подключён как C++ backend мировых графических draw-команд. Он создаёт и отслеживает VkPipeline и рисует world-pass: местность, сущности, небо, частицы, 3D-предметы/HUD, outline и постэффекты. Native-команды используют текущее устройство и targets Minecraft; второе окно/устройство/игровой мир не создаются.

Minecraft и Java-модули DarkWinter продолжают владеть симуляцией, генерацией, моделями, видимостью, подготовкой геометрии и световых данных, графом проходов, GPU allocations/layouts/descriptors/uniforms, barriers/clear/copy, submission/presentation. Двумерный GUI остаётся вне native WORLD scope. Это встроенный native GPU backend, а не перенос всей игры/симуляции в C++.

## Реализация и владение

- WKE/runtime/embedded/wke_vulkan.{h,cpp}: C ABI 1, 128-байтные draw packets, заимствованный VkDevice, собственность VkPipeline, прямые/indexed/indirect команды.
- Вся пачка проверяется до первой Vulkan-команды; при отказе можно выполнить исходный host draw без дублирования.
- vulkan_world_commands.hpp — общий фактический исполнитель команд для embedded backend и прежнего Renderer::drawGeometry самостоятельного host.
- modules/wke/adapter/java/com/winterkore/engine/render: FFM, identity/generation guard устройства, pipeline creation/retirement, WORLD/TERRAIN scopes и диагностика.
- modules/wke/adapter/resources: Minecraft-совместимые wke:core/terrain и отдельная конфигурация восьми mixins. Native часть не включает Minecraft/Fabric/JNI.
- Shader frontend сохраняет исходные vertex formats, uniforms, lightmap, cutout, fog, RGSS/nearest, reverse-Z, MULTIDRAW и OIT. Custom shadow/material overrides сохраняются.
- GPU-safe destruction следует host deferred queue; перед уничтожением device проверяется фактическое освобождение native pipelines. Функции C++ не выпускают исключения через ABI.

Создание graphics PSO native допускается на раннем старте для заранее скомпилированных pipeline. GUI может использовать такой pipeline через свой прежний draw-путь. Наличие PSO или DLL не считается доказательством native world draw.

## Игровое доказательство

Финальная задача: wkeRendererVerifyClient; job ff34d2664ba443789139d83fbfe282d0, exit 0, 57 секунд.
Квитанция: build/WKE/latest-renderer-client.json.
Изолированный каталог: build/run/wkeRenderer-20261108-094102-803.
Обычные настройки и сохранения игрока не открывались.

Проверки выполнены на AMD Radeon RX 7600 XT, Vulkan driver 1.4.359 AMD proprietary driver 26.10.1 (LLPC).

| Наблюдение | Фактический результат |
| --- | --- |
| SDK | completed=true, 100 сохранённых проверок |
| Контроль исходного host | Независимый Java WORLD/TERRAIN observer видит исходные draw-команды в HOST |
| WKE | Те же независимые Java WORLD/TERRAIN counters не растут; native-команды растут |
| Native к концу world lifecycle | 31678 Vulkan draw-команд; WORLD dispatch 31678, вложенный TERRAIN 11842 |
| Native отказ/ошибка/fallback | 0 / 0 / 0 в проверенных WORLD/TERRAIN scopes |
| Силуэт и depth A/B | 0 отличающихся depth/mask pixels в парной сцене |
| Цвет A/B | 593 occupied pixels с RGB-разницей больше 4, около 0,25%; естественный HOST drift 333 pixels, около 0,14% |
| NPC | Настоящий неподвижный Villager; удаление изменило 711 depth pixels и 1091 color pixels |
| Погода | Настоящая blizzard-фаза, 2200 живых SnowWeatherParticle |
| Разрешения | 1280×720, resize 960×640, настоящий GPU readback 1920×1080 |
| Lifecycle | Изменение блока, камера, reload ресурсов, чистое исчезновение overlay перед screenshots, disconnect/re-entry |
| Device close | После GPU drain создано 571 / уничтожено 571; nativeLivePipelines=0; successful detach наблюдён в реальном логе |

C++ packet packing в этом SDK намеренно VANILLA, voxel mode JAVA. Поэтому пройденный тест не объясняется прежней native-упаковкой.

Счётчики описывают запись Vulkan-команд, не GPU completion/FPS. GPU color/depth считывались после реального исполнения; raw depth хранится рядом с PNG в FLOAT32_LE reverse-Z. Raw capture делается до визуального resolve/рук/GUI; финальные screenshots отдельно сделаны после исчезновения resource overlay. World counter включает вложенный terrain, а приватный GPU-readback добавляет вспомогательные world-pass команды.

## Кадры

- [Парная сцена с NPC, WKE](../../build/run/wkeRenderer-20261108-094102-803/gpu-captures/wke.png).
- [Full HD raw GPU](../../build/run/wkeRenderer-20261108-094102-803/gpu-captures/wke-native-full-hd.png).
- [Чистый Full HD кадр с тенями](../../build/run/wkeRenderer-20261108-094102-803/screenshots/0000_wke-native-world-with-shadows.png).
- [Чистый Full HD снегопад](../../build/run/wkeRenderer-20261108-094102-803/screenshots/0001_wke-native-world-snow-particles.png).

Визуально осмотрены парные HOST/WKE кадры, NPC, итоговые Full HD тени и снегопад. Это контролируемая сцена SDK, а не замер большого леса.

## Остальные проверки и сборка

| Проверка | Результат |
| --- | --- |
| wkeVerifyRendererNative | PASS: 40 native contract checks; контролируемые callbacks, без GPU validation |
| wkeVerifyIndependent | PASS, job 2b9008be57f5430b8cbf63925c53e185: PowerShell/Zig, три DLL, 306547 core assertions и 40 renderer checks, без JVM/Minecraft/Gradle внутри driver |
| coreCompileGame | PASS, job e8d2c150a7144e7cb55cde361bcade0c: прежний C++ host с общим исполнителем компилируется |
| wkeVerifyPackage + build | PASS, job d41621d7943d46cabeba3b62282e70f4: три DLL/хеши, классы/восемь mixins/регистрация и исходные shader resources в фактическом JAR |
| IntelliJ build_project | PASS, ошибок нет |

Файл: build/libs/dark-winter-1.0.1.jar.
Размер: 83848338 байт.
SHA-256: c2e05f0155b649bfe460d7e1d0ee204d6beeb4af2269a82c09607d1100536f4c.

wke_render.dll: 434688 байт, SHA-256 e3827c4bf040172f15481d777aac14166f7417967f832f3ccfed2cb29a592c4f.
Этот же DLL hash подтверждён извлечением из classpath в SDK и в готовом JAR. wke_core/wke_voxel сохранили прежние проверенные SHA. Приватных renderer SDK классов в продукте нет.

## Проверка автором и границы

После запуска актуального мода в мире: /wkerender status. Ожидаются effective=WKE и растущие nativeDrawCalls. По умолчанию AUTO; нужен --enable-native-access=ALL-UNNAMED, уже заданный в runClient. /dwmesh VANILLA относится к сборке геометрии и не выключает WORLD renderer.

Снижение работы: один заимствованный graphics context, пакетные indirect draws, переиспользование single-packet памяти, без повторного мира/геометрического upload или per-draw диагностического readback. Самостоятельный host использует общий безаллокаторный исполнитель. Производительность полного кадра не измерялась; 120 FPS не заявляются.

Не заявляются исправление temporal AA, хвои/снежных швов старой C++-игры, перенос генерации/симуляции, одинаковые материалы/графы обоих host или перенос всех прежних standalone эффектов. Обнаруженное ранее ограничение mip atlas из-за коры 1254×1254 остаётся отдельным кандидатом для визуального аудита.

Minecraft 26.3 getLastDebugMessages возвращает пустую заглушку. В этом тесте не наблюдался validation-layer callback, поэтому отсутствие VUID/полная Vulkan validation не объявляются. CMake-конфигурация добавлена, но CMake/Ninja отсутствуют; фактически проверены Gradle и независимый PowerShell/Zig путь.
