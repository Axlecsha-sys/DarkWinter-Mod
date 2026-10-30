// Starts the native launcher and handles its command-line diagnostic and preview modes.
#![cfg_attr(windows, windows_subsystem = "windows")]

use anyhow::{Context, Result, bail, ensure};
use launcher_core::{AppPaths, ConfigStore};
use launcher_ui::{DiagnosticOptions, Page, PreviewOptions};
use std::path::PathBuf;

#[derive(Default)]
struct Arguments {
    diagnostics: DiagnosticOptions,
    data_dir: Option<PathBuf>,
    diagnose: bool,
    report: Option<PathBuf>,
    screenshot: Option<PathBuf>,
    smoke_page: Option<Page>,
    smoke_size: Option<[f32; 2]>,
    smoke_zoom: Option<f32>,
    help: bool,
}

fn arguments() -> Result<Arguments> {
    let mut result = Arguments::default();
    let mut args = std::env::args_os().skip(1);
    while let Some(arg) = args.next() {
        match arg.to_str() {
            Some("--help" | "-h") => result.help = true,
            Some("--diagnose") => result.diagnose = true,
            Some("--data-dir") => {
                result.data_dir = Some(PathBuf::from(
                    args.next().context("Для --data-dir нужен путь")?,
                ))
            }
            Some("--game-dir") => {
                result.diagnostics.game_dir = Some(PathBuf::from(
                    args.next().context("Для --game-dir нужен путь")?,
                ))
            }
            Some("--java") => {
                result.diagnostics.java_path =
                    Some(PathBuf::from(args.next().context("Для --java нужен путь")?))
            }
            Some("--report") => {
                result.report = Some(PathBuf::from(
                    args.next().context("Для --report нужен путь")?,
                ))
            }
            Some("--smoke-test") => {
                result.screenshot = Some(PathBuf::from(
                    args.next().context("Для --smoke-test нужен путь PNG")?,
                ))
            }
            Some("--smoke-page") => {
                let value = args.next().context("Для --smoke-page нужна страница")?;
                result.smoke_page = Some(match value.to_str() {
                    Some("home") => Page::Home,
                    Some("builds") => Page::Builds,
                    Some("settings") => Page::Settings,
                    Some("diagnostics") => Page::Diagnostics,
                    _ => bail!("Страница проверки: home, builds, settings или diagnostics"),
                });
            }
            Some("--smoke-size") => {
                let value = args
                    .next()
                    .context("Для --smoke-size нужен размер WIDTHxHEIGHT")?;
                let (width, height) = value
                    .to_str()
                    .and_then(|s| s.split_once('x'))
                    .context("Размер проверки должен иметь вид WIDTHxHEIGHT")?;
                let size: [f32; 2] = [width.parse()?, height.parse()?];
                ensure!(
                    (860.0..=2560.0).contains(&size[0]) && (620.0..=1600.0).contains(&size[1]),
                    "Размер проверочного окна вне допустимых границ"
                );
                result.smoke_size = Some(size);
            }
            Some("--smoke-zoom") => {
                let value = args.next().context("Для --smoke-zoom нужен масштаб")?;
                let zoom: f32 = value.to_str().context("Некорректный масштаб")?.parse()?;
                ensure!(
                    zoom.is_finite() && (0.8..=1.5).contains(&zoom),
                    "Масштаб проверки должен быть от 0.8 до 1.5"
                );
                result.smoke_zoom = Some(zoom);
            }
            _ => bail!("Неизвестный аргумент. Используйте --help."),
        }
    }
    ensure!(
        !result.diagnose || result.screenshot.is_none(),
        "--diagnose и --smoke-test несовместимы"
    );
    ensure!(
        result.report.is_none() || result.diagnose,
        "--report используется вместе с --diagnose"
    );
    ensure!(
        result.screenshot.is_some()
            || (result.smoke_page.is_none()
                && result.smoke_size.is_none()
                && result.smoke_zoom.is_none()),
        "Параметры --smoke-* используются вместе с --smoke-test"
    );
    for path in [&result.report, &result.screenshot].into_iter().flatten() {
        ensure!(
            path.is_absolute() && path.parent().is_some_and(|p| p.is_dir()),
            "Каталог результата должен существовать, путь должен быть абсолютным"
        );
    }
    Ok(result)
}

fn main() {
    if let Err(error) = entry() {
        eprintln!("DarkWinter Launcher: {error:#}");
        let headless = std::env::args_os().any(|arg| {
            matches!(
                arg.to_str(),
                Some("--diagnose" | "--smoke-test" | "--help" | "-h")
            )
        });
        if !headless {
            launcher_ui::show_startup_error(format!("Не удалось открыть лаунчер.\n\n{error:#}"));
        }
        std::process::exit(1);
    }
}

fn entry() -> Result<()> {
    let args = arguments()?;
    if args.help {
        println!(
            "DarkWinterLauncher [--data-dir ABS_PATH] [--game-dir ABS_PATH] [--java ABS_PATH]\n  --diagnose [--report ABS_PATH]  Local read-only diagnostics, then exit\n  --smoke-test ABS_PATH.png       Capture the real native window, then exit\n  --smoke-page home|builds|settings|diagnostics\n  --smoke-size WIDTHxHEIGHT --smoke-zoom 0.8..1.5"
        );
        return Ok(());
    }
    launcher_ui::validate_options(&args.diagnostics)?;
    if args.diagnose {
        let diagnostics = launcher_ui::diagnose(&args.diagnostics);
        if let Some(path) = &args.report {
            launcher_ui::write_report(path, &diagnostics)?;
        }
        println!("{}", serde_json::to_string_pretty(&diagnostics)?);
        return Ok(());
    }
    let paths = match args.data_dir {
        Some(root) => AppPaths::under(root)?,
        None => AppPaths::for_current_user()?,
    };
    let store = ConfigStore::open(paths)?;
    if args.screenshot.is_some() {
        launcher_ui::run_preview(
            store,
            args.diagnostics,
            PreviewOptions {
                screenshot: args.screenshot,
                page: args.smoke_page.unwrap_or_default(),
                window_size: args.smoke_size.unwrap_or([1280.0, 800.0]),
                zoom: args.smoke_zoom,
            },
        )
    } else {
        launcher_ui::run(store, args.diagnostics, None)
    }
}
