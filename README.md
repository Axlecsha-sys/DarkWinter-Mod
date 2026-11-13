# DarkWinter

Minecraft **26.3**, Fabric, Java **25 x64**. [Единая документация](docs/README.md) содержит актуальные руководства по тестовому режиму, совместной игре, лаунчеру и рендеру.

**Текущая версия исходников: DarkWinter pre-0.0.3** (`0.0.3-pre` в метаданных мода). [Релизы и сборки](https://github.com/Axlecsha-sys/DarkWinter-Mod/releases).

Предыдущий выпуск **DarkWinter pre-0.0.2**: [релиз и JAR](https://github.com/Axlecsha-sys/DarkWinter-Mod/releases/tag/v1.0.1) · [изменения](docs/development/RELEASE_1.0.1_RU.md) · [проверка GUI/HUD](docs/testing/GUI_1.0.1_RU.md).

Из корня проекта на Windows x64 с установленным JDK 25:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

Первая сборка автоматически подготавливает закреплённые Zig 0.14.1 и Vulkan-Headers 1.3.296 в `build/` с проверкой целостности загрузок.

[Подготовка среды](docs/development/BUILD_RU.md) · [Онлайн](docs/online/README_RU.md) · [Лицензии](docs/provenance/README_RU.md#лицензии-и-уведомления)

## Лицензия

Исходный код мода и лаунчера распространяется по [GNU GPLv3](LICENSE) (`GPL-3.0-only`). Сторонние компоненты и ресурсы с отдельными уведомлениями сохраняют указанные в них лицензии.

