// Builds the field-journal launcher pages and their native controls.
use crate::{
    LauncherUi, Page,
    art::{self, BLUE, INK, LINE, MUTED, NAVY, WHITE, serif},
    layout::JournalLayout,
};
use eframe::egui::{self, Align2, Color32, FontId, Rect, RichText, Stroke, pos2, vec2};

fn region<R>(
    ui: &mut egui::Ui,
    id: &str,
    rect: Rect,
    contents: impl FnOnce(&mut egui::Ui) -> R,
) -> R {
    let mut child = ui.new_child(
        egui::UiBuilder::new()
            .id_salt(id)
            .max_rect(rect)
            .layout(egui::Layout::top_down(egui::Align::Min)),
    );
    child.set_clip_rect(rect.intersect(ui.clip_rect()));
    contents(&mut child)
}

impl LauncherUi {
    pub(super) fn journal(&mut self, ui: &mut egui::Ui) {
        let layout = JournalLayout::new(ui.max_rect());
        self.art.paper(ui.painter(), layout.bounds);
        self.art.footer(ui.painter(), layout.footer);
        if self.page == Page::Home {
            self.home_scene(ui, &layout);
        }
        self.header(ui, &layout);
        if let Some(sidebar) = layout.sidebar {
            self.sidebar(ui, sidebar, layout.header.height());
        }
        if self.page != Page::Home {
            region(ui, "journal-page", layout.page_content(), |ui| {
                egui::ScrollArea::vertical()
                    .auto_shrink([false, false])
                    .show(ui, |ui| match self.page {
                        Page::Settings => self.settings_page(ui),
                        Page::Diagnostics => self.diagnostics_page(ui),
                        Page::Builds => self.builds_page(ui),
                        Page::Home => {}
                    });
            });
        }
        self.launch_bar(ui, &layout);
        self.dialogs(ui.ctx());
    }

    fn header(&mut self, ui: &mut egui::Ui, layout: &JournalLayout) {
        self.art.wordmark(ui.painter(), layout.logo);
        if layout.header.height() >= 110.0 {
            ui.painter().text(
                layout.logo.left_bottom() + vec2(1.0, 10.0),
                Align2::LEFT_TOP,
                "С Е В Е Р   П О М Н И Т",
                FontId::proportional(10.5),
                INK,
            );
        }
        let pages = [
            (Page::Home, "Главная"),
            (Page::Builds, "Сборки"),
            (Page::Settings, "Настройки"),
        ];
        for (index, (page, label)) in pages.into_iter().enumerate() {
            let rect = layout.nav_item(index);
            let selected = self.page == page;
            let fill = if selected {
                Color32::from_rgba_unmultiplied(104, 162, 207, 40)
            } else {
                Color32::TRANSPARENT
            };
            if ui
                .put(
                    rect,
                    egui::Button::new(RichText::new(label).size(16.0).color(INK))
                        .fill(fill)
                        .stroke(Stroke::NONE)
                        .corner_radius(0),
                )
                .clicked()
            {
                self.page = page;
            }
            if selected {
                ui.painter().line_segment(
                    [rect.left_bottom(), rect.right_bottom()],
                    Stroke::new(2.0, BLUE),
                );
            }
        }
        let rect = layout.profile;
        if ui
            .put(
                rect,
                egui::Button::new("")
                    .fill(Color32::TRANSPARENT)
                    .stroke(Stroke::NONE),
            )
            .clicked()
        {
            self.profile_open = true;
        }
        let nickname = self
            .diagnostics
            .as_ref()
            .and_then(|d| d.profile.as_ref())
            .map(|p| p.nickname.as_str())
            .unwrap_or("Профиль");
        let avatar = rect.left_center() + vec2(23.0, 0.0);
        ui.painter().circle_filled(avatar, 22.0, NAVY);
        let initial = nickname
            .chars()
            .next()
            .unwrap_or('П')
            .to_uppercase()
            .to_string();
        ui.painter()
            .text(avatar, Align2::CENTER_CENTER, initial, serif(21.0), WHITE);
        ui.painter().circle_filled(
            avatar + vec2(16.0, 16.0),
            4.0,
            Color32::from_rgb(112, 144, 171),
        );
        let text = Rect::from_min_max(
            pos2(avatar.x + 34.0, rect.top() + 7.0),
            pos2(rect.right() - 13.0, rect.bottom()),
        );
        let painter = ui.painter().with_clip_rect(text);
        painter.text(
            text.min,
            Align2::LEFT_TOP,
            nickname,
            FontId::proportional(17.0),
            INK,
        );
        painter.text(
            text.min + vec2(0.0, 23.0),
            Align2::LEFT_TOP,
            "Локальный профиль",
            FontId::proportional(10.5),
            MUTED,
        );
        let arrow = rect.right_center() - vec2(4.0, 0.0);
        ui.painter().add(egui::Shape::line(
            vec![
                arrow - vec2(4.0, 2.0),
                arrow + vec2(0.0, 2.0),
                arrow + vec2(4.0, -2.0),
            ],
            Stroke::new(1.0, MUTED),
        ));
    }

    fn home_scene(&self, ui: &egui::Ui, layout: &JournalLayout) {
        let body = layout.body;
        let painter = ui.painter().with_clip_rect(body);
        let room = body.height() > 430.0;
        art::contours(
            &painter,
            Rect::from_min_size(body.min, vec2(body.width(), 150.0)),
        );
        let height = (body.width() / self.art.panorama_aspect).min(body.height() - 32.0);
        let bottom = body.bottom() - if room { 47.0 } else { 22.0 };
        let scene = Rect::from_min_size(
            pos2(body.left(), bottom - height),
            vec2(body.width(), height),
        );
        self.art.panorama(&painter, scene);
        if room {
            let notes = body.min + vec2(42.0, 26.0);
            art::cross(&painter, notes + vec2(-13.0, 12.0), BLUE);
            painter.text(
                notes,
                Align2::LEFT_TOP,
                "СЕВЕРНЫЙ РАЙОН\nПОЛЕВОЙ ДНЕВНИК",
                FontId::monospace(10.0),
                MUTED,
            );
            painter.text(
                pos2(body.right() - 32.0, body.top() + 27.0),
                Align2::RIGHT_TOP,
                "ЗИМА\nDARKWINTER",
                FontId::monospace(10.0),
                MUTED,
            );
            painter.text(
                pos2(body.left() + 42.0, body.bottom() - 32.0),
                Align2::LEFT_TOP,
                "СЕВЕРНЫЙ БЕРЕГ\nЗИМНИЙ ПОСЁЛОК",
                FontId::monospace(9.5),
                MUTED,
            );
            let rule_y = body.bottom() - 22.0;
            let start = body.right() - 205.0;
            painter.line_segment(
                [pos2(start, rule_y), pos2(body.right() - 46.0, rule_y)],
                Stroke::new(0.7, LINE),
            );
            for step in 0..5 {
                let x = start + step as f32 * 39.75;
                painter.line_segment(
                    [pos2(x, rule_y - 4.0), pos2(x, rule_y + 4.0)],
                    Stroke::new(0.7, LINE),
                );
            }
            art::cross(&painter, pos2(body.right() - 23.0, rule_y), BLUE);
        }
        if self.settings.animations {
            let snow = painter.with_clip_rect(scene);
            for index in 0..52 {
                let x = (((index * 167 + 17) % 997) as f32 / 997.0 * scene.width()
                    + self.animation_time * (3.0 + (index % 5) as f32))
                    .rem_euclid(scene.width());
                let y = (((index * 239 + 31) % 991) as f32 / 991.0 * scene.height()
                    + self.animation_time * (8.0 + (index % 7) as f32))
                    .rem_euclid(scene.height());
                snow.circle_filled(
                    scene.min + vec2(x, y),
                    if index % 5 == 0 { 1.2 } else { 0.6 },
                    Color32::from_white_alpha(60),
                );
            }
        }
        if let Some(error) = &self.settings_error {
            let rect = Rect::from_min_max(
                body.min + vec2(28.0, 5.0),
                pos2(body.right() - 28.0, body.top() + 61.0),
            );
            region_readonly(ui, rect, error);
        }
    }

    fn sidebar(&mut self, ui: &mut egui::Ui, rect: Rect, header_height: f32) {
        let painter = ui.painter();
        painter.rect_filled(
            rect,
            0.0,
            Color32::from_rgba_unmultiplied(214, 234, 250, 52),
        );
        painter.line_segment(
            [rect.left_top(), rect.left_bottom()],
            Stroke::new(0.8, LINE),
        );
        let separator = |ui: &egui::Ui, y: f32| {
            ui.painter().line_segment(
                [pos2(rect.left() + 24.0, y), pos2(rect.right() - 24.0, y)],
                Stroke::new(0.8, LINE),
            );
            art::cross(ui.painter(), pos2(rect.left(), y), LINE);
        };
        separator(ui, rect.top() + header_height);
        let friends_height = ((rect.height() - header_height) * 0.38).clamp(115.0, 193.0);
        let friends = Rect::from_min_max(
            pos2(rect.left() + 26.0, rect.top() + header_height + 23.0),
            pos2(
                rect.right() - 26.0,
                rect.top() + header_height + 23.0 + friends_height,
            ),
        );
        region(ui, "friends-panel", friends, |ui| {
            section_title(ui, "ДРУЗЬЯ");
            ui.add_space(if friends.height() >= 170.0 {
                26.0
            } else {
                10.0
            });
            if friends.height() >= 170.0 {
                let (space, _) =
                    ui.allocate_exact_size(vec2(ui.available_width(), 34.0), egui::Sense::hover());
                let center = space.center();
                for offset in [-12.0, 12.0] {
                    ui.painter().circle_stroke(
                        center + vec2(offset, -8.0),
                        6.0,
                        Stroke::new(1.0, LINE),
                    );
                    ui.painter().line_segment(
                        [
                            center + vec2(offset - 8.0, 8.0),
                            center + vec2(offset + 8.0, 8.0),
                        ],
                        Stroke::new(1.0, LINE),
                    );
                }
            }
            ui.label(RichText::new("Друзья появятся здесь").color(INK).size(14.0));
            ui.label(
                RichText::new("Список друзей пока недоступен.")
                    .color(MUTED)
                    .size(12.0),
            );
        });
        let updates_y = friends.bottom() + 16.0;
        separator(ui, updates_y);
        let updates = Rect::from_min_max(
            pos2(rect.left() + 26.0, updates_y + 22.0),
            pos2(rect.right() - 26.0, rect.bottom() - 74.0),
        );
        region(ui, "updates-panel", updates, |ui| {
            section_title(ui, "ОБНОВЛЕНИЯ");
            ui.add_space(14.0);
            ui.label(
                RichText::new(format!("Лаунчер {}", env!("CARGO_PKG_VERSION")))
                    .strong()
                    .color(INK)
                    .size(15.0),
            );
            ui.label(
                RichText::new("Сине-голубой полевой дневник")
                    .color(MUTED)
                    .size(12.0),
            );
            if updates.height() >= 150.0 {
                ui.add_space(16.0);
                ui.separator();
                ui.add_space(6.0);
                ui.label(
                    RichText::new("Источник выпусков пока не настроен.")
                        .color(MUTED)
                        .size(12.0),
                );
            }
        });
        let button = Rect::from_min_max(
            pos2(rect.left() + 24.0, rect.bottom() - 54.0),
            pos2(rect.right() - 24.0, rect.bottom() - 18.0),
        );
        if ui
            .put(
                button,
                egui::Button::new(RichText::new("Проверка системы").color(INK).size(13.0))
                    .fill(Color32::TRANSPARENT)
                    .stroke(Stroke::new(0.7, LINE)),
            )
            .clicked()
        {
            self.page = Page::Diagnostics;
        }
    }

    fn launch_bar(&mut self, ui: &mut egui::Ui, layout: &JournalLayout) {
        let footer = layout.footer;
        let left = footer.left() + 28.0;
        let compact = footer.width() < 800.0;
        let medium = footer.width() < 1040.0;
        let available = layout.play.left() - left - 24.0;
        let selector_width = if compact {
            available
        } else if medium {
            available * 0.49
        } else {
            available * 0.34
        };
        let selector_left = if medium {
            left
        } else {
            left + available * 0.31
        };
        let selector_y = footer.top() + if compact { 42.0 } else { 41.0 };
        let selector =
            Rect::from_min_size(pos2(selector_left, selector_y), vec2(selector_width, 33.0));
        if !medium {
            let version = Rect::from_min_size(pos2(left, footer.top() + 41.0), vec2(76.0, 30.0));
            ui.painter().rect_stroke(
                version,
                0.0,
                Stroke::new(0.7, Color32::from_rgb(129, 161, 192)),
                egui::StrokeKind::Inside,
            );
            ui.painter().text(
                version.center(),
                Align2::CENTER_CENTER,
                format!("v{}", env!("CARGO_PKG_VERSION")),
                FontId::monospace(11.0),
                WHITE,
            );
            ui.painter().text(
                pos2(version.right() + 15.0, version.top() + 2.0),
                Align2::LEFT_TOP,
                "ЛАУНЧЕР\nРАЗРАБОТКА",
                FontId::monospace(9.0),
                Color32::from_rgb(152, 182, 209),
            );
        }
        if !compact {
            ui.painter().text(
                pos2(selector.left(), footer.top() + 19.0),
                Align2::LEFT_TOP,
                "Выбранная сборка",
                FontId::proportional(10.5),
                Color32::from_rgb(159, 187, 213),
            );
        }
        let mut selector_ui = ui.new_child(
            egui::UiBuilder::new()
                .id_salt("build-selector")
                .max_rect(selector),
        );
        selector_ui.set_clip_rect(selector);
        selector_ui.add_enabled_ui(false, |ui| {
            ui.put(
                selector,
                egui::Button::new(RichText::new("Сборка не выбрана").size(13.0).color(WHITE))
                    .fill(Color32::TRANSPARENT)
                    .stroke(Stroke::new(0.8, Color32::from_rgb(160, 190, 217)))
                    .corner_radius(0),
            );
        });
        let status = if compact {
            pos2(left, footer.top() + 16.0)
        } else {
            pos2(selector.right() + 24.0, footer.top() + 27.0)
        };
        let status_clip = Rect::from_min_max(
            pos2(status.x, footer.top()),
            pos2(layout.play.left() - 16.0, footer.bottom()),
        );
        let painter = ui.painter().with_clip_rect(status_clip);
        painter.text(
            status,
            Align2::LEFT_TOP,
            "Сборка не подключена",
            FontId::proportional(12.0),
            WHITE,
        );
        if !compact {
            painter.text(
                status + vec2(0.0, 21.0),
                Align2::LEFT_TOP,
                "Запуск пока недоступен",
                FontId::proportional(10.5),
                Color32::from_rgb(156, 186, 212),
            );
        }
        let diag = if compact {
            Rect::from_min_size(pos2(left - 4.0, footer.bottom() - 31.0), vec2(140.0, 25.0))
        } else {
            Rect::from_min_size(
                pos2(status.x - 6.0, footer.bottom() - 32.0),
                vec2(145.0, 25.0),
            )
        };
        if ui
            .put(
                diag,
                egui::Button::new(
                    RichText::new(if self.busy() {
                        "Проверка…"
                    } else {
                        "Диагностика"
                    })
                    .size(11.0)
                    .color(Color32::from_rgb(171, 210, 242)),
                )
                .fill(Color32::TRANSPARENT)
                .stroke(Stroke::NONE),
            )
            .clicked()
        {
            self.page = Page::Diagnostics;
        }
        ui.painter().rect_filled(layout.play, 2.0, BLUE);
        let mut play_ui = ui.new_child(
            egui::UiBuilder::new()
                .id_salt("play-disabled")
                .max_rect(layout.play),
        );
        play_ui.set_clip_rect(layout.play);
        play_ui.add_enabled_ui(false, |ui| {
            ui.put(
                layout.play,
                egui::Button::new(
                    RichText::new("ИГРАТЬ")
                        .font(serif(if compact { 24.0 } else { 30.0 }))
                        .color(WHITE),
                )
                .fill(Color32::TRANSPARENT)
                .stroke(Stroke::NONE),
            );
        });
    }

    fn builds_page(&self, ui: &mut egui::Ui) {
        page_title(ui, "СБОРКИ DARKWINTER", "Установка и выбор игровой версии");
        paper_section(
            ui,
            "Игровая сборка ещё не подключена",
            |ui| {
                ui.label("Для запуска нужен полный пакет Minecraft/Fabric и DarkWinter с проверенным манифестом.");
                ui.add_space(8.0);
                ui.label(
                    RichText::new("Менеджер установки и обновлений пока находится в разработке.")
                        .color(MUTED),
                );
            },
        );
        ui.add_space(20.0);
        ui.label(
            RichText::new("КАТАЛОГ СБОРОК")
                .monospace()
                .size(11.0)
                .color(MUTED),
        );
        ui.label(self.store.paths().builds.to_string_lossy());
    }

    fn settings_page(&mut self, ui: &mut egui::Ui) {
        page_title(ui, "НАСТРОЙКИ", "Оформление и локальные данные лаунчера");
        if let Some(error) = &self.settings_error {
            ui.colored_label(
                Color32::from_rgb(151, 62, 54),
                format!("Настройки не загружены: {error}. Сохранение отключено."),
            );
        }
        paper_section(ui, "Оформление", |ui| {
            let checkbox = ui.checkbox(&mut self.draft.animations, "Анимация снега");
            #[cfg(test)]
            {
                self.animation_checkbox = Some(checkbox.rect);
            }
            #[cfg(not(test))]
            let _ = checkbox;
            ui.add(
                egui::Slider::new(&mut self.draft.ui_scale, 0.8..=1.5)
                    .text("Масштаб интерфейса")
                    .step_by(0.05)
                    .custom_formatter(|value, _| format!("{:.0} %", value * 100.0))
                    .custom_parser(|text| {
                        text.trim_end_matches('%')
                            .trim()
                            .parse::<f64>()
                            .ok()
                            .map(|value| value / 100.0)
                    }),
            );
            ui.label(
                RichText::new("Снег останавливается при сворачивании и потере фокуса.")
                    .color(MUTED)
                    .size(12.0),
            );
        });
        ui.add_space(15.0);
        let ctx = ui.ctx().clone();
        ui.horizontal_wrapped(|ui| {
            let save = ui.add_enabled(
                self.settings_error.is_none() && self.io_rx.is_none(),
                egui::Button::new(RichText::new("Сохранить и применить").color(WHITE)).fill(BLUE),
            );
            #[cfg(test)]
            {
                self.save_button = Some(save.rect);
            }
            if save.clicked() {
                self.save_settings(&ctx);
            }
            if ui.button("Отменить изменения").clicked() {
                self.draft = self.settings.clone();
            }
        });
        ui.add_space(23.0);
        paper_section(ui, "Каталоги данных", |ui| {
            for (name, path) in [
                ("Настройки", &self.store.paths().config),
                ("Сборки", &self.store.paths().builds),
                ("Пользовательские данные", &self.store.paths().user_data),
                ("Кэш", &self.store.paths().cache),
                ("Отчёты", &self.store.paths().reports),
            ] {
                ui.label(RichText::new(name).strong().size(12.0));
                ui.label(
                    RichText::new(path.to_string_lossy())
                        .color(MUTED)
                        .size(11.0),
                );
                ui.add_space(4.0);
            }
        });
    }

    fn diagnostics_page(&mut self, ui: &mut egui::Ui) {
        page_title(ui, "ДИАГНОСТИКА", "Локальная проверка компьютера и Java");
        if let Some(snapshot) = &self.diagnostics {
            paper_section(ui, "Компьютер", |ui| {
                ui.label(RichText::new(&snapshot.hardware.cpu).strong());
                ui.label(format!(
                    "{} потоков · {}",
                    snapshot.hardware.logical_threads, snapshot.hardware.architecture
                ));
                ui.label(format!(
                    "ОЗУ: {:.1} ГиБ · доступно {:.1} ГиБ",
                    snapshot.hardware.total_memory_mib as f64 / 1024.0,
                    snapshot.hardware.available_memory_mib as f64 / 1024.0
                ));
                ui.label(RichText::new(&snapshot.hardware.os).color(MUTED).size(12.0));
            });
            ui.add_space(14.0);
            paper_section(ui, "Java Runtime", |ui| {
                if snapshot.java.is_empty() {
                    ui.label("Локальная Java не обнаружена.");
                }
                for check in &snapshot.java {
                    if let Some(runtime) = &check.runtime {
                        ui.label(
                            RichText::new(format!(
                                "Java {} · {} · {}",
                                runtime.version, runtime.architecture, runtime.vendor
                            ))
                            .strong(),
                        );
                        ui.label(
                            RichText::new(if runtime.compatible_with(25) {
                                "Java 25 x64 — подходит для текущего проекта."
                            } else {
                                "Текущему проекту требуется Java 25 x64."
                            })
                            .color(MUTED)
                            .size(12.0),
                        );
                    } else {
                        ui.label(check.problem.as_deref().unwrap_or("Ошибка проверки Java"));
                    }
                    ui.label(
                        RichText::new(check.executable.to_string_lossy())
                            .color(MUTED)
                            .size(11.0),
                    );
                    ui.add_space(8.0);
                }
            });
        } else {
            ui.label("Определяем оборудование и проверяем Java…");
        }
        ui.add_space(14.0);
        let ctx = ui.ctx().clone();
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    self.diagnostic_rx.is_none(),
                    egui::Button::new("Повторить проверку"),
                )
                .clicked()
            {
                self.refresh(&ctx);
            }
            if ui
                .add_enabled(
                    self.diagnostics.is_some() && self.io_rx.is_none(),
                    egui::Button::new("Сохранить отчёт"),
                )
                .clicked()
            {
                self.export_report(&ctx);
            }
        });
    }

    fn dialogs(&mut self, ctx: &egui::Context) {
        if self.profile_open {
            let mut open = true;
            egui::Window::new("Профиль DarkWinter")
                .open(&mut open)
                .collapsible(false)
                .resizable(true)
                .default_width(430.0)
                .show(ctx, |ui| {
                    if let Some(snapshot) = &self.diagnostics {
                        if let Some(profile) = &snapshot.profile {
                            ui.label(RichText::new(&profile.nickname).font(serif(24.0)));
                            ui.label(match profile.model {
                                launcher_profile::Model::Male => "Мужская модель",
                                launcher_profile::Model::Female => "Женская модель",
                            });
                            ui.add_space(10.0);
                            ui.label(
                                RichText::new("DARKWINTER ID")
                                    .monospace()
                                    .size(11.0)
                                    .color(MUTED),
                            );
                            ui.monospace(&profile.id);
                            ui.add_space(10.0);
                        }
                        ui.label(&snapshot.profile_status);
                    } else {
                        ui.label("Читаем публичные данные профиля…");
                    }
                    ui.add_space(10.0);
                    ui.label(
                        RichText::new(
                            "Редактирование профиля и список друзей будут подключены отдельно.",
                        )
                        .color(MUTED)
                        .size(12.0),
                    );
                });
            self.profile_open = open;
        }
        if let Some(message) = self.notice.clone() {
            let mut close = false;
            egui::Window::new("DarkWinter Launcher")
                .collapsible(false)
                .resizable(true)
                .default_width(440.0)
                .show(ctx, |ui| {
                    ui.label(message);
                    ui.add_space(14.0);
                    close = ui.button("Понятно").clicked();
                });
            if close {
                self.notice = None;
            }
        }
    }
}

fn section_title(ui: &mut egui::Ui, title: &str) {
    ui.label(RichText::new(title).font(serif(18.0)).color(INK));
}

fn page_title(ui: &mut egui::Ui, title: &str, subtitle: &str) {
    let compact = ui.clip_rect().height() < 260.0;
    ui.add_space(3.0);
    ui.label(
        RichText::new(title)
            .font(serif(if compact { 22.0 } else { 26.0 }))
            .color(INK),
    );
    if ui.clip_rect().height() >= 180.0 {
        ui.label(RichText::new(subtitle).color(MUTED).size(13.0));
    }
    ui.add_space(if compact { 8.0 } else { 18.0 });
}

fn paper_section(ui: &mut egui::Ui, title: &str, contents: impl FnOnce(&mut egui::Ui)) {
    let compact = ui.clip_rect().height() < 260.0;
    egui::Frame::new()
        .fill(Color32::from_rgba_unmultiplied(222, 240, 253, 130))
        .stroke(Stroke::new(0.7, LINE))
        .inner_margin(if compact { 12 } else { 17 })
        .corner_radius(0)
        .show(ui, |ui| {
            ui.set_min_width((ui.available_width() - 3.0).max(120.0));
            ui.label(
                RichText::new(title)
                    .font(serif(if compact { 16.0 } else { 18.0 }))
                    .color(INK),
            );
            ui.add_space(if compact { 6.0 } else { 10.0 });
            contents(ui);
        });
}

fn region_readonly(ui: &egui::Ui, rect: Rect, message: &str) {
    let painter = ui.painter().with_clip_rect(rect);
    painter.rect_filled(
        rect,
        1.0,
        Color32::from_rgba_unmultiplied(221, 239, 252, 240),
    );
    let text = painter.layout(
        message.into(),
        FontId::proportional(12.0),
        INK,
        rect.width() - 16.0,
    );
    painter.galley(rect.min + vec2(8.0, 5.0), text, INK);
}
