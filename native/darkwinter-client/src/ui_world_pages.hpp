#pragma once
// Direct GUI-space port of WorldModeScreen, IllustratedModeButton and UiLayout.
// The caller owns world creation, saves and all destructive operations.
#include <cwctype>
#include <chrono>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <sstream>

namespace dw::client {
struct UiWorldPages {
    static uint32_t rgba(uint32_t rgb, uint32_t alpha = 255) {
        return ((rgb >> 16) & 255) | (rgb & 0xff00) | ((rgb & 255) << 16) | (alpha << 24);
    }
    static std::wstring tr(Ui& u, const char* key, const wchar_t* ru, const wchar_t* en) {
        return u.tr(key, ru, en);
    }
    static float size(float scale) { return std::max(1.f, 11.f * scale); }
    static void fit(Ui& u, int x, int y, int width, float scale, std::wstring value, uint32_t tint) {
        if (width <= 0 || value.empty()) return;
        const float fontSize = size(scale);
        if (u.measure(value, fontSize) > width) {
            while (!value.empty() && u.measure(value + L"…", fontSize) > width) value.pop_back();
            if (value.empty() && u.measure(L"…", fontSize) > width) return;
            value += L"…";
        }
        u.text(x, y, fontSize, value, tint);
    }
    static void paragraph(Ui& u, int x, int y, int width, float scale,
                          const std::wstring& value, uint32_t tint, int maxLines) {
        if (width <= 0 || maxLines <= 0) return;
        std::wstring line;
        int row = 0;
        auto emit = [&]() {
            if (row < maxLines) u.text(float(x), y + row * 12.f * scale, size(scale), line, tint);
            line.clear();
            ++row;
        };
        for (size_t at = 0; at < value.size() && row < maxLines;) {
            if (value[at] == L'\n') { emit(); ++at; continue; }
            if (std::iswspace(value[at])) { ++at; continue; }
            size_t end = at;
            while (end < value.size() && !std::iswspace(value[end])) ++end;
            auto word = value.substr(at, end - at);
            auto candidate = line.empty() ? word : line + L" " + word;
            if (!line.empty() && u.measure(candidate, size(scale)) > width) emit();
            if (row >= maxLines) break;
            if (!line.empty()) line += L' ';
            line += word;
            at = end;
        }
        if (!line.empty() && row < maxLines) emit();
    }
    static void logo(Ui& u, int x, int y, float scale, float tracking, int maxWidth) {
        const int width = std::min(maxWidth, int(std::lround((88.f + 8.f * tracking) * scale)));
        if (width > 0) u.picture(2, x, y, width, std::max(1, width / 8), false, rgba(0xdce8eb));
    }
    // Same crop equations as UiArt.coverZoom, using the source image dimensions.
    static void cover(Ui& u, int id, int x, int y, int width, int height,
                      float focusX, float focusY, float zoom, uint32_t tint) {
        if (id < 0 || id >= int(u.art.size()) || width <= 0 || height <= 0) return;
        const auto& a = u.art[size_t(id)];
        const double ratio = double(width) / height, z = std::clamp(double(zoom), 1., 1.5);
        const int sw = std::max(1, int(std::lround(std::min(double(a.w), a.h * ratio) / z)));
        const int sh = std::max(1, int(std::lround(std::min(double(a.h), a.w / ratio) / z)));
        const int sx = int(std::lround((a.w - sw) * std::clamp(focusX, 0.f, 1.f)));
        const int sy = int(std::lround((a.h - sh) * std::clamp(focusY, 0.f, 1.f)));
        const int x0 = int(std::lround(x * u.pixelX)), y0 = int(std::lround(y * u.pixelY));
        const int pw = std::max(1, int(std::lround((x + width) * u.pixelX)) - x0);
        const int ph = std::max(1, int(std::lround((y + height) * u.pixelY)) - y0);
        using CropKey = std::tuple<const void*, int, int, int, int, int, int, uint32_t>;
        static std::map<CropKey, std::vector<uint32_t>> crops;
        const CropKey key{a.pixels.data(), sx, sy, sw, sh, pw, ph, tint};
        auto cached = crops.find(key);
        if (cached == crops.end()) {
        // A bounded cache prevents bilinear re-sampling of static cards at every animation frame.
        if (crops.size() >= 8) crops.clear();
        std::vector<uint32_t> sampled(size_t(pw) * ph);
        for (int j = 0; j < ph; ++j) {
            const double v = std::clamp(sy + (j + .5) * sh / ph - .5, 0., double(a.h - 1));
            const int iy = int(v), ny = std::min(iy + 1, int(a.h - 1));
            const float fy = float(v - iy);
            for (int i = 0; i < pw; ++i) {
                const double s = std::clamp(sx + (i + .5) * sw / pw - .5, 0., double(a.w - 1));
                const int ix = int(s), nx = std::min(ix + 1, int(a.w - 1));
                const float fx = float(s - ix);
                uint32_t out = 0;
                for (int channel = 0; channel < 32; channel += 8) {
                    const float top = std::lerp(float((a.pixels[ix + size_t(iy) * a.w] >> channel) & 255),
                                               float((a.pixels[nx + size_t(iy) * a.w] >> channel) & 255), fx);
                    const float bottom = std::lerp(float((a.pixels[ix + size_t(ny) * a.w] >> channel) & 255),
                                                  float((a.pixels[nx + size_t(ny) * a.w] >> channel) & 255), fx);
                    uint32_t c = uint32_t(std::lround(std::lerp(top, bottom, fy)));
                    c = c * ((tint >> channel) & 255) / 255;
                    out |= c << channel;
                }
                sampled[i + size_t(j) * pw] = out;
            }
        }
        cached = crops.emplace(key, std::move(sampled)).first;
        }
        for (int j = std::max(0, -y0); j < ph && y0 + j < u.rasterH; ++j)
            for (int i = std::max(0, -x0); i < pw && x0 + i < u.rasterW; ++i)
                u.pixel(x0 + i, y0 + j, cached->second[i + size_t(j) * pw]);
    }
    static void panel(Ui& u, int x, int y, int width, int height) {
        u.surface(x, y, width, height, 3, 0, rgba(0x7897a7));
        u.rect(x + 2, y, std::max(0, std::min(18, width - 2) - 2), 1, rgba(0xc2d9e1, 166));
    }
    static void landscape(Ui& u, bool trees = false) {
        const bool full = u.settings.animated && u.settings.motion == 0;
        cover(u, trees ? 4 : 0, 0, 0, u.w, u.h,
              trees ? .46f : .5f + (full ? .04f * std::sin(u.menuTime * .035f) : 0.f), .5f, 1,
              trees ? rgba(0xa5b4c1) : rgba(0xffffff));
        if (u.settings.animated && u.settings.menuMist) for (int bank = 0; bank < 3; ++bank) {
            const float time = full ? u.menuTime : 0.f;
            const int mw = int(std::lround(u.w * (.74f + bank * .12f)));
            const int mh = std::max(36, int(std::lround(u.h * (.12f + bank * .025f))));
            const int mx = int(std::lround(u.w * .52f - mw * .5f + std::sin(time * (.019f + bank * .007f) + bank * 2.1f) * u.w * .13f));
            const int my = int(std::lround(u.h * (.5f + bank * .15f) + std::sin(time * .032f + bank) * u.h * .018f)) - mh / 2;
            u.picture(8, mx, my, mw, mh, false, rgba(0xc8d7df, uint32_t(255 * .13f * u.settings.fxStrength)));
        }
        u.rect(0, 0, u.w, u.h, trees ? rgba(0x030a13, 138) : rgba(0x040d16, 89));
        u.gradient(0, 0, u.w, u.h / 3, rgba(0x030910, 69), rgba(0x030910, 0));
        u.gradient(0, u.h * 3 / 4, u.w, u.h / 4, rgba(0x030910, 0), rgba(0x030910, 148));
        u.analog();
        u.rect(3, 3, u.w - 6, 1, rgba(0x537384, 80));
        u.rect(3, u.h - 4, u.w - 6, 1, rgba(0x537384, 64));
    }
    static void forest(Ui& u) { landscape(u, true); }
    static void background(Ui& u) {
        if (u.activeWorld) u.gradient(0, 0, u.w, u.h, rgba(0x0b1723, 39), rgba(0x040d16, 105));
        else landscape(u);
    }
    static float light(Ui& u, int id) {
        auto it = u.lights.find(id);
        return it == u.lights.end() ? (u.hover == id || u.focus == id ? 1.f : 0.f) : it->second;
    }
    static std::wstring onOff(Ui& u, bool value) {
        return value ? tr(u, "options.on", L"Вкл", L"ON") : tr(u, "options.off", L"Выкл", L"OFF");
    }
    static void footer(Ui& u, int done, int cancel, int target, bool enable = true) {
        u.addPage(done, u.w / 2 - 154, u.h - 26, 150, 20, tr(u, "gui.done", L"Готово", L"Done"), target, enable);
        u.addPage(cancel, u.w / 2 + 4, u.h - 26, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"), target);
    }
    static std::wstring lower(std::wstring value) {
        std::transform(value.begin(), value.end(), value.begin(), [](wchar_t c) { return std::towlower(c); });
        return value;
    }
    static std::wstring difficulty(Ui& u, int id) {
        switch (id) {
        case 0: return tr(u, "options.difficulty.peaceful", L"Мирная", L"Peaceful");
        case 1: return tr(u, "options.difficulty.easy", L"Лёгкая", L"Easy");
        case 3: return tr(u, "options.difficulty.hard", L"Сложная", L"Hard");
        default: return tr(u, "options.difficulty.normal", L"Нормальная", L"Normal");
        }
    }
    static std::wstring worldName(Ui& u) {
        return u.selectedWorld >= 0 && u.selectedWorld < int(u.worlds.size())
            ? u.worlds[size_t(u.selectedWorld)].name : u.worldName;
    }
    static void tabs(Ui& u, bool stats = false) {
        const int targets[] = {stats ? Ui::STATS : Ui::CREATE,
                               stats ? Ui::STATS_ITEMS : Ui::CREATE_WORLD_TAB,
                               stats ? Ui::STATS_MOBS : Ui::CREATE_MORE_TAB};
        const char* createKeys[] = {"createWorld.tab.game.title", "createWorld.tab.world.title", "createWorld.tab.more.title"};
        const wchar_t* createRu[] = {L"Игра", L"Мир", L"Прочее"};
        const wchar_t* createEn[] = {L"Game", L"World", L"More"};
        const char* statsKeys[] = {"stat.generalButton", "stat.itemsButton", "stat.mobsButton"};
        const wchar_t* statsRu[] = {L"Основное", L"Предметы", L"Мобы"};
        const wchar_t* statsEn[] = {L"General", L"Items", L"Mobs"};
        const int raw = (std::min(400, u.w) - 28) / 3;
        const int width = (raw + 1) / 2 * 2, total = width * 3;
        const int x = ((u.w - total) / 2 + 1) / 2 * 2;
        for (int i = 0; i < 3; ++i) {
            const auto caption = tr(u, stats ? statsKeys[i] : createKeys[i], stats ? statsRu[i] : createRu[i], stats ? statsEn[i] : createEn[i]);
            const int id = (stats ? 724 : 710) + i, bx = x + i * width;
            // Item/mob lists stay disabled until their native statistics exist, as in StatsScreen.
            const bool enabled = !stats || i == 0;
            u.registerButton(id, bx, 0, width, 24, caption, enabled, targets[i]);
            const float active = u.page == targets[i] ? 1.f : light(u, id);
            u.surface(bx, 0, width, 24, 9, active, rgba(0xa9dae7));
            const auto ink = enabled ? rgba(0xdce8eb) : rgba(0x93aebb, 122);
            u.text(bx + (width - u.measure(caption, 11)) / 2.f, 8, 11, caption, ink);
            if (u.page == targets[i]) {
                const int tw = u.measure(caption, 11);
                u.rect(bx + (width - tw) / 2, 21, tw, 1, rgba(0xdce8eb));
            }
        }
        u.rect(0, 23, u.w, 1, rgba(0x537384, 100));
    }
    static bool createPages(Ui& u) {
        if (u.page != Ui::CREATE && u.page != Ui::CREATE_WORLD_TAB && u.page != Ui::CREATE_MORE_TAB) return false;
        landscape(u);
        tabs(u);
        const auto ink = rgba(0xdce8eb), muted = rgba(0x93aebb);
        const int bodyHeight = u.page == Ui::CREATE_MORE_TAB ? 76 : 117;
        const int y = 24 + std::max(0, (u.h - 33 - 24 - bodyHeight) / 6);
        if (u.page == Ui::CREATE) {
            const int x = u.w / 2 - 105;
            u.text(x + 1, y, 11, tr(u, "selectWorld.enterName", L"Название мира", L"World Name"), ink);
            u.editField(400, x + 1, y + 13, 208, 20);
            const int mode = std::clamp(int(u.settings.option("create.gameMode", 0)), 0, 2);
            const char* keys[] = {"gameMode.survival", "gameMode.hardcore", "gameMode.creative"};
            const wchar_t* ru[] = {L"Выживание", L"Хардкор", L"Творческий"};
            const wchar_t* en[] = {L"Survival", L"Hardcore", L"Creative"};
            u.add(241, x, y + 41, 210, 20, tr(u, "selectWorld.gameMode", L"Режим игры", L"Game Mode") + L": " + tr(u, keys[mode], ru[mode], en[mode]));
            u.add(242, x, y + 69, 210, 20, tr(u, "options.difficulty", L"Сложность", L"Difficulty") + L": " + difficulty(u, int(u.settings.option("create.difficulty", 2))), mode != 1);
            u.add(243, x, y + 97, 210, 20, tr(u, "selectWorld.allowCommands", L"Использование команд", L"Allow Commands") + L": " + onOff(u, u.settings.option("create.commands", 0) != 0), mode != 1);
        } else if (u.page == Ui::CREATE_WORLD_TAB) {
            const int x = (u.w - 310) / 2;
            const int type = std::clamp(int(u.settings.option("create.worldType", 4)), 0, 4);
            const char* keys[] = {"generator.minecraft.normal", "generator.minecraft.flat", "generator.minecraft.large_biomes", "generator.minecraft.amplified", "generator.minecraft.single_biome_surface"};
            const wchar_t* ru[] = {L"По умолчанию", L"Суперплоскость", L"Большие биомы", L"Расширенный", L"Один биом"};
            const wchar_t* en[] = {L"Default", L"Superflat", L"Large Biomes", L"Amplified", L"Single Biome"};
            u.add(244, x, y, 150, 20, tr(u, "selectWorld.mapType", L"Тип мира", L"World Type") + L": " + tr(u, keys[type], ru[type], en[type]));
            u.addPage(713, x + 160, y, 150, 20, tr(u, "selectWorld.customizeType", L"Настроить", L"Customize"), type == 1 ? Ui::WORLD_FLAT_CUSTOMIZE : Ui::WORLD_BIOME_CUSTOMIZE, type == 1 || type == 4);
            u.text(x, y + 28, 11, tr(u, "selectWorld.enterSeed", L"Ключ для генератора мира", L"Seed for the World Generator"), ink);
            u.editField(401, x, y + 41, 308, 20, tr(u, "selectWorld.seedInfo", L"Оставьте пустым для случайного ключа", L"Leave blank for a random seed"));
            u.text(x, y + 79, 11, tr(u, "selectWorld.mapFeatures", L"Генерация строений", L"Generate Structures"), ink);
            u.add(245, x + 266, y + 73, 44, 20, onOff(u, u.settings.option("create.structures", 1) != 0));
            u.text(x, y + 103, 11, tr(u, "selectWorld.bonusItems", L"Бонусный сундук", L"Bonus Chest"), ink);
            u.add(246, x + 266, y + 97, 44, 20, onOff(u, u.settings.option("create.bonusChest", 0) != 0), int(u.settings.option("create.gameMode", 0)) != 1);
        } else {
            const int x = u.w / 2 - 105;
            u.addPage(714, x, y, 210, 20, tr(u, "selectWorld.gameRules", L"Игровые правила", L"Game Rules"), Ui::WORLD_GAME_RULES);
            u.addPage(715, x, y + 28, 210, 20, tr(u, "selectWorld.experiments", L"Эксперименты", L"Experiments"), Ui::WORLD_EXPERIMENTS);
            u.addPage(716, x, y + 56, 210, 20, tr(u, "selectWorld.dataPacks", L"Наборы данных", L"Data Packs"), Ui::WORLD_DATA_PACKS);
        }
        if (!u.status.empty()) paragraph(u, u.w / 2 - 210, u.h - 65, 420, .8f, u.status, muted, 2);
        u.rect(0, u.h - 34, u.w, 1, rgba(0x537384, 80));
        u.add(301, u.w / 2 - 154, u.h - 26, 150, 20, tr(u, "selectWorld.create", L"Создать новый мир", L"Create New World"), !u.worldName.empty());
        u.add(302, u.w / 2 + 4, u.h - 26, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"));
        return true;
    }
    static void worldList(Ui& u) {
        forest(u);
        const int left = std::max(20, std::min(60, int(std::lround(u.w * .075f))));
        const int available = u.w - left * 2;
        const bool compact = u.w < 480;
        u.text(left, 18, 11, tr(u, "selectWorld.title", L"Выбор мира", L"Select World"), rgba(0xdce8eb));
        u.editField(403, left, 43, std::min(240, available), 20, tr(u, "selectWorld.search", L"Поиск миров", L"Search worlds"));
        const int createWidth = std::min(178, available / 2);
        u.add(201, u.w - left - createWidth, 14, createWidth, 21, L"+ " + tr(u, "selectWorld.create", L"Создать новый мир", L"Create New World"));
        std::vector<int> visible;
        const auto needle = lower(u.fieldValue(403));
        for (size_t i = 0; i < u.worlds.size(); ++i)
            if (needle.empty() || lower(u.worlds[i].name).find(needle) != std::wstring::npos) visible.push_back(int(i));
        const int top = 72, listHeight = std::max(40, u.h - top - (compact ? 57 : 35) - 8);
        const int rowHeight = u.h >= 330 ? 54 : 40, rowWidth = std::min(640, u.w - left * 2), x = (u.w - rowWidth) / 2;
        u.scrollStep = rowHeight;
        u.setScrollRegion(0, top, u.w, listHeight, int(visible.size()) * rowHeight + 4);
        u.setClip(0, top, u.w, listHeight);
        for (size_t v = 0; v < visible.size(); ++v) {
            const int y = top + 2 + int(v) * rowHeight - u.optionScroll;
            if (y >= top + listHeight || y + rowHeight <= top) continue;
            const int index = visible[v], id = 1000 + index;
            const auto& row = u.worlds[size_t(index)];
            const bool selected = u.selectedWorld == index;
            u.registerButton(id, x, y, rowWidth, rowHeight, row.name);
            u.gradient(x, y + 2, rowWidth, rowHeight - 4, rgba(0x13212e, 139), rgba(0x050e18, 174));
            u.rect(x, y + 2, rowWidth, 1, rgba(0x4d6b7c, 85));
            u.rect(x, y + rowHeight - 3, rowWidth, 1, rgba(0x4d6b7c, 85));
            u.rect(x, y + 2, 1, rowHeight - 4, rgba(0x4d6b7c, 56));
            u.rect(x + rowWidth - 1, y + 2, 1, rowHeight - 4, rgba(0x4d6b7c, 56));
            if (u.hover == id) u.gradient(x, y + 2, rowWidth, rowHeight - 4, rgba(0x476679, 56), rgba(0x233243, 24));
            if (selected) {
                u.surface(x, y + 2, rowWidth, rowHeight - 4, 6, u.focus == id ? 1.f : .25f, rgba(u.focus == id ? 0xa9dae7 : 0x85acbe));
                u.rect(x + 3, y + 5, 1, rowHeight - 10, rgba(0xdce8eb));
            }
            // Existing native saves do not contain Minecraft's icon.png. Preserve the empty thumbnail area.
            u.surface(x + 4, y + 4, 84, rowHeight - 8, 3, 0, rgba(0x7897a7));
            if (u.hover == id) {
                const int ax = x + 36, ay = y + rowHeight / 2;
                u.rect(ax - 7, ay, 13, 1, rgba(0xdce8eb));
                for (int a = 0; a < 5; ++a) {
                    u.rect(ax + a, ay - 4 + a, 1, 1, rgba(0xdce8eb));
                    u.rect(ax + a, ay + 4 - a, 1, 1, rgba(0xdce8eb));
                }
            }
            fit(u, x + 94, y + 6, std::max(40, rowWidth - 166), 1, row.name, rgba(0xdce8eb));
            fit(u, x + 94, y + 22, std::max(40, rowWidth - 166), 1, L"DarkWinter", rgba(0x93aebb));
            const auto systemTime = std::chrono::time_point_cast<std::chrono::system_clock::duration>(std::chrono::file_clock::to_sys(row.modified));
            const std::time_t timestamp = std::chrono::system_clock::to_time_t(systemTime);
            std::tm date{};
            if (localtime_s(&date, &timestamp) == 0) {
                wchar_t day[32]{}, hour[16]{};
                std::wcsftime(day, 32, L"%d.%m.%Y", &date);
                std::wcsftime(hour, 16, L"%H:%M", &date);
                u.text(x + rowWidth - 66, y + 10, size(.85f), day, rgba(0x93aebb));
                u.text(x + rowWidth - 31, y + 24, size(.85f), hour, rgba(0x93aebb));
            }
        }
        u.resetClip();
        u.scrollBar();
        const bool selected = u.selectedWorld >= 0 && u.selectedWorld < int(u.worlds.size());
        const int playWidth = compact ? available : int(std::lround(available * .31f));
        u.add(202, left, u.h - (compact ? 56 : 31), playWidth, compact ? 20 : 22,
              tr(u, "selectWorld.select", L"Играть в выбранном мире", L"Play Selected World"), selected);
        const int otherWidth = compact ? (available - 18) / 4 : (available - playWidth - 24) / 4;
        const int first = compact ? left : left + playWidth + 6, bottom = u.h - (compact ? 29 : 31);
        u.add(221, first, bottom, std::max(32, otherWidth), compact ? 20 : 22, tr(u, "selectWorld.edit", L"Настроить", L"Edit"), selected);
        u.add(222, first + otherWidth + 6, bottom, std::max(32, otherWidth), compact ? 20 : 22, tr(u, "selectWorld.delete", L"Удалить", L"Delete"), selected);
        u.add(220, first + 2 * (otherWidth + 6), bottom, std::max(32, otherWidth), compact ? 20 : 22, tr(u, "selectWorld.recreate", L"Пересоздать", L"Re-Create"), selected);
        u.add(203, first + 3 * (otherWidth + 6), bottom, std::max(32, otherWidth), compact ? 20 : 22, tr(u, "gui.back", L"Назад", L"Back"));
    }
    static bool editPages(Ui& u) {
        if (u.page == Ui::WORLD_EDIT) {
            landscape(u);
            const int x = u.w / 2 - 100, y = u.h / 2 - 92;
            u.text(x, y, 11, tr(u, "selectWorld.enterName", L"Название мира", L"World Name"), rgba(0xdce8eb));
            u.editField(404, x, y + 14, 200, 20);
            u.add(227, x, y + 39, 200, 20, tr(u, "selectWorld.edit.resetIcon", L"Сбросить иконку", L"Reset Icon"));
            u.add(226, x, y + 64, 200, 20, tr(u, "selectWorld.edit.openFolder", L"Открыть папку мира", L"Open World Folder"));
            u.add(225, x, y + 89, 200, 20, tr(u, "selectWorld.edit.backup", L"Создать резервную копию", L"Make Backup"));
            u.add(229, x, y + 114, 200, 20, tr(u, "selectWorld.edit.backupFolder", L"Открыть папку резервных копий", L"Open Backups Folder"));
            u.add(228, x, y + 139, 200, 20, tr(u, "selectWorld.edit.optimize", L"Оптимизировать мир", L"Optimize World"));
            u.add(223, x, y + 189, 98, 20, tr(u, "selectWorld.edit.save", L"Сохранить", L"Save"), !u.fieldValue(404).empty());
            u.addPage(717, x + 102, y + 189, 98, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"), Ui::WORLDS);
            if (!u.status.empty()) paragraph(u, x - 70, y + 218, 340, .8f, u.status, rgba(0x93aebb), 3);
            return true;
        }
        if (u.page == Ui::WORLD_DELETE_CONFIRM) {
            landscape(u);
            const auto title = tr(u, "selectWorld.deleteQuestion", L"Вы действительно хотите удалить этот мир?", L"Are you sure you want to delete this world?");
            u.centered(u.h / 2 - 35, 11, title, rgba(0xdce8eb));
            auto warning = tr(u, "selectWorld.deleteWarning", L"«%s» будет удалён навсегда! (Безвозвратно!)", L"'%s' will be lost forever! (A long time!)");
            auto at = warning.find(L"%s");
            if (at != std::wstring::npos) warning.replace(at, 2, worldName(u));
            u.centered(u.h / 2 - 18, 11, warning, rgba(0x93aebb));
            u.add(247, u.w / 2 - 152, u.h / 2 + 15, 150, 20, tr(u, "selectWorld.deleteButton", L"Удалить", L"Delete"));
            u.addPage(718, u.w / 2 + 2, u.h / 2 + 15, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"), Ui::WORLDS);
            return true;
        }
        if (u.page == Ui::WORLD_OPTIMIZE || u.page == Ui::WORLD_BACKUP) {
            landscape(u);
            u.centered(26, 11, tr(u, "selectWorld.edit.optimize", L"Оптимизировать мир", L"Optimize World"), rgba(0xdce8eb));
            paragraph(u, u.w / 2 - 170, 55, 340, 1,
                      tr(u, "native.world.optimize.info", L"Этот мир хранится в формате DarkWinter. Оптимизация миров Minecraft к нему не применяется. Можно создать резервную копию текущего сохранения.", L"This world uses the DarkWinter format. Minecraft world optimization does not apply. You can make a backup of the current save."), rgba(0x93aebb), 4);
            u.add(225, u.w / 2 - 155, 136, 150, 20, tr(u, "selectWorld.edit.backup", L"Создать резервную копию", L"Make Backup"));
            u.addPage(719, u.w / 2 + 5, 136, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"), Ui::WORLD_EDIT);
            if (!u.status.empty()) paragraph(u, u.w / 2 - 170, 177, 340, .8f, u.status, rgba(0x93aebb), 3);
            return true;
        }
        return false;
    }
    struct Rule {
        std::string id, category, type, label, description;
        long long initial = 0, minimum = 0, maximum = 1;
        int field = -1;
    };
    struct FlatLayer { std::string block; int count = 1; };
    struct FlatPreset { const char* id; const char* layers; const wchar_t* ru; const wchar_t* en; };
    static const std::array<FlatPreset, 9>& flatPresets() {
        // Captured from the nine stock 26.3 PresetFlatWorldScreen selections, without creating a world.
        static const std::array<FlatPreset, 9> values{{
            {"classic_flat", "minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block;minecraft:plains", L"Классическая плоскость", L"Classic Flat"},
            {"tunnelers_dream", "minecraft:bedrock,230*minecraft:stone,5*minecraft:dirt,minecraft:grass_block;minecraft:windswept_hills", L"Мечта метростроевца", L"Tunnelers' Dream"},
            {"water_world", "minecraft:bedrock,64*minecraft:deepslate,5*minecraft:stone,5*minecraft:dirt,5*minecraft:gravel,90*minecraft:water;minecraft:deep_ocean", L"Водный мир", L"Water World"},
            {"overworld", "minecraft:bedrock,59*minecraft:stone,3*minecraft:dirt,minecraft:grass_block;minecraft:plains", L"Обычный мир", L"Overworld"},
            {"snowy_kingdom", "minecraft:bedrock,59*minecraft:stone,3*minecraft:dirt,minecraft:grass_block,minecraft:snow;minecraft:snowy_plains", L"Снежное царство", L"Snowy Kingdom"},
            {"bottomless_pit", "2*minecraft:cobblestone,3*minecraft:dirt,minecraft:grass_block;minecraft:plains", L"Бездна", L"Bottomless Pit"},
            {"desert", "minecraft:bedrock,3*minecraft:stone,52*minecraft:sandstone,8*minecraft:sand;minecraft:desert", L"Пустыня", L"Desert"},
            {"redstone_ready", "minecraft:bedrock,3*minecraft:stone,116*minecraft:sandstone;minecraft:desert", L"Редстоун-мастерская", L"Redstone Ready"},
            {"the_void", "minecraft:air;minecraft:the_void", L"Пустота", L"The Void"}
        }};
        return values;
    }
    struct Draft {
        const Ui* owner = nullptr;
        int lastPage = -1, rulesParent = Ui::CREATE_MORE_TAB;
        std::vector<Rule> rules;
        std::vector<std::pair<std::string, std::string>> biomes;
        std::map<std::string, long long> ruleValues, initialRuleValues;
        std::string biome;
        std::array<bool, 3> experiments{};
        std::vector<FlatLayer> layers;
        std::string flatBiome = "minecraft:plains";
        int selectedLayer = -1, selectedPreset = -1;
        std::vector<std::string> statistics;
        std::map<std::string, double> world, initialWorld;
        int initialPort = 0;
    };
    static Draft& draft() { static Draft value; return value; }
    static bool integer(const std::wstring& value, long long& out) {
        if (value.empty() || value.size() > 20) return false;
        try {
            size_t count = 0;
            out = std::stoll(value, &count);
            return count == value.size();
        } catch (...) { return false; }
    }
    static void syncDraft(Ui& u) {
        auto& d = draft();
        if (d.owner != &u) {
            d = Draft{};
            d.owner = &u;
            std::ifstream input(u.fontPath.parent_path() / L"ui-game-rules.tsv");
            std::string line;
            int field = 410;
            while (std::getline(input, line)) {
                if (line.empty() || line.front() == '#') continue;
                std::array<std::string, 8> cells;
                std::istringstream row(line);
                bool valid = true;
                for (auto& cell : cells) if (!(row >> std::quoted(cell))) { valid = false; break; }
                if (!valid || cells[0].empty() || (cells[2] != "boolean" && cells[2] != "integer")) continue;
                try {
                    Rule rule;
                    rule.id = cells[0]; rule.category = cells[1]; rule.type = cells[2];
                    rule.initial = rule.type == "boolean" ? (cells[3] == "true" ? 1 : 0) : std::stoll(cells[3]);
                    rule.minimum = std::stoll(cells[4]); rule.maximum = std::stoll(cells[5]);
                    rule.label = cells[6]; rule.description = cells[7];
                    if (rule.type == "integer" && field < 450) rule.field = field++;
                    d.rules.push_back(std::move(rule));
                } catch (...) { /* Reject malformed metadata instead of inventing a rule. */ }
            }
            std::sort(d.rules.begin(), d.rules.end(), [](const Rule& a, const Rule& b) {
                return a.category == b.category ? a.id < b.id : a.category < b.category;
            });
            auto english = u.translations.find("en_us");
            if (english != u.translations.end()) for (const auto& [key, label] : english->second) {
                constexpr const char* prefix = "biome.minecraft.";
                if (key.starts_with(prefix)) d.biomes.emplace_back("minecraft:" + key.substr(16), key);
                if (key.starts_with("stat.minecraft.") || key.starts_with("stat.darkwinter.")) d.statistics.push_back(key);
            }
        }
        if (d.lastPage == u.page) return;
        if (u.page == Ui::WORLD_GAME_RULES && d.lastPage != Ui::WORLD_RULES_DISCARD_CONFIRM) {
            d.rulesParent = u.activeWorld ? Ui::WORLD_OPTIONS : Ui::CREATE_MORE_TAB;
            d.ruleValues.clear();
            for (const auto& rule : d.rules) {
                const auto value = static_cast<long long>(std::clamp(
                    u.settings.option("rule." + rule.id, double(rule.initial)), double(rule.minimum), double(rule.maximum)));
                d.ruleValues.emplace(rule.id, value);
                if (rule.field >= 0) u.textFields[rule.field] = std::to_wstring(value);
            }
            d.initialRuleValues = d.ruleValues;
            clearStage(u, "world.ruleDraft.");
            u.textFields[405].clear();
        }
        if (u.page == Ui::WORLD_BIOME_CUSTOMIZE) {
            d.biome = u.settings.textOption("create.biome", "minecraft:snowy_taiga");
            u.textFields[406].clear();
        }
        if (u.page == Ui::WORLD_EXPERIMENTS || u.page == Ui::WORLD_DATA_PACKS) {
            const char* ids[] = {"minecart_improvements", "redstone_experiments", "trade_rebalance"};
            for (int i = 0; i < 3; ++i) d.experiments[size_t(i)] = u.settings.option(std::string("create.experiment.") + ids[i], 0) != 0;
            if (u.page == Ui::WORLD_DATA_PACKS) u.textFields[407].clear();
        }
        if (u.page == Ui::WORLD_FLAT_CUSTOMIZE && d.lastPage != Ui::WORLD_FLAT_PRESETS) {
            if (!parseFlat(u.settings.textOption("create.flat", flatPresets()[0].layers), d.layers, d.flatBiome))
                parseFlat(flatPresets()[0].layers, d.layers, d.flatBiome);
            d.selectedLayer = -1;
        }
        if (u.page == Ui::WORLD_OPTIONS && d.lastPage != Ui::WORLD_GAME_RULES && d.lastPage != Ui::WORLD_RESTRICTIONS
                && d.lastPage != Ui::WORLD_DIFFICULTY_LOCK_CONFIRM && d.lastPage != Ui::WORLD_RULES_DISCARD_CONFIRM) {
            auto value = [&](const char* key, double fallback, int maximum) {
                const double stored = u.settings.option(key, fallback);
                return std::isfinite(stored) ? std::clamp(std::trunc(stored), 0., double(maximum)) : fallback;
            };
            // Creation uses survival/hardcore/creative; the in-world selector uses all four GameTypes.
            const int creationMode = int(value("create.gameMode", 0, 2));
            const double mode = value("world.gameMode", creationMode == 2 ? 1 : 0, 3);
            d.world = {{"gameMode", mode}, {"personalGameMode", value("world.personalGameMode", mode, 3)},
                       {"commands", value("world.commands", value("create.commands", 0, 1), 1)},
                       {"difficulty", value("world.difficulty", value("create.difficulty", 2, 3), 3)},
                       {"difficultyLocked", creationMode == 1 ? 1 : value("world.difficultyLocked", 0, 1)},
                       {"lan", 0}, {"guestCommands", 0}, {"forceGameMode", 1}};
            d.initialWorld = d.world;
            d.initialPort = 0;
            u.textFields[409].clear();
            clearStage(u, "world.draft.");
            u.status.clear();
        }
        d.lastPage = u.page;
    }
    static bool parseFlat(const std::string& value, std::vector<FlatLayer>& output, std::string& biome) {
        const auto separator = value.find(';');
        if (separator == std::string::npos || value.size() > 4096) return false;
        std::vector<FlatLayer> layers;
        std::istringstream stream(value.substr(0, separator));
        std::string part;
        int total = 0;
        while (std::getline(stream, part, ',')) {
            const auto star = part.find('*');
            int count = 1;
            if (star != std::string::npos) {
                try { size_t used = 0; count = std::stoi(part.substr(0, star), &used); if (used != star) return false; }
                catch (...) { return false; }
                part = part.substr(star + 1);
            }
            if (count <= 0 || count > 384 || total > 384 - count || part.empty() || part.size() > 128) return false;
            if (part.find(':') == std::string::npos) part = "minecraft:" + part;
            if (part.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789_:./-") != std::string::npos) return false;
            layers.push_back({part, count});
            total += count;
        }
        auto biomeValue = value.substr(separator + 1);
        if (layers.empty() || biomeValue.empty() || biomeValue.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789_:./-") != std::string::npos) return false;
        output = std::move(layers); biome = std::move(biomeValue);
        return true;
    }
    static std::string flatString(const Draft& d) {
        std::string value;
        for (const auto& layer : d.layers) {
            if (!value.empty()) value += ',';
            if (layer.count != 1) value += std::to_string(layer.count) + '*';
            value += layer.block;
        }
        return value + ';' + d.flatBiome;
    }
    static void clearStage(Ui& u, const char* prefix) {
        std::erase_if(u.settings.options, [prefix](const auto& row) { return row.first.starts_with(prefix); });
        std::erase_if(u.settings.textOptions, [prefix](const auto& row) { return row.first.starts_with(prefix); });
    }
    static void replaceStage(Ui& u, const char* prefix, const std::map<std::string, double>& staged) {
        // Validate and build first: allocation failure cannot leave a partially staged transaction.
        auto values = u.settings.options;
        auto texts = u.settings.textOptions;
        std::erase_if(values, [prefix](const auto& row) { return row.first.starts_with(prefix); });
        std::erase_if(texts, [prefix](const auto& row) { return row.first.starts_with(prefix); });
        values.insert(staged.begin(), staged.end());
        u.settings.options.swap(values);
        u.settings.textOptions.swap(texts);
    }
    static bool worldPort(Ui& u, long long& port) {
        port = 0;
        if (draft().world.at("lan") == 0) return true;
        const auto& input = u.fieldValue(409);
        // Blank means automatic selection. The unavailable LAN backend receives the sentinel 0.
        if (std::all_of(input.begin(), input.end(), [](wchar_t c) { return std::iswspace(c); })) return true;
        if (input.size() > 32) return false;
        size_t offset = input.front() == L'+' ? 1 : 0;
        if (offset == input.size()) return false;
        for (; offset < input.size(); ++offset) {
            const wchar_t c = input[offset];
            if (c < L'0' || c > L'9') return false;
            port = port * 10 + (c - L'0');
            if (port > 65535) return false;
        }
        return port >= 1024;
    }
    static bool worldChanged(Ui& u) {
        const auto& d = draft();
        if (d.world != d.initialWorld) return true;
        long long port = 0;
        return d.world.at("lan") != 0 && d.initialWorld.at("lan") != 0
            && worldPort(u, port) && port != d.initialPort;
    }
    static bool worldApplyEnabled(Ui& u) {
        long long port = 0;
        return worldChanged(u) && worldPort(u, port);
    }
    static bool stageWorld(Ui& u, bool lockConfirmed) {
        long long port = 0;
        if (!worldPort(u, port)) {
            u.status = tr(u, "native.world.port.invalid", L"Порт должен быть числом от 1024 до 65535 или оставаться пустым.", L"Enter a port from 1024 to 65535, or leave it blank.");
            u.dirty = true;
            return false;
        }
        std::map<std::string, double> staged;
        for (const auto& [key, value] : draft().world) staged.emplace("world.draft." + key, value);
        staged.emplace("world.draft.port", double(port));
        staged.emplace("world.draft.changed", worldChanged(u) ? 1 : 0);
        staged.emplace("world.draft.lockConfirmed", lockConfirmed ? 1 : 0);
        replaceStage(u, "world.draft.", staged);
        return true;
    }
    static bool hardcore(Ui& u) { return u.settings.option("create.gameMode", 0) == 1; }
    static const char* worldControlKey(int id) {
        static constexpr const char* keys[] = {"gameMode", "personalGameMode", "commands", "difficulty", "difficultyLocked", "lan", "guestCommands", "forceGameMode"};
        return id >= 252 && id <= 259 ? keys[id - 252] : "";
    }
    static bool worldControlEnabled(Ui& u, int id) {
        const auto& d = draft();
        switch (id) {
            case 252: case 253: return !hardcore(u) && d.world.at("commands") != 0;
            case 254: return !hardcore(u) || d.initialWorld.at("commands") != 0;
            case 255: case 256: return !hardcore(u) && d.initialWorld.at("difficultyLocked") == 0;
            case 257: return true;
            case 258: return d.world.at("lan") != 0 && d.world.at("commands") != 0;
            case 259: return d.world.at("lan") != 0 && !hardcore(u) && d.world.at("guestCommands") == 0;
            default: return false;
        }
    }
    static void updateGuestControls(Ui& u, bool updateAccess) {
        auto& d = draft();
        const bool lan = d.world.at("lan") != 0;
        if (updateAccess) d.world["guestCommands"] = lan && d.world.at("commands") != 0 ? d.initialWorld.at("guestCommands") : 0;
        d.world["forceGameMode"] = hardcore(u) || !lan ? 1
            : d.world.at("guestCommands") != 0 ? 0 : d.initialWorld.at("forceGameMode");
    }
    static void buttonValue(Ui& u, int id, const std::string& key, double value) {
        if (u.buttons.empty() || u.buttons.back().id != id) return;
        auto& button = u.buttons.back();
        button.optionKey = key;
        button.hasValue = true;
        button.value = value;
    }
    static void worldButton(Ui& u, int id, int x, int y, int width, const std::wstring& label) {
        u.add(id, x, y, width, 20, label, worldControlEnabled(u, id));
        const auto* key = worldControlKey(id);
        buttonValue(u, id, std::string("world.") + key, draft().world.at(key));
    }
    static void ruleFieldEdited(Ui& u, int field) {
        if (u.page != Ui::WORLD_GAME_RULES || field < 410 || field >= 450) return;
        auto& d = draft();
        if (d.owner != &u) return;
        for (const auto& rule : d.rules) if (rule.field == field) {
            long long value = 0;
            if (integer(u.fieldValue(field), value) && value >= rule.minimum && value <= rule.maximum)
                d.ruleValues[rule.id] = value;
            return;
        }
    }
    static bool rulesValid(Ui& u) {
        auto& d = draft();
        bool valid = !d.rules.empty();
        for (const auto& rule : d.rules) if (rule.type == "integer") {
            long long value = 0;
            if (rule.field < 0 || !integer(u.fieldValue(rule.field), value) || value < rule.minimum || value > rule.maximum) valid = false;
            else d.ruleValues[rule.id] = value;
        }
        return valid;
    }
    static bool rulesChanged(Ui& u) {
        // Java compares parsed values, retaining the last valid value while an EditBox is invalid.
        rulesValid(u);
        const auto& d = draft();
        return d.ruleValues != d.initialRuleValues;
    }
    static void rulesPage(Ui& u) {
        background(u);
        u.centered(4, 11, tr(u, "editGamerule.title", L"Настройка игровых правил", L"Edit Game Rules"), rgba(0xdce8eb));
        u.editField(405, u.w / 2 - 100, 17, 200, 15, tr(u, "gui.search", L"Поиск", L"Search"));
        auto& d = draft();
        struct Item { int rule; std::string category; };
        std::vector<Item> items;
        std::string category;
        const auto search = lower(u.fieldValue(405));
        for (size_t i = 0; i < d.rules.size(); ++i) {
            const auto& rule = d.rules[i];
            const auto name = lower(u.tr(rule.label, std::wstring(rule.id.begin(), rule.id.end())));
            if (!search.empty() && name.find(search) == std::wstring::npos && lower(std::wstring(rule.id.begin(), rule.id.end())).find(search) == std::wstring::npos) continue;
            if (category != rule.category) { category = rule.category; items.push_back({-1, category}); }
            items.push_back({int(i), category});
        }
        const int height = std::max(20, u.h - 69), x = u.w / 2 - 110;
        u.scrollStep = 24;
        u.setScrollRegion(0, 36, u.w, height, int(items.size()) * 24 + 4);
        u.setClip(0, 36, u.w, height);
        for (size_t row = 0; row < items.size(); ++row) {
            const int y = 38 + int(row) * 24 - u.optionScroll;
            if (y >= 36 + height || y + 24 <= 36) continue;
            const auto& item = items[row];
            if (item.rule < 0) {
                u.centered(y + 4, 11, u.tr("gamerule.category.minecraft." + item.category,
                           std::wstring(item.category.begin(), item.category.end())), rgba(0xdce8eb));
                continue;
            }
            const auto& rule = d.rules[size_t(item.rule)];
            const auto label = u.tr(rule.label, std::wstring(rule.id.begin(), rule.id.end()));
            paragraph(u, x, y + 2, 165, 1, label, rgba(0xdce8eb), 2);
            const int id = 14000 + item.rule;
            if (rule.type == "boolean") {
                u.add(id, x + 173, y + 2, 44, 20, onOff(u, d.ruleValues[rule.id] != 0));
                buttonValue(u, id, "rule." + rule.id, double(d.ruleValues[rule.id]));
            } else if (rule.field >= 0) {
                u.editField(rule.field, x + 173, y + 2, 44, 20);
                long long value = 0;
                if (!integer(u.fieldValue(rule.field), value) || value < rule.minimum || value > rule.maximum)
                    u.rect(x + 174, y + 20, 42, 1, rgba(0xd05a60));
            }
            if (!u.buttons.empty()) {
                auto& button = u.buttons.back();
                button.tooltip = label;
                if (!rule.description.empty()) button.tooltip += L"\n" + u.tr(rule.description, L"");
            }
        }
        u.resetClip();
        u.scrollBar();
        if (d.rules.empty()) paragraph(u, x - 60, 76, 340, 1,
            tr(u, "native.rules.unavailable", L"Каталог игровых правил не найден в ресурсах игры.", L"The game-rule catalogue is missing from the game resources."), rgba(0x93aebb), 3);
        u.add(740, u.w / 2 - 154, u.h - 26, 150, 20, tr(u, "gui.done", L"Готово", L"Done"), rulesValid(u));
        u.add(741, u.w / 2 + 4, u.h - 26, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"));
    }
    static void biomePage(Ui& u) {
        landscape(u);
        u.centered(7, 11, tr(u, "createWorld.customize.buffet.title", L"Настройка одного биома", L"Customize Single Biome"), rgba(0xdce8eb));
        u.editField(406, u.w / 2 - 100, 19, 200, 15, tr(u, "gui.search", L"Поиск", L"Search"));
        auto& d = draft();
        std::vector<std::pair<int, std::wstring>> entries;
        const auto needle = lower(u.fieldValue(406));
        for (size_t i = 0; i < d.biomes.size(); ++i) {
            const auto& [id, key] = d.biomes[i];
            auto label = u.tr(key, std::wstring(id.begin(), id.end()));
            if (needle.empty() || lower(label).find(needle) != std::wstring::npos) entries.emplace_back(int(i), std::move(label));
        }
        std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) {
            return CompareStringEx(LOCALE_NAME_USER_DEFAULT, SORT_STRINGSORT, a.second.c_str(), -1, b.second.c_str(), -1, nullptr, nullptr, 0) == CSTR_LESS_THAN;
        });
        const int x = u.w / 2 - 110, height = std::max(20, u.h - 73);
        u.scrollStep = 15;
        u.setScrollRegion(0, 40, u.w, height, int(entries.size()) * 15);
        u.setClip(0, 40, u.w, height);
        for (size_t i = 0; i < entries.size(); ++i) {
            const int y = 40 + int(i) * 15 - u.optionScroll;
            if (y >= 40 + height || y + 15 <= 40) continue;
            const int index = entries[i].first, id = 15000 + index;
            const bool selected = d.biome == d.biomes[size_t(index)].first;
            u.registerButton(id, x, y, 220, 15, entries[i].second);
            if (selected) u.surface(x, y, 220, 15, 6, u.focus == id ? 1.f : .25f, rgba(0xa9dae7));
            fit(u, x + 2, y + 2, 216, 1, entries[i].second, rgba(0xdce8eb));
        }
        u.resetClip();
        u.scrollBar();
        if (d.biomes.empty()) paragraph(u, x - 60, 76, 340, 1,
            tr(u, "native.biomes.unavailable", L"Каталог биомов не найден в ресурсах игры.", L"The biome catalogue is missing from the game resources."), rgba(0x93aebb), 3);
        u.add(742, u.w / 2 - 154, u.h - 26, 150, 20, tr(u, "gui.done", L"Готово", L"Done"), !d.biomes.empty());
        u.add(743, u.w / 2 + 4, u.h - 26, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"));
    }
    static bool activate(Ui& u, int id) {
        syncDraft(u);
        auto& d = draft();
        if (u.page == Ui::WORLD_GAME_RULES) {
            if (id >= 14000 && id < 14000 + int(d.rules.size())) {
                const auto& rule = d.rules[size_t(id - 14000)];
                if (rule.type != "boolean") return false;
                d.ruleValues[rule.id] = d.ruleValues[rule.id] ? 0 : 1;
                u.dirty = true;
                return true;
            }
            if (id == 740) {
                if (!rulesValid(u)) return true;
                if (u.activeWorld) {
                    std::map<std::string, double> staged;
                    for (const auto& [key, value] : d.ruleValues)
                        if (value != d.initialRuleValues.at(key)) staged.emplace("world.ruleDraft." + key, double(value));
                    replaceStage(u, "world.ruleDraft.", staged);
                    // Done applies rules independently of the outer World Options transaction.
                    return false;
                }
                for (const auto& [key, value] : d.ruleValues) u.settings.setOption("rule." + key, double(value));
                u.backToPage(d.rulesParent);
                return true;
            }
            if (id == 741) {
                if (u.activeWorld && rulesChanged(u)) u.openPage(Ui::WORLD_RULES_DISCARD_CONFIRM);
                else { clearStage(u, "world.ruleDraft."); u.backToPage(d.rulesParent); }
                return true;
            }
        }
        if (u.page == Ui::WORLD_BIOME_CUSTOMIZE) {
            if (id >= 15000 && id < 15000 + int(d.biomes.size())) {
                d.biome = d.biomes[size_t(id - 15000)].first;
                u.dirty = true;
                return true;
            }
            if (id == 742) {
                u.settings.setTextOption("create.biome", d.biome);
                u.backToPage(Ui::CREATE_WORLD_TAB);
                return true;
            }
            if (id == 743) { u.backToPage(Ui::CREATE_WORLD_TAB); return true; }
        }
        if (u.page == Ui::WORLD_EXPERIMENTS || u.page == Ui::WORLD_DATA_PACKS) {
            if (id >= 744 && id <= 746) {
                auto& enabled = d.experiments[size_t(id - 744)];
                enabled = !enabled;
                u.dirty = true;
                return true;
            }
            if (id == 747) {
                const char* names[] = {"minecart_improvements", "redstone_experiments", "trade_rebalance"};
                for (int i = 0; i < 3; ++i) u.settings.setOption(std::string("create.experiment.") + names[i], d.experiments[size_t(i)] ? 1 : 0);
                u.backToPage(Ui::CREATE_MORE_TAB);
                return true;
            }
            if (id == 748) { u.backToPage(Ui::CREATE_MORE_TAB); return true; }
        }
        if (u.page == Ui::WORLD_FLAT_CUSTOMIZE) {
            if (id >= 16000 && id < 16000 + int(d.layers.size())) {
                d.selectedLayer = id - 16000; u.dirty = true; return true;
            }
            if (id == 753 && d.selectedLayer >= 0 && d.selectedLayer < int(d.layers.size())) {
                d.layers.erase(d.layers.begin() + d.selectedLayer); d.selectedLayer = -1; u.dirty = true; return true;
            }
            if (id == 754) {
                auto value = flatString(d);
                u.textFields[408] = std::wstring(value.begin(), value.end());
                d.selectedPreset = -1;
                u.openPage(Ui::WORLD_FLAT_PRESETS); return true;
            }
            if (id == 751) { u.settings.setTextOption("create.flat", flatString(d)); u.backToPage(Ui::CREATE_WORLD_TAB); return true; }
            if (id == 752) { u.backToPage(Ui::CREATE_WORLD_TAB); return true; }
        }
        if (u.page == Ui::WORLD_FLAT_PRESETS) {
            if (id >= 16100 && id < 16109) {
                const auto value = std::string(flatPresets()[size_t(id - 16100)].layers);
                u.textFields[408] = std::wstring(value.begin(), value.end());
                d.selectedPreset = id - 16100; u.dirty = true; return true;
            }
            if (id == 755) {
                const auto& input = u.fieldValue(408);
                const std::string value(input.begin(), input.end());
                std::vector<FlatLayer> layers; std::string biome;
                if (!parseFlat(value, layers, biome)) {
                    u.status = tr(u, "native.flat.invalid", L"Не удалось прочитать строку слоёв и биома.", L"The layer and biome string could not be parsed.");
                    u.dirty = true; return true;
                }
                const auto dictionary = u.translations.find("en_us");
                if (dictionary != u.translations.end()) for (const auto& layer : layers) {
                    auto key = "block." + layer.block; std::replace(key.begin(), key.end(), ':', '.');
                    if (!dictionary->second.contains(key)) {
                        u.status = tr(u, "native.flat.unknown", L"В строке указан неизвестный блок.", L"The layer string contains an unknown block.");
                        u.dirty = true; return true;
                    }
                }
                d.layers = std::move(layers); d.flatBiome = std::move(biome); d.selectedLayer = -1;
                u.backToPage(Ui::WORLD_FLAT_CUSTOMIZE); return true;
            }
            if (id == 756) { u.backToPage(Ui::WORLD_FLAT_CUSTOMIZE); return true; }
        }
        if (u.page == Ui::WORLD_RULES_DISCARD_CONFIRM) {
            if (id == 262) { clearStage(u, "world.ruleDraft."); u.backToPage(d.rulesParent); return true; }
            if (id == 263) { u.backToPage(Ui::WORLD_GAME_RULES); return true; }
        }
        if (u.page == Ui::WORLD_DIFFICULTY_LOCK_CONFIRM) {
            if (id == 260) {
                const bool staged = stageWorld(u, true);
                u.backToPage(Ui::WORLD_OPTIONS);
                return !staged;
            }
            if (id == 261) { u.backToPage(Ui::WORLD_OPTIONS); return true; }
        }
        if (u.page == Ui::WORLD_OPTIONS) {
            if (id >= 252 && id <= 259) {
                if (!worldControlEnabled(u, id)) return true;
                auto& value = d.world[worldControlKey(id)];
                value = (int(value) + 1) % ((id == 252 || id == 253 || id == 255) ? 4 : 2);
                if (id == 254) {
                    if (value == 0) {
                        d.world["gameMode"] = d.initialWorld.at("gameMode");
                        d.world["personalGameMode"] = d.world.at("gameMode");
                    }
                    updateGuestControls(u, true);
                } else if (id == 257) {
                    updateGuestControls(u, true);
                    u.textFields[409] = value != 0 && d.initialWorld.at("lan") != 0 ? std::to_wstring(d.initialPort) : L"";
                    if (value == 0 && u.focusedField == 409) u.focusedField = -1;
                } else if (id == 258) updateGuestControls(u, false);
                u.dirty = true; return true;
            }
            if (id == 251) { clearStage(u, "world.draft."); u.backToPage(Ui::PAUSE); return true; }
            if (id == 250) {
                if (!worldApplyEnabled(u)) return true;
                if (d.world.at("difficultyLocked") != d.initialWorld.at("difficultyLocked")) {
                    u.openPage(Ui::WORLD_DIFFICULTY_LOCK_CONFIRM);
                    return true;
                }
                // Only the application's native backend may validate/apply these staged changes.
                return !stageWorld(u, false);
            }
        }
        return false;
    }
    static std::wstring replaceValue(std::wstring format, const std::wstring& value) {
        auto at = format.find(L"%s");
        if (at != std::wstring::npos) format.replace(at, 2, value);
        return format;
    }
    static bool flatPages(Ui& u) {
        if (u.page != Ui::WORLD_FLAT_CUSTOMIZE && u.page != Ui::WORLD_FLAT_PRESETS) return false;
        landscape(u);
        auto& d = draft();
        const int x = u.w / 2 - 110;
        if (u.page == Ui::WORLD_FLAT_CUSTOMIZE) {
            u.centered(12, 11, tr(u, "createWorld.customize.flat.title", L"Параметры суперплоскости", L"Superflat Customization"), rgba(0xdce8eb));
            const auto material = tr(u, "createWorld.customize.flat.tile", L"Материал слоя", L"Layer Material");
            const auto height = tr(u, "createWorld.customize.flat.height", L"Высота", L"Height");
            u.scrollStep = 24;
            u.setScrollRegion(0, 33, u.w, std::max(24, u.h - 97), 17 + int(d.layers.size()) * 24);
            u.setClip(0, 33, u.w, std::max(24, u.h - 97));
            const int head = 35 - u.optionScroll;
            u.text(x + 2, head, 11, material, rgba(0xdce8eb));
            u.text(x + 218 - u.measure(height, 11), head, 11, height, rgba(0xdce8eb));
            u.rect(x + 2, head + 10, u.measure(material, 11), 1, rgba(0xdce8eb));
            u.rect(x + 218 - u.measure(height, 11), head + 10, u.measure(height, 11), 1, rgba(0xdce8eb));
            for (size_t row = 0; row < d.layers.size(); ++row) {
                const int index = int(d.layers.size() - 1 - row), y = 48 + int(row) * 24 - u.optionScroll;
                const auto& layer = d.layers[size_t(index)];
                auto key = "block." + layer.block; std::replace(key.begin(), key.end(), ':', '.');
                const auto label = u.tr(key, std::wstring(layer.block.begin(), layer.block.end()));
                u.registerButton(16000 + index, x, y, 220, 24, label);
                if (index == d.selectedLayer) u.surface(x, y, 220, 24, 6, .5f, rgba(0xa9dae7));
                u.surface(x + 3, y + 3, 18, 18, 3, 0, rgba(0x7897a7));
                u.text(x + 25, y + 7, 11, label, rgba(0xdce8eb));
                auto amount = std::to_wstring(layer.count);
                if (row == 0) amount = replaceValue(tr(u, "createWorld.customize.flat.layer.top", L"Поверхность — %s", L"Top - %s"), amount);
                else if (index == 0) amount = replaceValue(tr(u, "createWorld.customize.flat.layer.bottom", L"Нижний слой — %s", L"Bottom - %s"), amount);
                u.text(x + 218 - u.measure(amount, 11), y + 7, 11, amount, rgba(0xdce8eb));
            }
            u.resetClip(); u.scrollBar();
            u.add(753, u.w / 2 - 154, u.h - 54, 150, 20, tr(u, "createWorld.customize.flat.removeLayer", L"Удалить слой", L"Remove Layer"), d.selectedLayer >= 0);
            u.add(754, u.w / 2 + 4, u.h - 54, 150, 20, tr(u, "createWorld.customize.presets", L"Шаблоны", L"Presets"));
            u.add(751, u.w / 2 - 154, u.h - 30, 150, 20, tr(u, "gui.done", L"Готово", L"Done"));
            u.add(752, u.w / 2 + 4, u.h - 30, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"));
        } else {
            u.centered(8, 11, tr(u, "createWorld.customize.presets.title", L"Выбор шаблона", L"Select a Preset"), rgba(0xdce8eb));
            u.text(50, 30, 11, tr(u, "createWorld.customize.presets.share", L"Хотите поделиться своим шаблоном? Используйте поле ниже!", L"Want to share your preset with someone? Use the box below!"), rgba(0x93aebb));
            u.editField(408, 50, 40, u.w - 100, 20);
            u.text(50, 68, 11, tr(u, "createWorld.customize.presets.list", L"Или можете воспользоваться готовыми шаблонами!", L"Alternatively, here's some we made earlier!"), rgba(0x93aebb));
            u.scrollStep = 24;
            u.setScrollRegion(0, 80, u.w, std::max(24, u.h - 117), 9 * 24 + 4);
            u.setClip(0, 80, u.w, std::max(24, u.h - 117));
            for (int i = 0; i < 9; ++i) {
                const auto& p = flatPresets()[size_t(i)];
                const int y = 82 + i * 24 - u.optionScroll;
                const auto caption = u.tr(std::string("flat_world_preset.minecraft.") + p.id, p.ru, p.en);
                u.registerButton(16100 + i, x, y, 220, 24, caption);
                if (i == d.selectedPreset) u.surface(x, y, 220, 24, 6, .5f, rgba(0xa9dae7));
                u.surface(x + 3, y + 3, 18, 18, 3, 0, rgba(0x7897a7));
                u.text(x + 25, y + 7, 11, caption, rgba(0xdce8eb));
            }
            u.resetClip(); u.scrollBar();
            u.add(755, u.w / 2 - 155, u.h - 28, 150, 20, tr(u, "createWorld.customize.presets.select", L"Использовать", L"Use Preset"), !u.fieldValue(408).empty());
            u.add(756, u.w / 2 + 5, u.h - 28, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"));
            if (!u.status.empty()) paragraph(u, 50, u.h - 55, u.w - 100, .8f, u.status, rgba(0x93aebb), 2);
        }
        return true;
    }
    static std::wstring gameMode(Ui& u, int mode) {
        const char* keys[] = {"selectWorld.gameMode.survival", "selectWorld.gameMode.creative", "selectWorld.gameMode.adventure", "selectWorld.gameMode.spectator"};
        const wchar_t* ru[] = {L"Выживание", L"Творческий", L"Приключение", L"Наблюдатель"};
        const wchar_t* en[] = {L"Survival", L"Creative", L"Adventure", L"Spectator"};
        mode = std::clamp(mode, 0, 3);
        return tr(u, keys[mode], ru[mode], en[mode]);
    }
    static std::vector<std::wstring> dialogLines(Ui& u, const std::wstring& value, int width, size_t maximum = 15) {
        std::vector<std::wstring> lines;
        std::wstring line, word;
        auto emit = [&]() { if (lines.size() < maximum) lines.push_back(line); line.clear(); };
        for (size_t i = 0; i <= value.size() && lines.size() < maximum; ++i) {
            const wchar_t c = i == value.size() ? L'\n' : value[i];
            if (std::iswspace(c)) {
                if (!word.empty()) {
                    const auto candidate = line.empty() ? word : line + L" " + word;
                    if (!line.empty() && u.measure(candidate, 11) > width) emit();
                    if (lines.size() >= maximum) break;
                    if (!line.empty()) line += L' ';
                    line += word;
                    word.clear();
                }
                if (c == L'\n') emit();
            } else word += c;
        }
        return lines;
    }
    static void worldConfirmation(Ui& u) {
        const bool locking = u.page == Ui::WORLD_DIFFICULTY_LOCK_CONFIRM;
        if (locking) {
            // PopupScreen paints the World Options screen beneath a transparent dark overlay.
            const int page = u.page, scroll = u.optionScroll;
            u.page = Ui::WORLD_OPTIONS;
            const auto previous = u.scrollPositions.find(Ui::WORLD_OPTIONS);
            u.optionScroll = previous == u.scrollPositions.end() ? 0 : previous->second;
            worldOptions(u);
            u.page = page; u.optionScroll = scroll;
            u.buttons.clear();
            u.scrollWidth = u.scrollHeight = u.scrollMaximum = 0;
            u.resetClip();
            u.gradient(0, 0, u.w, u.h, rgba(0x101010, 192), rgba(0x101010, 208));
        } else background(u);
        const auto title = locking
            ? tr(u, "difficulty.lock.title", L"Фиксация сложности мира", L"Lock World Difficulty")
            : tr(u, "editGamerule.inGame.discardChanges.title", L"Отменить изменения?", L"Discard Changes?");
        const auto message = locking
            ? replaceValue(tr(u, "difficulty.lock.question",
                L"Вы уверены, что хотите зафиксировать сложность этого мира? Сложность «%s» больше нельзя будет изменить.",
                L"Are you sure you want to lock the difficulty of this world? This will set this world to always be %s, and you will never be able to change that again."),
                difficulty(u, int(draft().world.at("difficulty"))))
            : tr(u, "editGamerule.inGame.discardChanges.message", L"Изменения игровых правил будут потеряны.", L"Your game rule changes will be lost.");
        const int width = locking ? std::min(214, std::max(40, u.w - 52)) : std::max(40, u.w - 50);
        const auto titleLines = dialogLines(u, title, width);
        const auto messageLines = dialogLines(u, message, width);
        const int gap = locking ? 12 : 8, padding = locking ? 0 : 16;
        const int titleHeight = int(titleLines.size()) * 9, messageHeight = int(messageLines.size()) * 9;
        const int height = titleHeight + gap + messageHeight + gap + padding + 20;
        const int top = (u.h - height) / 2;
        if (locking) panel(u, u.w / 2 - width / 2 - 18, top - 18, width + 36, height + 36);
        for (size_t i = 0; i < titleLines.size(); ++i) u.centered(top + int(i) * 9, 11, titleLines[i], rgba(0xdce8eb));
        for (size_t i = 0; i < messageLines.size(); ++i) u.centered(top + titleHeight + gap + int(i) * 9, 11, messageLines[i], rgba(0xdce8eb));
        const int buttonGap = locking ? 6 : 4, buttonWidth = locking ? std::min(150, (width - buttonGap) / 2) : 150;
        const int x = u.w / 2 - (buttonWidth * 2 + buttonGap) / 2, y = top + height - 20;
        u.add(locking ? 260 : 262, x, y, buttonWidth, 20, tr(u, "gui.yes", L"Да", L"Yes"));
        u.add(locking ? 261 : 263, x + buttonWidth + buttonGap, y, buttonWidth, 20, tr(u, "gui.no", L"Нет", L"No"));
    }
    static bool worldOptions(Ui& u) {
        if (u.page == Ui::WORLD_DIFFICULTY_LOCK_CONFIRM || u.page == Ui::WORLD_RULES_DISCARD_CONFIRM) {
            worldConfirmation(u);
            return true;
        }
        if (u.page == Ui::WORLD_RESTRICTIONS) {
            background(u);
            u.centered(12, 11, tr(u, "restrictions_screen.title", L"Ограничения", L"Restrictions"), rgba(0xdce8eb));
            const char* permissions[] = {"send_messages", "send_commands", "receive_system_messages", "receive_player_messages"};
            const wchar_t* deniedRu[] = {L"Вы не можете отправлять сообщения в чат", L"Вы не можете отправлять команды", L"Вы не можете получать системные сообщения от сервера", L"Вы не можете получать сообщения от игроков"};
            const wchar_t* deniedEn[] = {L"You can't send chat messages", L"You can't send commands", L"You can't receive system messages from the server", L"You can't receive messages from players"};
            std::wstring lines;
            for (int i = 0; i < 4; ++i) {
                if (i) lines += L'\n';
                lines += u.tr(std::string("restrictions_screen.permission.") + permissions[i] + ".denied", deniedRu[i], deniedEn[i]);
            }
            paragraph(u, u.w / 2 - 125, 63, 250, 1, lines, rgba(0xdce8eb), 8);
            u.addPage(757, u.w / 2 - 100, u.h - 26, 200, 20, tr(u, "gui.done", L"Готово", L"Done"), Ui::WORLD_OPTIONS);
            return true;
        }
        if (u.page != Ui::WORLD_OPTIONS) return false;
        background(u);
        auto& d = draft();
        u.centered(12, 11, tr(u, "options.worldOptions.title", L"Настройки мира", L"World Options"), rgba(0xdce8eb));
        const int x = u.w / 2 - 154;
        u.scrollStep = 24;
        u.setScrollRegion(u.w / 2 - 172, 33, 344, std::max(24, u.h - 66), 222);
        u.setClip(u.w / 2 - 172, 33, 344, std::max(24, u.h - 66));
        const int off = u.optionScroll;
        u.centered(41 - off, 11, tr(u, "options.worldOptions.general.title", L"Основные", L"General"), rgba(0xdce8eb));
        worldButton(u, 252, x, 62 - off, 308, tr(u, "options.worldOptions.game_mode", L"Стандартный режим игры", L"Default Game Mode") + L": " + gameMode(u, int(d.world["gameMode"])));
        worldButton(u, 253, x, 86 - off, 308, tr(u, "options.worldOptions.personal_game_mode", L"Личный режим игры", L"Personal Game Mode") + L": " + gameMode(u, int(d.world["personalGameMode"])));
        worldButton(u, 254, x, 110 - off, 150, tr(u, "selectWorld.allowCommands", L"Использование команд", L"Allow Commands") + L": " + onOff(u, d.world["commands"] != 0));
        worldButton(u, 255, x + 158, 110 - off, 130, tr(u, "options.difficulty", L"Сложность", L"Difficulty") + L": " + difficulty(u, int(d.world["difficulty"])));
        const bool lockEnabled = worldControlEnabled(u, 256);
        u.registerButton(256, x + 288, 110 - off, 20, 20, tr(u, "narrator.button.difficulty_lock", L"Фиксация сложности", L"Difficulty Lock"), lockEnabled);
        buttonValue(u, 256, "world.difficultyLocked", d.world.at("difficultyLocked"));
        u.surface(x + 288, 110 - off, 20, 20, lockEnabled ? 0 : 7, lockEnabled ? light(u, 256) : 0, rgba(0xa9dae7));
        const int lx = x + 294, ly = 116 - off;
        u.rect(lx, ly + 3, 8, 6, rgba(0xdce8eb));
        u.rect(lx + 1, ly, 6, 1, rgba(0xdce8eb));
        u.rect(lx + 1, ly, 1, 4, rgba(0xdce8eb));
        if (d.world["difficultyLocked"] != 0) u.rect(lx + 6, ly, 1, 4, rgba(0xdce8eb));
        else u.rect(lx + 6, ly, 1, 2, rgba(0xdce8eb));
        u.addPage(758, x, 134 - off, 150, 20, tr(u, "editGamerule.inGame.button", L"Игровые правила...", L"Game Rules..."), Ui::WORLD_GAME_RULES,
                  !hardcore(u) && d.initialWorld.at("commands") != 0);
        u.addPage(759, x + 158, 134 - off, 150, 20, tr(u, "restrictions_screen.button", L"Ограничения...", L"Restrictions..."), Ui::WORLD_RESTRICTIONS);
        u.centered(178 - off, 11, tr(u, "options.worldOptions.multiplayer.title", L"Сетевая игра", L"Multiplayer"), rgba(0xdce8eb));
        const bool lan = d.world["lan"] != 0;
        worldButton(u, 257, x, 199 - off, 150, tr(u, "menu.multiplayerOptions.lan", L"Локальная сеть", L"LAN") + L": " + onOff(u, lan));
        if (lan) {
            u.editField(409, x + 158, 199 - off, 150, 20, tr(u, "lanServer.port", L"Порт", L"Port"));
            long long port = 0;
            if (!worldPort(u, port)) u.rect(x + 159, 217 - off, 148, 1, rgba(0xd05a60));
        } else {
            const auto label = tr(u, "lanServer.port", L"Порт", L"Port");
            u.registerButton(409, x + 158, 199 - off, 150, 20, label, false);
            u.surface(x + 158, 199 - off, 150, 20, 2, 0, rgba(0xa9dae7));
            u.text(x + 163, 204 - off, 11, label, rgba(0x93aebb, 122));
            if (u.focusedField == 409) u.focusedField = -1;
        }
        if (!u.buttons.empty() && u.buttons.back().id == 409) {
            u.buttons.back().kind = 2;
            u.buttons.back().optionKey = "world.port";
        }
        worldButton(u, 258, x, 223 - off, 150, tr(u, "options.worldOptions.guest.command_access", L"Доступ к командам", L"Command Access") + L": " + onOff(u, d.world["guestCommands"] != 0));
        worldButton(u, 259, x + 158, 223 - off, 150, tr(u, "options.worldOptions.guest.force_game_mode", L"Менять режим при входе", L"Force Game Mode") + L": " + onOff(u, d.world["forceGameMode"] != 0));
        u.resetClip(); u.scrollBar();
        if (!u.status.empty()) paragraph(u, x - 36, u.h - 77, 380, .8f, u.status, rgba(0x93aebb), 3);
        u.add(250, x, u.h - 26, 150, 20, tr(u, "menu.multiplayerOptions.applyChanges", L"Применить", L"Apply Changes"), worldApplyEnabled(u));
        u.add(251, x + 158, u.h - 26, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"));
        return true;
    }
    static std::wstring decimal(double value, int places = 2) {
        std::wostringstream stream; stream << std::fixed << std::setprecision(places) << value; return stream.str();
    }
    static std::wstring statisticValue(Ui& u, const std::string& key, double seconds) {
        const auto id = key.substr(key.find('.', 5) + 1);
        double raw = u.settings.option(key, 0);
        if (id == "jump") raw = double(u.sessionJumps);
        else if (id == "walk_one_cm") raw = std::round(u.sessionDistance * 100);
        else if (id == "total_world_time") raw = std::max(0., seconds * 20);
        if (id.ends_with("_one_cm")) {
            const double meters = raw / 100, kilometers = meters / 1000;
            if (kilometers > .5) return decimal(kilometers) + L" km";
            if (meters > .5) return decimal(meters) + L" m";
            return std::to_wstring(static_cast<long long>(raw)) + L" cm";
        }
        if (id.ends_with("_time") || id.starts_with("time_since_")) {
            double s = raw / 20, minute = s / 60, hour = minute / 60, day = hour / 24, year = day / 365;
            if (year > .5) return decimal(year) + L" y";
            if (day > .5) return decimal(day) + L" d";
            if (hour > .5) return decimal(hour) + L" h";
            if (minute > .5) return decimal(minute) + L" min";
            auto value = decimal(s);
            if (value.ends_with(L"0")) value.pop_back();
            return value + L" s";
        }
        if (id.starts_with("damage_")) return decimal(raw * .1);
        return std::to_wstring(static_cast<long long>(raw));
    }
    static bool statistics(Ui& u, double seconds) {
        if (u.page != Ui::STATS && u.page != Ui::STATS_ITEMS && u.page != Ui::STATS_MOBS) return false;
        background(u); tabs(u, true);
        std::vector<std::pair<std::string, std::wstring>> entries;
        if (u.page == Ui::STATS) for (const auto& key : draft().statistics) entries.emplace_back(key, u.tr(key, std::wstring(key.begin(), key.end())));
        std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) { return a.second < b.second; });
        const int x = u.w / 2 - 140, height = std::max(20, u.h - 57);
        u.scrollStep = 14;
        u.setScrollRegion(0, 24, u.w, height, int(entries.size()) * 14 + 4);
        u.setClip(0, 24, u.w, height);
        for (size_t i = 0; i < entries.size(); ++i) {
            const int y = 26 + int(i) * 14 - u.optionScroll;
            if (y >= 24 + height || y + 14 <= 24) continue;
            const auto ink = i % 2 ? rgba(0xbababa) : rgba(0xffffff);
            u.registerButton(17000 + int(i), x, y, 280, 14, entries[i].second);
            if (u.hover == 17000 + int(i)) u.rect(x, y, 280, 14, rgba(0x7897a7, 25));
            fit(u, x + 4, y + 3, 246, 1, entries[i].second, ink);
            const auto amount = statisticValue(u, entries[i].first, seconds);
            u.text(x + 274 - u.measure(amount, 11), y + 3, 11, amount, ink);
        }
        u.resetClip();
        // Native stats use a scrollbar alongside the 280-unit table, not at the screen edge.
        u.scrollX = x; u.scrollWidth = 294; u.scrollBar();
        u.addPage(727, u.w / 2 - 100, u.h - 26, 200, 20, tr(u, "gui.done", L"Готово", L"Done"), Ui::PAUSE);
        return true;
    }
    static std::wstring experimentName(Ui& u, int index) {
        const char* ids[] = {"minecart_improvements", "redstone_experiments", "trade_rebalance"};
        const wchar_t* ru[] = {L"Улучшения вагонеток", L"Изменения редстоуна", L"Балансировка торговли с крестьянами"};
        const wchar_t* en[] = {L"Minecart Improvements", L"Redstone Experiments", L"Villager Trade Rebalance"};
        return u.tr(std::string("dataPack.") + ids[index] + ".name", ru[index], en[index]);
    }
    static std::wstring experimentDescription(Ui& u, int index) {
        const char* ids[] = {"minecart_improvements", "redstone_experiments", "trade_rebalance"};
        const wchar_t* ru[] = {L"Улучшенное движение вагонеток", L"Экспериментальные изменения редстоуна", L"Обновлённые сделки крестьян"};
        const wchar_t* en[] = {L"Improved minecart movement", L"Experimental changes to redstone", L"Updated trades for villagers"};
        return u.tr(std::string("dataPack.") + ids[index] + ".description", ru[index], en[index]);
    }
    static bool packPages(Ui& u) {
        if (u.page == Ui::WORLD_EXPERIMENTS) {
            landscape(u);
            u.centered(12, 11, tr(u, "selectWorld.experiments", L"Эксперименты", L"Experiments"), rgba(0xdce8eb));
            const int x = u.w / 2 - 165, top = 33 + std::max(0, (u.h - 66 - 168) / 8);
            paragraph(u, x, top, 310, 1,
                tr(u, "selectWorld.experiments.info", L"Эксперименты — это будущие нововведения. Учтите, что они могут работать нестабильно. После создания мира, эксперименты отключить нельзя.", L"Experiments are potential future features. Be aware that they may be unstable. Once the world is created, experiments cannot be disabled."), rgba(0x93aebb), 3);
            for (int i = 0; i < 3; ++i) {
                const int y = top + 42 + i * 42;
                u.text(x + 10, y + 10, 11, experimentName(u, i), rgba(0xdce8eb));
                u.add(744 + i, x + 265, y + 4, 44, 20, onOff(u, draft().experiments[size_t(i)]));
                paragraph(u, x + 10, y + 24, 250, 1, experimentDescription(u, i), rgba(0x93aebb), 2);
            }
            u.add(747, u.w / 2 - 154, u.h - 26, 150, 20, tr(u, "gui.done", L"Готово", L"Done"));
            u.add(748, u.w / 2 + 4, u.h - 26, 150, 20, tr(u, "gui.cancel", L"Отмена", L"Cancel"));
            return true;
        }
        if (u.page == Ui::WORLD_DATA_PACKS) {
            landscape(u);
            u.centered(4, 11, tr(u, "dataPack.title", L"Выбор наборов данных", L"Select Data Packs"), rgba(0xdce8eb));
            u.centered(17, 11, tr(u, "pack.dropInfo", L"Перетащите файлы в это окно, чтобы добавить наборы", L"Drag and drop files into this window to add packs"), rgba(0x93aebb));
            u.editField(407, u.w / 2 - 101, 30, 200, 15, tr(u, "gui.search", L"Поиск", L"Search"));
            const int xs[] = {u.w / 2 - 215, u.w / 2 + 15};
            const auto needle = lower(u.fieldValue(407));
            u.setClip(0, 49, u.w, std::max(20, u.h - 82));
            for (int column = 0; column < 2; ++column) {
                const int x = xs[column];
                u.gradient(x, 49, 200, std::max(20, u.h - 82), rgba(0x13212e, 115), rgba(0x050e18, 154));
                u.rect(x, 49, 200, 1, rgba(0x4d6b7c, 85));
                u.rect(x, u.h - 33, 200, 1, rgba(0x4d6b7c, 85));
                auto title = column ? tr(u, "pack.selected.title", L"Выбранные", L"Selected") : tr(u, "pack.available.title", L"Доступные", L"Available");
                u.text(x + (200 - u.measure(title, 11)) / 2.f, 51, 11, title, rgba(0xdce8eb));
                u.rect(x + (200 - u.measure(title, 11)) / 2, 61, u.measure(title, 11), 1, rgba(0xdce8eb));
                int y = 64;
                for (int i = 0; i < 3; ++i) {
                    if (draft().experiments[size_t(i)] != (column == 1)) continue;
                    const wchar_t* names[] = {L"minecart_improvements", L"redstone_experiments", L"trade_rebalance"};
                    const auto label = std::wstring(names[i]);
                    if (!needle.empty() && lower(label).find(needle) == std::wstring::npos) continue;
                    u.registerButton(744 + i, x + 2, y, 196, 36, label);
                    if (u.hover == 744 + i) u.surface(x + 2, y, 196, 36, 6, .5f, rgba(0xa9dae7));
                    if (u.art.size() > 35) u.picture(35, x + 4, y + 2, 32, 32);
                    else u.surface(x + 4, y + 2, 32, 32, 3, 0, rgba(0x7897a7));
                    const wchar_t* arrow = column ? L"‹" : L"›";
                    if (u.hover == 744 + i) u.text(x + 14, y + 11, 11, arrow, rgba(0xdce8eb));
                    fit(u, x + 39, y + 2, 153, 1, label, rgba(0xdce8eb));
                    paragraph(u, x + 39, y + 15, 153, 1, experimentDescription(u, i) + L" (" + tr(u, "pack.source.feature", L"функция", L"feature") + L")", rgba(0x93aebb), 2);
                    y += 36;
                }
                if (column == 1 && (needle.empty() || lower(L"Minecraft").find(needle) != std::wstring::npos)) {
                    if (u.art.size() > 36) u.picture(36, x + 4, y + 2, 32, 32);
                    else u.surface(x + 4, y + 2, 32, 32, 3, 0, rgba(0x7897a7));
                    fit(u, x + 39, y + 2, 153, 1, tr(u, "dataPack.vanilla.name", L"Стандартный", L"Default"), rgba(0xdce8eb));
                    paragraph(u, x + 39, y + 15, 153, 1, tr(u, "dataPack.vanilla.description", L"Стандартный набор данных Minecraft", L"The default data for Minecraft") + L" (" + tr(u, "pack.source.builtin", L"встроенный", L"built-in") + L")", rgba(0x93aebb), 2);
                }
            }
            u.resetClip();
            u.add(248, u.w / 2 - 154, u.h - 26, 150, 20, tr(u, "pack.openFolder", L"Папка с наборами", L"Open Pack Folder"));
            u.add(747, u.w / 2 + 4, u.h - 26, 150, 20, tr(u, "gui.done", L"Готово", L"Done"));
            if (!u.status.empty()) paragraph(u, u.w / 2 - 205, u.h - 72, 410, .8f, u.status, rgba(0x93aebb), 2);
            return true;
        }
        return false;
    }
    static void card(Ui& u, int id, int x, int y, int width, int height, bool story) {
        const auto title = story ? tr(u, "menu.darkwinter.story.short", L"История", L"Story")
                                 : tr(u, "menu.darkwinter.free.short", L"Свободный режим", L"Free mode");
        const auto semantic = story ? tr(u, "menu.darkwinter.story", L"История / DarkWinter", L"Story / DarkWinter")
                                    : tr(u, "menu.darkwinter.free", L"Свободный режим / Minecraft", L"Free mode / Minecraft");
        u.registerButton(id, x, y, width, height, semantic, true, story ? Ui::STORY_SETUP : -1);
        const float light = UiWorldPages::light(u, id);
        int pictureHeight = std::max(22, std::min(int(std::lround(height * .49f)), height - 94));
        if (height < 146) pictureHeight = std::max(22, int(std::lround(height * .42f)));
        u.gradient(x, y, width, height, rgba(0x182b39, 213), rgba(0x040d17, 243));
        const uint32_t r = uint32_t(std::lround(208 + 47 * light));
        const uint32_t g = uint32_t(std::lround(214 + 41 * light));
        const uint32_t b = uint32_t(std::lround(221 + 34 * light));
        cover(u, story ? 4 : 5, x + 1, y + 1, width - 2, pictureHeight,
              story ? .5f : .67f, story ? .64f : .5f, story ? 1.18f : 1.f,
              rgba((r << 16) | (g << 8) | b));
        u.gradient(x + 1, y + pictureHeight - 16, width - 2, 18, rgba(0x050d17, 0), rgba(0x050d17, 203));
        const auto edge = light > .5f ? rgba(0xdce8eb) : rgba(0x698899);
        u.rect(x, y, width, 1, edge); u.rect(x, y + height - 1, width, 1, edge);
        u.rect(x, y, 1, height, edge); u.rect(x + width - 1, y, 1, height, edge);
        u.rect(x + 1, y + pictureHeight + 1, width - 2, 1, (edge & 0xffffff) | (89u << 24));
        const int titleY = y + pictureHeight + 9;
        const bool badge = width >= 130;
        if (badge) u.picture(story ? 33 : 34, x + 9, titleY - 1, 14, 14, false, rgba(0xdce8eb));
        const int titleLeft = badge ? 29 : 10;
        fit(u, x + titleLeft, titleY, width - titleLeft - 10,
            std::max(.92f, std::min(1.08f, width / 200.f)), title, rgba(0xdce8eb));
        const float body = std::min(.90f, std::max(.78f, width / 265.f));
        const int summaryY = titleY + 18;
        const int limit = std::max(0, int((y + height - 26 - summaryY) / (body * 12.f)));
        paragraph(u, x + 10, summaryY, width - 22, body,
                  story ? tr(u, "menu.darkwinter.story.card", L"Северная Канада. Заснеженная тайга. Выживание и исследование. Сюжетная кампания в разработке.", L"Northern Canada. Snow-covered taiga. Survival and exploration. Story campaign in development.")
                        : tr(u, "menu.darkwinter.free.card", L"Мир Minecraft с системами выживания DarkWinter. Обычная генерация и полный выбор настроек.", L"Minecraft with DarkWinter survival systems. Standard generation and full world settings."),
                  rgba(0xb2c8d3), limit);
        const int ax = x + width - 23 + int(std::lround(light * 3)), ay = y + height - 17;
        if (height > 196) u.rect(x + 10, y + height - 29, width - 20, 1, rgba(0x537384, 64));
        u.rect(ax - 7, ay + 3, 10, 1, edge);
        for (int i = 0; i < 4; ++i) { u.rect(ax + i, ay + i, 1, 1, edge); u.rect(ax + i, ay + 6 - i, 1, 1, edge); }
        if (light > .01f) u.gradient(x + 2, y + pictureHeight - 5, width - 4, 5, rgba(0xa9dae7, uint32_t(light * .26f * 255)), rgba(0x9bd9ed, 0));
    }
    static bool render(Ui& u, const Scene* scene, double seconds) {
        (void)scene; (void)seconds;
        syncDraft(u);
        if (u.page == Ui::WORLDS) { worldList(u); return true; }
        if (createPages(u) || editPages(u) || packPages(u) || flatPages(u) || worldOptions(u) || statistics(u, seconds)) return true;
        if (u.page == Ui::WORLD_GAME_RULES) { rulesPage(u); return true; }
        if (u.page == Ui::WORLD_BIOME_CUSTOMIZE) { biomePage(u); return true; }
        if (u.page == Ui::WORLD_MODE) {
            forest(u);
            const int margin = std::max(12, std::min(24, u.w / 24));
            const int half = std::max(76, std::min(220, (u.w - 2 * margin - 14) / 2));
            const int height = std::max(88, std::min(int(std::lround(half * 1.10f)), u.h - 92));
            const int y = std::max(38, (u.h - height) / 2 - 1);
            const int width = std::max(70, std::min(200, (u.w - 68) / 3));
            const int total = width * 3 + 20, x = (u.w - total) / 2;
            logo(u, x, std::max(3, y - 35), 1.4f, 2.2f, total);
            fit(u, x, std::max(3, y - 35) + 23, total, .84f,
                tr(u, "menu.darkwinter.choose", L"Выберите свой путь", L"Choose your path"), rgba(0x93aebb));
            card(u, 700, x, y, width, height, true);
            card(u, 233, x + width + 10, y, width, height, false);
            u.addPage(701, x + 2 * (width + 10), y, width, height,
                      tr(u, "menu.darkwinter.test.short", L"Тестирование", L"Testing"), Ui::TEST_SETUP);
            u.addPage(702, u.w / 2 - 60, u.h - 27, 120, 20,
                      tr(u, "gui.back", L"Назад", L"Back"), Ui::WORLDS);
            return true;
        }
        if (u.page == Ui::STORY_SETUP) {
            forest(u);
            const int width = std::max(180, std::min(384, u.w - 40)), x = (u.w - width) / 2;
            const int image = std::max(20, std::min(76, u.h - 180));
            const int selector = u.h < 260 ? 18 : 22, configure = u.h < 260 ? 25 : 32, continuation = u.h < 260 ? 22 : 28;
            const int height = image + 8 + selector + 8 + configure + 12 + continuation;
            const int y = std::max(36, (u.h - height) / 2 - 4), dy = y + image + 8, cy = dy + selector + 8;
            logo(u, x, std::max(2, y - 35), 1.28f, 2.4f, width);
            fit(u, x, y - 13, width, .8f, tr(u, "menu.darkwinter.story", L"История / DarkWinter", L"Story / DarkWinter"), rgba(0x93aebb));
            panel(u, x - 1, y - 2, width + 2, image + 4);
            const int iw = std::min(120, width / 3), tx = x + iw + 12, space = width - iw - 22;
            cover(u, 4, x + 2, y + 1, iw, image - 2, .48f, .58f, 1, rgba(0xffffff));
            fit(u, tx, y + 4, space, .96f, tr(u, "menu.darkwinter.region", L"Северная Канада", L"Northern Canada"), rgba(0xdce8eb));
            if (image >= 44) paragraph(u, tx, y + 21, space, .8f,
                tr(u, "menu.darkwinter.story.summary", L"Заснеженная тайга. Выживание.\nСюжетная кампания в разработке.", L"Snow-covered taiga. Survival.\nStory campaign in development."), rgba(0x93aebb), std::max(1, (image - 24) / 10));
            panel(u, x, dy, width, selector);
            fit(u, x + 10, dy + 7, width / 2 - 12, .8f, tr(u, "menu.darkwinter.difficulty.label", L"Сложность", L"Difficulty"), rgba(0x93aebb));
            u.add(230, x + width / 2 + 24, dy, width / 2 - 48, selector, difficulty(u, int(u.settings.option("create.difficulty", 2))));
            u.add(231, x + width / 2, dy, 22, selector, L"‹");
            u.add(232, x + width - 22, dy, 22, selector, L"›");
            u.registerButton(236, x, cy, width, configure, tr(u, "menu.darkwinter.configure", L"Настроить мир", L"Configure world"));
            u.surface(x, cy, width, configure, 0, light(u, 236), rgba(0xa9dae7));
            const auto hint = tr(u, "menu.darkwinter.configure.hint", L"Параметры мира, генерации и игрового процесса.", L"World, generation and gameplay options.");
            if (width >= 300 && configure >= 30) {
                fit(u, x + 10, cy + (configure - 10) / 2, width * 2 / 5 - 18, .96f,
                    tr(u, "menu.darkwinter.configure", L"Настроить мир", L"Configure world"), rgba(0xdce8eb));
                u.rect(x + width * 2 / 5, cy + 7, 1, configure - 14, rgba(0x537384, 80));
                paragraph(u, x + width * 2 / 5 + 12, cy + 7, width * 3 / 5 - 23, .70f, hint, rgba(0x93aebb), 2);
            } else fit(u, x + 10, cy + (configure - 10) / 2, width - 20, .92f,
                       tr(u, "menu.darkwinter.configure", L"Настроить мир", L"Configure world"), rgba(0xdce8eb));
            u.add(235, x + width / 5, cy + configure + 12, width * 3 / 5, continuation,
                  tr(u, "menu.darkwinter.continue", L"Продолжить", L"Continue"));
            u.addPage(703, u.w / 2 - 60, u.h - 27, 120, 20,
                      tr(u, "gui.back", L"Назад", L"Back"), Ui::WORLD_MODE);
            return true;
        }
        if (u.page == Ui::TEST_SETUP) {
            forest(u);
            const int width = std::min(340, u.w - 42), x = (u.w - width) / 2;
            const int y = std::max(74, std::min(u.h - 142, u.h / 2 - 52));
            logo(u, x, y - 62, 1.2f, 2.2f, width);
            paragraph(u, x, y - 36, width, .8f,
                      tr(u, "menu.darkwinter.test.note", L"Создание локаций и проверка игровых систем", L"Build locations and verify gameplay systems"), rgba(0x93aebb), 3);
            const int type = std::clamp(int(u.settings.option("test.template", 0)), 0, 3);
            const char* keys[] = {"menu.darkwinter.test.island", "menu.darkwinter.test.normal", "menu.darkwinter.test.flat", "menu.darkwinter.test.test_map"};
            const wchar_t* ru[] = {L"Заснеженный тестовый остров", L"Обычный мир", L"Плоский полигон", L"Подготовленная испытательная площадка"};
            const wchar_t* en[] = {L"Snow-covered testing island", L"Normal world", L"Flat testing ground", L"Prepared testing ground"};
            auto templ = tr(u, "menu.darkwinter.test.template", L"Шаблон мира: %s", L"World template: %s");
            auto pos = templ.find(L"%s");
            if (pos != std::wstring::npos) templ.replace(pos, 2, tr(u, keys[type], ru[type], en[type]));
            u.add(237, x, y, width, 24, templ);
            const int radii[] = {64, 96, 128};
            auto radius = tr(u, "menu.darkwinter.test.radius", L"Радиус острова: %s м", L"Island radius: %s m");
            pos = radius.find(L"%s");
            if (pos != std::wstring::npos) radius.replace(pos, 2, std::to_wstring(radii[std::clamp(int(u.settings.option("test.radiusIndex", 1)), 0, 2)]));
            u.add(238, x, y + 30, (width - 8) / 2, 24, radius, type == 0);
            const int shape = std::clamp(int(u.settings.option("test.shape", 0)), 0, 2);
            const char* sk[] = {"menu.darkwinter.test.shape.round", "menu.darkwinter.test.shape.oval", "menu.darkwinter.test.shape.crescent"};
            const wchar_t* sr[] = {L"Круглая", L"Овальная", L"Полумесяц"};
            const wchar_t* se[] = {L"Round", L"Oval", L"Crescent"};
            auto shapeText = tr(u, "menu.darkwinter.test.shape", L"Форма: %s", L"Shape: %s");
            pos = shapeText.find(L"%s");
            if (pos != std::wstring::npos) shapeText.replace(pos, 2, tr(u, sk[shape], sr[shape], se[shape]));
            u.add(239, x + (width + 8) / 2, y + 30, (width - 8) / 2, 24, shapeText, type == 0);
            u.add(240, x, y + 70, width, 24, tr(u, "menu.darkwinter.test.create", L"Настроить тестовый мир", L"Configure test world"));
            u.addPage(704, u.w / 2 - 60, u.h - 27, 120, 20,
                      tr(u, "gui.back", L"Назад", L"Back"), Ui::WORLD_MODE);
            return true;
        }
        return false;
    }
};
}
