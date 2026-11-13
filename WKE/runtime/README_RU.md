# WKE — Vulkan runtime

## Встроенный backend мода

Новый модуль embedded собирается в wke_render.dll. Он заимствует VkDevice/командный буфер host, создаёт и отслеживает VkPipeline, выполняет мировые Vulkan draw-команды и освобождает pipelines после штатного GPU drain. Окно, device, очередь, targets, граф проходов, descriptors, barriers/copy/clear и presentation остаются у Minecraft.

Java/Fabric и Minecraft shader frontend лежат отдельно: modules/wke/adapter. WORLD scope охватывает renderLevel, outline и постэффекты; двумерный GUI остаётся вне него. Самостоятельный host и мод используют общий vulkan_world_commands.hpp. Старые материалы/AA/графы двух host этим не унифицируются автоматически.

Проверка: gradlew wkeRendererVerifyClient; доказательства — build/WKE/latest-renderer-client.json. Сборка без JVM: WKE/build.ps1 -Verify -Renderer. CMake-опция WKE_BUILD_EMBEDDED_RENDERER и каталог embedded независимы от старого Windows-owned renderer ниже.

## Самостоятельный Windows-owned renderer

Этот дополнительный модуль собирает техническую часть существующего C++-рендера
отдельно от самостоятельной игры. Цель CMake: `wke_runtime`; псевдоним для
потребителей: `WKE::Runtime`.

По умолчанию модуль отключён. Обычная сборка ядра WKE не требует заголовков Vulkan,
VMA, Minecraft, Fabric, Java и игровых ресурсов. При включении runtime нужны
Windows toolchain с C++20, полный каталог Vulkan-Headers/include и каталог
с `vk_mem_alloc.h`. Зависимости автоматически не скачиваются.

Пример из корня проекта:

```powershell
cmake -S WKE -B build/wke-cmake -DWKE_BUILD_WINDOWS_RUNTIME=ON -DWKE_VULKAN_INCLUDE_DIR="F:/DarkWinter/build/core/deps/Vulkan-Headers-1.3.296/include" -DWKE_VMA_INCLUDE_DIR="F:/DarkWinter/build/core/deps/VulkanMemoryAllocator-3.3.0"
cmake --build build/wke-cmake --target wke_runtime --config Release
```

Сборка библиотеки не открывает окно и не загружает игровой мир. Скомпилированный
модуль содержит renderer.cpp, scene.cpp и vma.cpp, а также статическую реализацию
snapshot/job-ядра. Для исполнения рендера приложение отдельно предоставляет
сцену, ресурсы, окно и реализацию `RenderVisualProvider`.

## Границы текущей реализации

- Рендерер пока является Windows-бэкендом с собственными Vulkan instance,
  device, surface и swapchain. Он не заменяет автоматически рендер Minecraft.
- Календарь, климат и художественный профиль поступают через интерфейс
  `RenderVisualProvider`. Реализация DarkWinter остаётся на стороне игры.
- Scene пока содержит совместимость с форматами и вариантами ресурсов DarkWinter,
  включая адаптацию стволов. Это ещё не полностью универсальная сцена.
- Метод `Scene::prepareNative` является существующим расширением самостоятельной
  игры и реализуется её streaming.cpp. Он не требуется для компиляции runtime;
  приложение, использующее этот метод, должно подключить соответствующую реализацию.
- CMake-цель создаёт статическую библиотеку. Она не включает генератор DarkWinter,
  игрока, меню, звук шагов и самостоятельный игровой цикл.
- Поддержка `RenderVisualProvider` отделяет данные окружения от GPU-исполнения;
  подключение к графическому устройству Minecraft реализовано отдельно в embedded; старый Renderer продолжает владеть своим устройством.

Флаги строгой арифметики сохраняют принятый режим вычислений исходного рендера.
Этот CMake-путь необходимо проверять отдельно от сборки существующими Gradle-задачами.
