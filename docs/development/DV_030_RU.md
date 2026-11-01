# DV-030: неизменяемые входы лаборатории

Первый адресный этап архитектурного направления выполнен: рендер и погода больше не читают локальные mutable server settings и не создают сетевую копию TestingSettings при каждом presentation getter. Это не переработка всей архитектуры Minecraft. [Аудит девяти направлений](DV_ARCHITECTURE_RU.md).

## Изменение

TestingPresentation содержит module mask и параметры weather/wind/temperature/film/haze/shadow. Сервер публикует целый immutable record после загрузки и после завершённого END_SERVER_TICK. При неизменившихся значениях сохраняется прежний объект. Временные actor-specific settings восстанавливаются до публикации.

OnlineTestingView создаёт record из валидированного собственного DTO вместе с View. Сохраняются существующие authentication/binding/epoch проверки: pending даёт neutral, отключение и смена мира исключают старый View. UI getters оставлены без изменения и не входят в новую ownership-границу: локальные UI/motion/stamina/debug потребители всё ещё могут читать старый settings getter. Серверные команды, игровые правила и сохранения сохраняют прежнее владение.

Каждая операция WeatherClient захватывает вход один раз. VisualRenderer передаёт один record в WeatherInput, SceneFrame, SolarRuntime, FilmHistory и проверки pipeline/weather/shadow/fog этого кадра. Публикация посреди кадра не меняет его лабораторные входы. Обычные миры используют прежние значения по умолчанию.

SceneFrame сохраняет std140 layout 416 bytes. Его writer теперь отклоняет heap, read-only и короткие по limit буферы до JOML Unsafe; запись остаётся по абсолютным смещениям от нуля. Производственный GPU-путь использует native memory. Ошибка первого тестового стенда с heap buffer обнаружена и исправлена scoped native allocation; маскирующих JVM flags нет.

## Проверки

Все новые клиентские запуски после выбора основной платформы выполняются на Vulkan.

- 103525 standalone state assertions: десять module bits, detachment, identity reuse, signed zero и controlled volatile handoff. Это проверка record, а не настоящего мира или авторизации.
- Настоящий Vulkan SDK-клиент: 76 weather/presentation сочетаний и 11 групп контрактов. Проверены END tick publication, старый captured frame после обновления владельца, production UBO writer, profile load, all-OFF, native save/reopen с новым Context, ordinary world fallback и native-buffer guard. Mixin audit прошёл.
- Шесть окон по 100000 offline getter reads: TestingSettings constructors равны нулю; положительный контроль каждый раз фиксирует 512 реальных constructors. Пять окон показывают ноль выделений, одно — 3320 bytes. Это не универсальное обещание отсутствия выделений JVM.
- Настоящий локальный DirectoryNode и attested Online host на Vulkan: 2485 проверок исходного стенда прошли, включая отказ неизвестному/unverified участнику. Дополнительный observer подтвердил стабильную identity на 8192 reads, ноль mutable-settings constructors против 512 в clone control и invalidation при завершении session/world. Сам getter и observer могут иметь другие выделения: нулевая общая стоимость не заявляется.
- Свежие Options выбирают Vulkan, реальное save/load сохраняет предпочтение и проверенные render/simulation distance, frame cap и VSync.

Основной успешный клиент: build/run/dv030Client-vulkan-20261101-081206-173/dv030-evidence/report.json. Online observer: build/run/dv030OnlineHostClientTest/dv030-online-evidence/presentation.json. Полный host report: build/online-native-host-evidence.json. Локальные тесты и evidence не входят в JAR или публичный репозиторий.

## Границы

Изменение обеспечивает согласованность лабораторных presentation inputs и убирает конкретную лишнюю копию. Оно не доказывает прирост FPS, отсутствие всех гонок, независимый client/render loop или полную изоляцию подсистем. Снимок может отставать до конца текущего owner tick. Нативное освещение DVCPP-004 ещё должно отдельно доказать корректность и полный выигрыш; этот интерфейс готовит связь с существующей визуальной системой и не вводит новый shader pack.
