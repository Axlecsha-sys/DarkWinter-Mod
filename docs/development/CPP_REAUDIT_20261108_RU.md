# DarkWinter C++ — повторный аудит и исправления
Дата: 8 ноября 2026 (Asia/Vladivostok).
Корень: F:/DarkWinter. Рабочее дерево develop/с++, с сохранением существующих незакоммиченных изменений.
Сборка: build/core/game-client-v14/DarkWinter.exe.
Запуск: Start-DarkWinter-Core.cmd выбирает v14; работающая v13 не закрывалась.

## Изменения, уже найденные перед работой

В коде появились WorldMipChain, аппаратный textureGrad, линейная minification/trilinear mip-фильтрация, анизотропия до 8x и коррекция alpha coverage. У генератора v4 есть поиск подходящей опоры для растительности и контекстные соединения стволов со снегом. Они были реализованы до этого повторного аудита, а не в данном изменении. Старые версии генератора сохранены для совместимости миров.

## Что исправлено сейчас

| Пункт прошлого аудита | Состояние |
| --- | --- |
| DW-A01: уничтожение Scene раньше StreamingWorld при двойном отказе загрузки | Исправлен порядок reset: stream → collision → scene |
| DW-A02: игнорирование отказа save при закрытии | При отказе приложение возвращается в паузу; добавлена проверка close потоков .state/.info перед публикацией |
| DW-A03: неиспользуемые traversal masks в растровой игре | Scene(..., tracing=false) не выделяет и не строит их; default=true оставляет экспериментальные пути |
| DW-A04: полная копия world на каждую партию | В игровом растровом пути pump публикует dirty instances напрямую без общей копии и фоновых mask jobs |
| DW-A05: полный перебор геометрии при клике | Игровые лучи ограничены досягаемостью; перед обходом экземпляров отсекаются не пересекающиеся секции с halo 4 блока. Независимый безграничный oracle сохранён |
| DW-A06: повторный ray/triangle test на каждом видимом фрагменте | Нормальный путь использует растровую интерполяцию. Точный sourceIntersection остался для oracle. Стабилизация depth плоских граней сохранена отдельно |
| DW-A07: рябь/лесенка растительности | Дополнено уже существующее mip-фильтрование: MSAA до 4x, alpha-to-coverage, centroid interpolation, устранение discard по центру пикселя |
| DW-A08: большой синхронный commit чанков | Часть стоимости устранена за счёт DW-A03/04; перестройка render batches остаётся на основном потоке, общего бюджета публикации ещё нет |
| DW-A09: частичный scope frameP95/frameP99 | Остаётся: это CPU work, не полный интервал вывода. Новых обещаний FPS по этим цифрам не делается |
| DW-A10: UI viewDistance до 512 при effective ≤88 | Остаётся; в этом изменении диапазон/семантика настройки не менялись |
| DW-A11: опора растений на полублоках | Уже переработана в генераторе v4 до этой работы; старые генераторы не переписывались |
| DW-A12: validation в обычном запуске | Состояние слоя теперь явно записывается в отчёт. На этой машине validationEnabled=false; ноль validationErrors не означает выполненную проверку слоя |

## Как работает исправление изображения

Сглаживается геометрия мира, включая непрозрачные тонкие ветки и cutout-края травы/хвои. Количество samples проверяется по поддержке color/depth форматов и ограничивается 4; возможны fallback 2 или 1. Oracle/probe-путь оставляет 1 sample и исходный контракт alpha, поэтому его успех не используется как доказательство качества MSAA.

Дополнительные ресурсы создаются при загрузке/resize, не каждый кадр. Постэффекты и HUD остаются в исходном разрешении. Это не размытие экрана и не temporal history; шлейфы от истории не добавляются.

Цвет разрешается аппаратно. На Vulkan 1.1 глубина разрешается отдельным проходом: max для reverse-Z. Coverage определяется по покрытым depth samples, а не по усреднённому alpha A2C. Композиция с небом/облаками учитывает coverage и не умножает разрешённый цвет повторно.

Ограничения: MSAA расходует дополнительную память/время GPU. Полное исчезновение любого temporal aliasing не доказано. На проверочных кадрах всё ещё видны пунктирные швы на некоторых снежных поверхностях; отдельное исправление геометрии/глубины этих соединений остаётся открытым. Картинка на статичном кадре сама по себе не доказывает плавность.

## Оптимизация и сохранение поведения

Для окна 13×13×7 секций старый dense world составлял примерно 93.09 МиБ. Без occupancy/owner masks остаётся около 18.49 МиБ клеток и индекса; примерно 74.60 МиБ служебных массивов больше не нужны этому пути. Общая копия 93 МиБ на обновление тоже убрана. Это расчёт размеров структур, не замер RSS и не обещание прироста FPS.

Авторитетные snapshots, журнал edits, версии генератора, seed, palette identity, досягаемость, коллизии и формат сохранений сохранены. Новые проверки подтверждают сохранение и восстановление растровой сцены.

## Проверки

Успешный прогон:
.darkwinter-lab/jobs/ec97f80ab5ee4ac49efde67e5b1be65d.log
BUILD SUCCESSFUL; coreCheckAntialiasing, coreCheckRasterScene, coreCheckAntialiasingMotion.

- coreCheckAntialiasing: 180 кадров, antialiasingSamples=4, filteredTextureFrames=180.
- coreCheckAntialiasingMotion: 360 кадров контролируемого движения камеры, samples=4, все 360 кадров в обычном filtered пути.
- coreCheckRasterScene: 8 проверок: исходные клетки/хеш, отсутствие traversal arrays, streaming, edit publication, bounded picking и snapshot/journal reload.
- Ранее в общем наборе также прошли 1718 player checks и 68 player transitions.
- Слой Vulkan Validation недоступен: validationEnabled=false.
- Сравнение FPS до/после при одинаковом workload не выполнялось.

Доказательства:
build/core/game-runs/20261108-051222-628/antialiasing/stage6.json
build/core/game-runs/20261108-051222-628/antialiasing/frame-179.png
build/core/game-runs/20261108-051222-628/antialiasing-motion/stage6.json
build/core/game-runs/20261108-051222-628/antialiasing-motion/frame-60.png
build/core/game-runs/20261108-051222-628/antialiasing-motion/frame-180.png
build/core/game-runs/20261108-051222-628/antialiasing-motion/frame-300.png
build/core/game-runs/20261108-051222-628/antialiasing-motion/frame-359.png

Общий coreVerifyGame пока НЕ зелёный:
- generation-checks останавливается на "Observed coplanar ray keeps canonical CPU material": фиксированные лучи/ID не совпадают с текущей сценой. Это обнаружилось ещё до изменения bounded picking; проверка не удалялась и не ослаблялась.
- Старый GUI exercise останавливается на "Loading cancellation did not return to title". Его нельзя выдавать за проверенный цикл save/reload/меню.
- Новый --render-motion — отдельный ограниченный графический сценарий, не замена/успешное прохождение этих старых проверок.

Последующая правка скобок сняла предупреждение компилятора без изменения логики. Финальный coreBuildGame завершился успешно за 12 секунд: .darkwinter-lab/jobs/3c81c536d6da40778a56641ab7151ddc.log. Контрольные суммы EXE, raster.vert/frag, depth_resolve и cloud_resolve совпадают с build-receipt.json.

## Основные файлы

native/darkwinter-client/src/renderer.hpp
native/darkwinter-client/src/renderer.cpp
native/darkwinter-client/src/vulkan_api.hpp
native/darkwinter-client/shaders/raster.vert
native/darkwinter-client/shaders/raster.frag
native/darkwinter-client/shaders/depth_resolve.frag
native/darkwinter-client/shaders/visuals/cloud_resolve.frag
native/darkwinter-client/src/scene.hpp
native/darkwinter-client/src/scene.cpp
native/darkwinter-client/src/streaming.cpp
native/darkwinter-client/src/world_main.cpp
native/darkwinter-client/checks/generation-checks.cpp
gradle/core-world.gradle
gradle/core-client.gradle
gradle/core-game.gradle
Start-DarkWinter-Core.cmd
