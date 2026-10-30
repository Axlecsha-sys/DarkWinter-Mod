// Defines launcher UI state, navigation and diagnostic presentation.
mod art;
mod journal;
mod layout;

use anyhow::{Result, ensure};
use art::{DecodedArt, JournalArt};
use eframe::egui;
use launcher_common::LauncherSettings;
use launcher_core::{ConfigStore, atomic_write_new};
use launcher_hardware::HardwareSnapshot;
use launcher_java::JavaRuntime;
use launcher_profile::ProfileSnapshot;
use serde::Serialize;
use std::{
    path::PathBuf,
    sync::{Arc, Mutex, mpsc},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Debug, Default)]
pub struct DiagnosticOptions {
    pub game_dir: Option<PathBuf>,
    pub java_path: Option<PathBuf>,
}

#[derive(Clone, Debug, Serialize)]
pub struct JavaCheck {
    pub executable: PathBuf,
    pub runtime: Option<JavaRuntime>,
    pub problem: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct LocalDiagnostics {
    pub schema: u32,
    pub launcher_version: String,
    pub captured_at_unix_seconds: u64,
    pub hardware: HardwareSnapshot,
    pub java: Vec<JavaCheck>,
    pub profile: Option<ProfileSnapshot>,
    pub profile_status: String,
    pub game_directory_selected: bool,
}

/// No network calls, world access, profile writes, configuration edits or game launch.
pub fn diagnose(options: &DiagnosticOptions) -> LocalDiagnostics {
    let hardware = launcher_hardware::inspect();
    let mut java = Vec::new();
    let deadline = Instant::now() + Duration::from_secs(12);
    for executable in launcher_java::discover(options.java_path.as_deref()) {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match launcher_java::inspect(&executable, remaining.min(Duration::from_secs(4))) {
            Ok(runtime) => java.push(JavaCheck {
                executable,
                runtime: Some(runtime),
                problem: None,
            }),
            Err(error) => java.push(JavaCheck {
                executable,
                runtime: None,
                problem: Some(error.to_string()),
            }),
        }
    }
    let (profile, profile_status) = match &options.game_dir {
        None => (
            None,
            "Укажите папку существующей игры при запуске лаунчера.".into(),
        ),
        Some(dir) => match launcher_profile::read_snapshot(dir) {
            Ok(Some(profile)) => (
                Some(profile),
                "Связь DarkWinter ID с открытым ключом проверена.".into(),
            ),
            Ok(None) => (None, "Профиль в выбранной папке не найден.".into()),
            Err(error) => (None, error.to_string()),
        },
    };
    LocalDiagnostics {
        schema: 1,
        launcher_version: env!("CARGO_PKG_VERSION").into(),
        captured_at_unix_seconds: unix_seconds(),
        hardware,
        java,
        profile,
        profile_status,
        game_directory_selected: options.game_dir.is_some(),
    }
}

pub fn write_report(path: &std::path::Path, diagnostics: &LocalDiagnostics) -> Result<()> {
    atomic_write_new(path, &serde_json::to_vec_pretty(diagnostics)?)
}

fn unix_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Page {
    #[default]
    Home,
    Builds,
    Diagnostics,
    Settings,
}

/// Developer QA options; none are persisted to user settings.
pub struct PreviewOptions {
    pub screenshot: Option<PathBuf>,
    pub page: Page,
    pub window_size: [f32; 2],
    pub zoom: Option<f32>,
}

impl Default for PreviewOptions {
    fn default() -> Self {
        Self {
            screenshot: None,
            page: Page::Home,
            window_size: [1280.0, 800.0],
            zoom: None,
        }
    }
}

enum IoCompletion {
    Settings(LauncherSettings),
    Report(PathBuf),
}
type IoResult = std::result::Result<IoCompletion, String>;
type CaptureResult = Arc<Mutex<Option<std::result::Result<(), String>>>>;

struct LauncherUi {
    store: Arc<ConfigStore>,
    options: DiagnosticOptions,
    settings: LauncherSettings,
    draft: LauncherSettings,
    settings_error: Option<String>,
    page: Page,
    art: JournalArt,
    diagnostics: Option<LocalDiagnostics>,
    diagnostic_rx: Option<mpsc::Receiver<LocalDiagnostics>>,
    io_rx: Option<mpsc::Receiver<IoResult>>,
    notice: Option<String>,
    profile_open: bool,
    start: Instant,
    animation_time: f32,
    last_frame: Instant,
    frames: u32,
    screenshot: Option<PathBuf>,
    capture_result: CaptureResult,
    #[cfg(test)]
    save_button: Option<egui::Rect>,
    #[cfg(test)]
    animation_checkbox: Option<egui::Rect>,
}

pub fn run(
    store: ConfigStore,
    options: DiagnosticOptions,
    screenshot: Option<PathBuf>,
) -> Result<()> {
    run_preview(
        store,
        options,
        PreviewOptions {
            screenshot,
            ..Default::default()
        },
    )
}

pub fn run_preview(
    store: ConfigStore,
    options: DiagnosticOptions,
    preview: PreviewOptions,
) -> Result<()> {
    let decoded = DecodedArt::load()?;
    let capture_result: CaptureResult = Arc::new(Mutex::new(None));
    let outcome = capture_result.clone();
    let smoke = preview.screenshot.is_some();
    let native = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("DarkWinter Launcher")
            .with_inner_size(preview.window_size)
            .with_min_inner_size([860.0, 620.0]),
        renderer: eframe::Renderer::Glow,
        centered: true,
        ..Default::default()
    };
    eframe::run_native(
        "DarkWinter Launcher",
        native,
        Box::new(move |cc| {
            art::configure(&cc.egui_ctx);
            let mut app = LauncherUi::new(
                store,
                options,
                decoded.upload(&cc.egui_ctx),
                preview,
                capture_result,
                &cc.egui_ctx,
            );
            app.refresh(&cc.egui_ctx);
            Ok(Box::new(app))
        }),
    )
    .map_err(|error| anyhow::anyhow!("Не удалось открыть нативное окно: {error}"))?;
    if smoke {
        match outcome
            .lock()
            .map_err(|_| anyhow::anyhow!("Ошибка проверки кадра"))?
            .take()
        {
            Some(Ok(())) => {}
            Some(Err(error)) => anyhow::bail!(error),
            None => anyhow::bail!("Проверка окна завершилась без снимка"),
        }
    }
    Ok(())
}

impl LauncherUi {
    fn new(
        store: ConfigStore,
        options: DiagnosticOptions,
        art: JournalArt,
        preview: PreviewOptions,
        capture_result: CaptureResult,
        ctx: &egui::Context,
    ) -> Self {
        let (settings, settings_error) = match store.load_launcher() {
            Ok(settings) => (settings, None),
            Err(error) => (LauncherSettings::default(), Some(error.to_string())),
        };
        ctx.set_zoom_factor(preview.zoom.unwrap_or(settings.ui_scale));
        Self {
            store: Arc::new(store),
            options,
            draft: settings.clone(),
            settings,
            settings_error,
            page: preview.page,
            art,
            diagnostics: None,
            diagnostic_rx: None,
            io_rx: None,
            notice: None,
            profile_open: false,
            start: Instant::now(),
            animation_time: 0.0,
            last_frame: Instant::now(),
            frames: 0,
            screenshot: preview.screenshot,
            capture_result,
            #[cfg(test)]
            save_button: None,
            #[cfg(test)]
            animation_checkbox: None,
        }
    }

    fn busy(&self) -> bool {
        self.diagnostic_rx.is_some() || self.io_rx.is_some()
    }

    fn refresh(&mut self, ctx: &egui::Context) {
        if self.diagnostic_rx.is_some() {
            return;
        }
        let (tx, rx) = mpsc::channel();
        self.diagnostic_rx = Some(rx);
        let options = self.options.clone();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let _ = tx.send(diagnose(&options));
            ctx.request_repaint();
        });
    }

    fn poll(&mut self, ctx: &egui::Context) {
        if let Some(rx) = &self.diagnostic_rx {
            match rx.try_recv() {
                Ok(snapshot) => {
                    self.diagnostics = Some(snapshot);
                    self.diagnostic_rx = None;
                }
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.notice = Some("Не удалось завершить диагностику.".into());
                    self.diagnostic_rx = None;
                }
                Err(mpsc::TryRecvError::Empty) => {}
            }
        }
        if let Some(rx) = &self.io_rx {
            match rx.try_recv() {
                Ok(result) => {
                    match result {
                        Ok(IoCompletion::Settings(settings)) => {
                            ctx.set_zoom_factor(settings.ui_scale);
                            self.settings = settings;
                            self.notice = Some("Настройки лаунчера сохранены.".into());
                        }
                        Ok(IoCompletion::Report(path)) => {
                            self.notice =
                                Some(format!("Отчёт сохранён на компьютере:\n{}", path.display()))
                        }
                        Err(error) => self.notice = Some(error),
                    }
                    self.io_rx = None;
                }
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.notice = Some("Операция сохранения прервана.".into());
                    self.io_rx = None;
                }
                Err(mpsc::TryRecvError::Empty) => {}
            }
        }
    }

    fn save_settings(&mut self, ctx: &egui::Context) {
        if self.io_rx.is_some() || self.settings_error.is_some() {
            return;
        }
        let (tx, rx) = mpsc::channel();
        self.io_rx = Some(rx);
        let store = self.store.clone();
        let settings = self.draft.clone();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let result = store
                .save_launcher(&settings)
                .map(|_| IoCompletion::Settings(settings))
                .map_err(|e| e.to_string());
            let _ = tx.send(result);
            ctx.request_repaint();
        });
    }

    fn export_report(&mut self, ctx: &egui::Context) {
        if self.io_rx.is_some() {
            return;
        }
        let Some(snapshot) = self.diagnostics.clone() else {
            return;
        };
        let (tx, rx) = mpsc::channel();
        self.io_rx = Some(rx);
        let path = self.store.paths().reports.join(format!(
            "local-{}.json",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let result = write_report(&path, &snapshot)
                .map(|_| IoCompletion::Report(path))
                .map_err(|e| e.to_string());
            let _ = tx.send(result);
            ctx.request_repaint();
        });
    }

    fn render(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        self.poll(&ctx);
        self.frames += 1;
        let active = ctx.input(|i| !i.viewport().minimized.unwrap_or(false) && i.focused);
        let now = Instant::now();
        if active && self.settings.animations {
            self.animation_time += (now - self.last_frame).as_secs_f32().min(0.1);
        }
        self.last_frame = now;
        self.journal(ui);
        if active && (self.settings.animations || self.busy()) {
            ctx.request_repaint_after(Duration::from_millis(if self.settings.animations {
                50
            } else {
                150
            }));
        }
        if self.screenshot.is_some() && self.diagnostic_rx.is_none() && self.frames >= 4 {
            let path = self.screenshot.take().expect("checked screenshot path");
            let ctx = ctx.clone();
            let outcome = self.capture_result.clone();
            ctx.clone().request_screenshot(move |image| {
                std::thread::spawn(move || {
                    let pixels: Vec<u8> = image
                        .pixels
                        .iter()
                        .flat_map(|pixel| pixel.to_array())
                        .collect();
                    let result = image::save_buffer(
                        &path,
                        &pixels,
                        image.size[0] as u32,
                        image.size[1] as u32,
                        image::ColorType::Rgba8,
                    )
                    .map_err(|error| error.to_string());
                    if let Ok(mut slot) = outcome.lock() {
                        *slot = Some(result);
                    }
                    ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                });
            });
        } else if self.screenshot.is_some() {
            ctx.request_repaint_after(Duration::from_millis(100));
            if self.start.elapsed() > Duration::from_secs(45) {
                if let Ok(mut slot) = self.capture_result.lock() {
                    *slot = Some(Err("Истекло время проверки окна".into()));
                }
                ctx.send_viewport_cmd(egui::ViewportCommand::Close);
            }
        }
    }
}

impl eframe::App for LauncherUi {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.render(ui);
    }
}

pub fn validate_options(options: &DiagnosticOptions) -> Result<()> {
    if let Some(path) = &options.game_dir {
        ensure!(
            path.is_absolute() && path.is_dir(),
            "Укажите существующую абсолютную папку игры"
        );
    }
    if let Some(path) = &options.java_path {
        ensure!(
            path.is_absolute() && path.is_file(),
            "Укажите существующий абсолютный путь Java"
        );
    }
    Ok(())
}

pub fn show_startup_error(message: String) {
    struct ErrorWindow(String);
    impl eframe::App for ErrorWindow {
        fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
            egui::Frame::NONE.inner_margin(24).show(ui, |ui| {
                ui.heading("DarkWinter Launcher");
                ui.add_space(16.0);
                ui.label(&self.0);
                ui.add_space(16.0);
                if ui.button("Закрыть").clicked() {
                    ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
                }
            });
        }
    }
    let _ = eframe::run_native(
        "DarkWinter Launcher — ошибка",
        eframe::NativeOptions {
            viewport: egui::ViewportBuilder::default().with_inner_size([580.0, 240.0]),
            renderer: eframe::Renderer::Glow,
            ..Default::default()
        },
        Box::new(move |cc| {
            art::configure(&cc.egui_ctx);
            Ok(Box::new(ErrorWindow(message)))
        }),
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use launcher_core::AppPaths;

    fn frame(ctx: &egui::Context, app: &mut LauncherUi, events: Vec<egui::Event>) {
        let input = egui::RawInput {
            screen_rect: Some(egui::Rect::from_min_size(
                egui::Pos2::ZERO,
                egui::vec2(1280.0, 800.0),
            )),
            events,
            ..Default::default()
        };
        let mut output = ctx.run_ui(input, |ui| app.render(ui));
        // No GPU in this input test. Explicitly acknowledge texture uploads.
        output.textures_delta.clear();
    }

    fn click(ctx: &egui::Context, app: &mut LauncherUi, position: egui::Pos2) {
        frame(
            ctx,
            app,
            vec![
                egui::Event::PointerMoved(position),
                egui::Event::PointerButton {
                    pos: position,
                    button: egui::PointerButton::Primary,
                    pressed: true,
                    modifiers: egui::Modifiers::NONE,
                },
            ],
        );
        frame(
            ctx,
            app,
            vec![egui::Event::PointerButton {
                pos: position,
                button: egui::PointerButton::Primary,
                pressed: false,
                modifiers: egui::Modifiers::NONE,
            }],
        );
    }

    #[test]
    fn real_widgets_navigate_and_persist_settings_after_restyling() {
        let temp = tempfile::tempdir().unwrap();
        let store = ConfigStore::open(AppPaths::under(temp.path().to_path_buf()).unwrap()).unwrap();
        let ctx = egui::Context::default();
        art::configure(&ctx);
        let art = DecodedArt::load().unwrap().upload(&ctx);
        let mut app = LauncherUi::new(
            store,
            DiagnosticOptions::default(),
            art,
            PreviewOptions::default(),
            Arc::new(Mutex::new(None)),
            &ctx,
        );
        frame(&ctx, &mut app, vec![]);
        let layout = layout::JournalLayout::new(egui::Rect::from_min_size(
            egui::Pos2::ZERO,
            egui::vec2(1280.0, 800.0),
        ));
        click(&ctx, &mut app, layout.nav_item(2).center());
        assert_eq!(app.page, Page::Settings);
        frame(&ctx, &mut app, vec![]);
        let checkbox = app.animation_checkbox.expect("settings checkbox rendered");
        click(&ctx, &mut app, checkbox.center());
        assert!(!app.draft.animations);
        let save = app.save_button.expect("save button rendered");
        click(&ctx, &mut app, save.center());
        let deadline = Instant::now() + Duration::from_secs(3);
        while app.io_rx.is_some() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
            frame(&ctx, &mut app, vec![]);
        }
        assert!(app.io_rx.is_none(), "save worker finished");
        assert!(!app.store.load_launcher().unwrap().animations);
        assert!(
            app.notice
                .as_deref()
                .is_some_and(|message| message.contains("сохранены"))
        );
    }
}
