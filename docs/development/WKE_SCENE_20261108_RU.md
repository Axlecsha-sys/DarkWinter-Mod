# WKE: материалы, стабильность кадра, эффекты, снег и звук

Дата: 08.11.2026. Minecraft 26.3 / Fabric / Java 25 / Windows / Vulkan.
Самостоятельная игра отложена. Генерация остаётся Minecraft/DarkWinter.

## Требование автора

Настоящее **2560×1440, стабильные 144 FPS, максимальное реализованное качество**.
Бюджет полного кадра — **6,944 мс**. Основной монитор: 2560×1440 / 144 Гц.
Контрольный ПК: Ryzen 5 8400F / Radeon RX 7600 XT.
Это цель разработки; отдельная компиляция или количество draw-команд её не подтверждают.

## Реальное подключение

| Пункт | Подключено к обычному моду |
| --- | --- |
| 1. Стабильность картинки | Native-resolution temporal resolve с реальной jitter-проекцией, текущей/предыдущей матрицами, depth rejection, clipping и отдельным spatial fallback. Две RGBA16_FLOAT/D32 истории; две реальные R8-маски текущих/предыдущих particle quads. Полные motion vectors отсутствуют. |
| 2. Материалы | Общий независимый C++ kernel: linear premultiplied mip, cutout coverage, RGB padding прозрачных краёв. FFM frontend и настоящие полные GPU-цепочки для хвои/ветвей. |
| 5. Эффекты | Общие WKE shader resources, шесть graphs и контракт, реальный WkePostGraphExecutor через host FrameGraph. Профили качества эффектов и работающие AA/MIP/film controls. |
| 6. Поверхность | Накопление снега в активной загруженной области, сопротивление ходьбе, небольшие отпечатки, заметание и сохранение по чанкам. |
| 7. Звук | Реальные существующие OpenAL channels/context: позиция/дистанция, direct/high-frequency фильтры препятствий, EFX room reverb. |

Minecraft сохраняет владение миром, симуляцией, коллизиями, световыми данными, подготовкой геометрии, главным FrameGraph, barriers и presentation.
WKE C++ создаёт VkPipeline и записывает настоящие мировые draw-команды на заимствованном Vulkan device.
Независимые Java surface/audio политики не требуют Minecraft; host-адаптеры читают и изменяют только доступный загруженный мир.

## Почему прежний MIP4 не исправлял дальнюю растительность

1. Ветровая хвоя использовала отдельную SimpleTexture без mip. Теперь этот renderer использует действительный atlas/sampler.
2. У хвои 512² atlas mip4 заканчивается на 32². Для дальней минификации этого недостаточно. Авторская хвоя получила 10 уровней, ветви — 7, с реальными uploaded GPU texels.
3. Fog preprocessor основного terrain мог обходить material bindings. Теперь собственные fog stages сохраняются и используют общий sampler. MAIN и shadow dispatches проверяются отдельно.

Общий MIP 0–4 плавно задаёт max LOD хвои 0/2,25/4,5/6,75/9 и ветвей 0/1,5/3/4,5/6. Атлас сохраняет собственные native уровни 0–4. Исходные authored PNG не заменялись; несовместимые размеры нормализуются только сборкой.

Это исправляет фильтрацию текстур. Геометрия тоньше пикселя дополнительно требует сглаживания.

## AA: исправления и строгая проверка

История пишет непосредственно в внешний temporal target FP16, до spatial/film и преобразования в финальный MAIN RGBA8.
Точечный host mixin задаёт FP16 color target state **до компиляции** только у собственного temporal pass.
Простая смена формата последующего copy не использовалась: color copy в этом пути отсутствует, выполняются depth copy и swap.

RGBA8 feedback создавал dead zone EMA: при retention 0,9 изменения менее примерно пяти кодов цвета могли не накапливаться.
Эксперимент с retention 0,95 усилил проблему и не прошёл проверку; возвращено 0,9.
FP16 устраняет эту конкретную потерю точности, но сам по себе не закрыл дальние силуэты.
Текущая дополнительная правка реконструирует current color в стабильную native сетку и согласует выбранный raw depth texel с ray/reprojection.
Реальные текущие частицы сохраняют direct-current bypass через R8-маску.

Приёмка использует фактические восемь Halton phases, GPU color/D32 и реальные UBO matrices.
OFF/ON сравниваются на **одинаковых мировых точках** дальних силуэтов: требуется минимум 5% улучшения среднего остатка и отсутствие регрессии p95.
История должна использоваться во всех captured frames без reset. Числовые требования не ослаблялись.
Движение/удаление настоящего NPC и реальные полупрозрачные частицы проверяются отдельно.
PNG/CPU-анализ не выполняются между кадрами измеряемой AA-серии.

**Итог AA: PASS в обычном GPU-прогоне 89db51ee3f19470fbe7a49b5286f2573, 710 проверок всей сцены.**
На 5868 одинаковых world-point samples средний остаток снизился 3,4073 → 2,5467 (−25,3%), p95 14,2753 → 8,9432 (−37,4%). Все восемь actual Halton phases подтверждены, history использована без reset.
Реальный NPC: доля корректной фоновой глубины после движения 99,86%, после удаления 100%; средний foreground tail около 0,00144/0,00115.
Реальные 1-pixel частицы: минимум контраста 0,9476 от raw source, максимальный измеренный старый шлейф 0; обе реальные R8-маски прочитаны с GPU.
Финальный TAA shader SHA d1737a243af4224079047d426eb0ed0d69a257b3713c31736b200dc55eb978ae.
Нативные pipelines при нормальном закрытии: created=964, destroyed=964, live=0.
Реконструкция может слегка смягчать мельчайшие детали; прохождение этих сценариев не означает наличия object motion vectors или отсутствия любых артефактов.
Предыдущий FP16-прогон f58fdc0bec3f4d559e9b43384e11f470 не прошёл только paired AA: 3,4071 → 4,6630 byte luma residual.
Это не записано как успешное исправление ряби.

## Облака: сохранение качества подтверждено GPU

Первая диагностика настоящими асинхронными Vulkan timestamps обнаружила основной weather hotspot:
cloud pass около 3,095 мс в снегопад. Отдельные snow particles и R8 mask не были основным источником этого времени.

В roundClouds пропускаются только математически нулевые элементы:
расстояние до возможных центров, точный радиус 0,60 при максимальной поддержке 0,576261 и область нулевого нижнего profile.
Шаги raymarch, разрешение, свет, density и depth оставшихся элементов сохранены.
Также fused merge повторно использует уже прочитанные texels только при точном совпадении адресов.

**Реальный same-frame GPU reference A/B — PASS, побитово одинаково.**
Прогон build/run/wkeScene-20261108-130914-123: 1280×720, 921600 пикселей, 345520 занятых облаками;
различий RGBA, depth bits и depth mask — 0.
Оба draw использовали одни и те же actual GPU uniform slices: roundness 0,3, cover 0,82, scale 1,15, stretch 1,45.
SHA исходного shader b3028c61722c415400caf3bbfdeb67d4c4c7d64f049104212fa4844f3f297f02;
оптимизированного 774783f46a0cee99f8ef21861a9c5ba044c84de7c2499c8504115ddf1a006682.
Оба PNG имеют SHA c9d81bbd4af3f173fa918f03247e41a94d2179aec07a8c307852aa6e703400cf.
Это intermediate cloud buffer; основной кадр отдельно подтверждён как 2560×1440.

## Снег и звук: фактические результаты GPU-сцен

- Ходьба одинаковыми настоящими input ticks: bare около 6,526 м, deep snow около 3,154 м за 40 ticks.
- При реальном snowfall 0,38 глубина за 80 ticks выросла примерно 875 → 877 мм.
- После настоящего save/close/reopen восстановлены глубина около 878 мм, 7 layers и 5 footprints; новые контакты при доказательстве восстановления отсутствовали.
- Footprints реально нарисованы GPU. Общая разница кадров включает небо/фон и не выдаётся за число пикселей только следов.
- OpenAL EFX проверен на настоящем audio thread/context: закрытая дверь direct gain около 0,480, HF около 0,273; открытая — около 1,000/0,976.
- Помещение: wet около 0,320, decay 1,65 с; снаружи wet 0. UI relative sound остаётся без фильтра/reverb. AL error 0.
- Device HRTF status был 0: принудительный HRTF не заявляется.

Снег работает около игрока в уже загруженных чанках; offline пересчёта региона нет.
Есть контактный API для будущего транспорта, готовый снегоход не добавлен.
Акустика оценивает препятствия/помещение в ограниченном бюджете, не моделирует волновое поле.
Время ветра интерполируется каждый render frame; отдельный потенциальный pop ближнего набора при лимите 384 экземпляра требует последующего отдельного наблюдения.

## Производительность: достижение 144 FPS пока не подтверждено

Условия: реальный native 2560×1440, RD15/SD17, MIP4, anisotropy 8×, HIGH и все существующие эффекты/film/AA включены, VSync выключен.
Контрольная сцена: 24 настоящих authored spruce + 2 vanilla, 20 grass и 20 shrub assemblies.
Это ограниченный stand, не копия пользовательского леса и не весь регион.

Отдельные десятисекундные окна не содержат GPU readback или PNG IO. Пер-проходные timestamp diagnostics выполняются отдельным запуском и не выдаются за baseline FPS.
Первый обычный прогон 7ac6c000e07b4249b91b4807e4669bd8: Snow GPU p99 7,970 мс, mean frame 7,859 мс.
После первого exact cloud skip, прогон 87bed4e0258f4b389d0e9067f023b75b: Snow GPU p99 7,251 мс, mean frame 7,220 мс.
Поздний FP16/fidelity прогон f58: Snow GPU median 7,188 мс; присутствовали отдельные host/render hitches до 111,7 мс полного кадра.
Во время этого окна зарегистрированные GC counts/times не изменились; причина hitches без корреляции timeline не приписывается GC.
Итоговый обычный прогон без timestamp/debug/fidelity-флагов — 89db51ee3f19470fbe7a49b5286f2573:

| Сценарий | Средний FPS | 1% low | Полный кадр p99 | GPU p99 |
| --- | ---: | ---: | ---: | ---: |
| Стоя, ясно, все HIGH effects | 262,6 | 216,7 | 4,212 мс | 3,991 мс |
| Нативная ходьба, снег, все HIGH effects | 141,7 | 127,3 | 7,598 мс | 7,344 мс |

Снег: 1416 измеренных frame intervals, mean 7,05584 мс; 177 реальных GPU samples, mean 7,00167 мс.
Требование стабильных 144 FPS **не выполнено**. Положительный feature verdict не подменяет этот отрицательный performance result.
Обычный SDK сохраняет весь raw timeline; FPS ниже 144 не скрываются и не усредняются с ясной погодой.

Следующий участок производительности — полноценная оптимизация дорогого облачного поля и редких host/render hitches. Дополнительный horizon skip не внесён: фактический dwCloudInterval уже отсекает эти лучи до march, существенного выигрыша в данной сцене нет.

## Завершённые проверки основы

| Проверка | Результат |
| --- | --- |
| Независимый WKE/build.ps1 без JVM/Minecraft | PASS: 306547 core assertions, 40 controlled Vulkan ABI/ownership checks, 16091 material assertions. Vulkan callbacks здесь имитированные. |
| wkeVerify, job 71175c62c5e74ab8ab8437cad0f2f465 | PASS на предыдущей проверенной основе: native, surface/store, mip, mesh/visibility/fallback/FFM/package. |
| visualsDoctor + checkMotionBlurEnvelope | PASS: ABI 416/176/16 байт, 241 motion assertion. |
| wkeSceneVerifyClient, job 89db51ee3f19470fbe7a49b5286f2573 | PASS: 710 checks, настоящий native 1440p, материалы, парное AA, NPC/particles, снег/save/reopen/OpenAL; performance result отдельно выше. |
| build + wkeVerifyPackage + renderSettingsVerifyClient, job 4ade5110a4d84cfd85eb6d145d7147e1 | PASS: свежий JAR, все 35 canonical resources по SHA, три DLL, зарегистрированные production mixins, отсутствие private SDK classes; 179 UI checks уже с финальным FP16/AA. |
| Финальная GPU film parity | OFF grade-only max RGB error 1/255, alpha mismatches 0, повторный OFF drift 0; реальные quality/AA/MIP/film UI clicks и persistence. Native pipelines created/destroyed=1070, live=0. |
| IntelliJ build_project после финального кода | PASS: isSuccess=true, problems=[]. |

Промежуточный packaging run 62571312c3af488bbdb26a016f7b32c7 обнаружил ошибку самого нового verifier: относительные имена mixins с подпакетом не преобразовывались в ZIP path. Проверка исправлена; финальный 4ade прошёл. Отсутствие класса не обходилось исключением.

## Итоговая сборка

- Файл: F:\\DarkWinter\\build\\libs\\dark-winter-1.0.1.jar.
- Размер: **85703503 байта**.
- SHA-256: **6deb9711451bad33e0cbc53b053f1d6b620ff22be06fd39466dc88e65557b81d**.
- Контрольная сумма независимо пересчитана после сборки и совпала с packaging receipt.
- wke_core.dll: 4fe91683143f479933b61e55eeb4b21aabd7036567321eded19982f4fcedcf8c.
- wke_voxel.dll: ba0298fca9dd56d8a5caf21e4334f4ab02904a0384bc83d33bd14a8b0a2c0eae.
- wke_render.dll: 64dd09c9095c8509582d67dc8bedbbd13d23722e0bccc6b36ffe4543b1924745.

Настройки и миры SDK изолированы от обычных сохранений и настроек игрока.
CMake/Ninja не установлены и не проверялись; отсутствие сообщений host debug API не считается Vulkan validation proof.

## Свидетельства

- Финальный compact receipt: build/run/wkeScene-20261108-131829-286/wke-scene-summary.json; SHA 905a024e279bae4b0f2d19232b9a846759ac370484d15378bef3a6050515c424.
- Финальный полный receipt: build/WKE/latest-scene-client.json и build/run/wkeScene-20261108-131829-286/wke-scene.json.
- Полные raw series и PNG/D32: performance-captures и gpu-captures в том же каталоге.
- Cloud reference color/depth: build/run/wkeScene-20261108-130914-123/gpu-captures/cloud-fidelity.
- UI receipt: build/render-settings/latest.json.
- Итоговый packaging receipt: build/WKE/package-verification.json.
- Согласованные цели и границы: WKE/ROADMAP_RU.md и WKE/README_RU.md.
