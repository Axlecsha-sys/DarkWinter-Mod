# Аудит архитектурных направлений DV

Проверены девять направлений архитектурной группы по фактическим исходникам Minecraft 26.3 и DarkWinter. Это выбор конкретной безопасной границы для следующего этапа, а не подтверждение полной переработки ядра.

| ID | Что уже есть | Что остаётся и ограничения |
| --- | --- | --- |
| DV-001 | Сервер последовательно обрабатывает clocks, levels, connections и players; внутри мира сохранён порядок block/fluid/chunk/events/entities. DarkWinter добавляет owner-thread tick подсистем. | Регионального владения, межрегиональных сообщений и миграции сущностей нет. Запуск vanilla level.tick в executor не является допустимым локальным исправлением. |
| DV-002 | Серверная погода использует ticks; VisualTime и WeatherClient уже интерполируют presentation через partialTicks. | Независимые частоты всех подсистем не реализованы. Изменение TPS не заменяет отделение времени; нужны pause/freeze, time commands и JOIN проверки. |
| DV-003 | Origins, journal и import loader имеют границы владения; TestingServer задаёт порядок подсистем. | Общие Context и tick остаются, отдельных бюджетов каждой системы нет. Произвольный вынос mutable block/fluid/entity callbacks создаст нарушения причинности. |
| DV-004 | Клиентский цикл последовательно extract/apply/render; section workers уже существуют, DV-011 ограничивает snapshots, диагностика имеет bounded worker. | Возможности выноса чистых задач выбираются профилем; RenderSystem/GPU остаются на предусмотренном потоке. Immutable presentation inputs — узкий ближайший шаг. |
| DV-021 | Клиентские чанки и серверная симуляция имеют самостоятельное владение и packet updates. | Делить mutable chunks между потоками нельзя. Подтверждённая локальная лишняя копия — OnlineTestingView.settings при каждом чтении render/weather. |
| DV-025 | Сохраняются vanilla collision/step-up; weather даёт отдельные wind/temperature inputs, генерационный снег не является общим solver. | Тепло, перенос снега и новая физика будут новыми механиками и форматами состояния, а не автоматически эквивалентным performance fix. |
| DV-026 | Погода хранит per-save timelines; native tickets/range ограничивают активные сущности. | Полной региональной catch-up симуляции снега/растительности/AI нет. Требуются отдельные persisted правила, агрегация и миграции. |
| DV-030 | Есть TestingAccess, WeatherInput, OnlineTestingView и TestingOnlineBridge. | Локальный путь отдавал mutable серверные settings, сетевой создавал полный новый TestingSettings/CHM при каждом getter. Общего атомарного presentation набора не было. Выбран следующий адресный этап. |
| DV-031 | Версионные Mixin descriptors, std140 SceneFrame 416 bytes, ABI и fallback уже закрепляют локальные контракты. | Они не стабилизируют Mojang API между версиями. Нужны поддерживаемые regression contracts, а не массовая прослойка абстракций без конкретного потребителя. |

Опорные точки: MinecraftServer.tickChildren, ServerLevel.tick, Minecraft.renderFrame, ClientPacketListener chunk updates; DarkWinter TestingServer.init, TestingOrigins, WeatherServer/WeatherClient, VisualTime, TestingAccess, OnlineTestingView, TestingOnlineBridge и SceneFrame.

## Выбран DV-030

Добавляется маленький неизменяемый TestingPresentation: module mask, weather, wind, temperature, film/haze/shadow параметры. Mutable settings остаются у server owner и UI/authoritative команд. Наблюдавшаяся гонка не объявляется: volatile/ConcurrentHashMap защищали отдельные поля, но не полный согласованный набор.

Offline snapshot публикуется после загрузки и после завершённого owner tick, с повторным использованием прежнего объекта при неизменившихся значениях. Actor-specific tools не публикуются посередине временной подстановки settings: END_SERVER_TICK видит восстановленное shared состояние.

Online snapshot создаётся из валидированного DTO вместе с View и читается после существующих binding/epoch/authentication проверок. Pending даёт нейтральный набор, disconnect/world switch исключают старое состояние. Старые UI getters и серверные правила/сохранения не заменяются.

Weather operation использует один captured snapshot; VisualRenderer захватывает его в SceneFrame и передаёт WeatherInput и всем UBO writes этого кадра. Это подготовка общей границы входов для последующей связи освещения DVCPP-004 с существующими погодой, туманом и постобработкой.

## Критерии

- Матрица 76 модулей/пресетов проверяет WeatherValues и WeatherInput.State; отдельные captured/all-OFF контракты проверяют production UBO writer. Ordinary worlds используют прежний fallback.
- Старый record не меняется после server update; следующий опубликованный набор целиком новый; неизменённый набор сохраняет identity.
- Profile/reset/recovery/actor scopes, authentication generation, pending, world switch и disconnect не публикуют временное или старое shared состояние.
- Повторные online presentation reads не создают TestingSettings и ConcurrentHashMap; allocation counter используется отдельно от FPS.
- Vulkan и независимые OFF состояния модулей остаются корректными; новые OpenGL-прогоны исключены из текущей матрицы. Публикация может отставать до конца текущего owner tick; это явная граница, не свободное чтение промежуточных изменений.

Первый этап DV-030 реализован и прошёл Vulkan, native owner/lifecycle и настоящий локальный Online host стенд. [Результаты и границы](DV_030_RU.md). Остальные восемь направлений не объявляются закрытыми этим изменением.
