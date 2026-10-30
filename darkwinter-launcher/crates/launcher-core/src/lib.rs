// Manages launcher configuration, application paths and atomic persistence.
use anyhow::{Context, Result, ensure};
use atomicwrites::{AllowOverwrite, AtomicFile, DisallowOverwrite};
use fs2::FileExt;
use launcher_common::{
    GameSettings, LauncherSettings, SETTINGS_LIMIT, read_json, require_plain_path,
};
use serde::Serialize;
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

#[derive(Clone, Debug)]
pub struct AppPaths {
    pub root: PathBuf,
    pub config: PathBuf,
    pub builds: PathBuf,
    pub user_data: PathBuf,
    pub cache: PathBuf,
    pub reports: PathBuf,
}

impl AppPaths {
    pub fn for_current_user() -> Result<Self> {
        let base = std::env::var_os("LOCALAPPDATA").context("Windows не сообщила LOCALAPPDATA")?;
        Self::under(PathBuf::from(base).join("DarkWinterLauncher"))
    }

    pub fn under(root: PathBuf) -> Result<Self> {
        ensure!(root.is_absolute(), "Каталог данных должен быть абсолютным");
        require_plain_path(&root)?;
        for ancestor in root.ancestors() {
            ensure!(
                !ancestor.join("level.dat").exists(),
                "Данные лаунчера нельзя размещать в игровом мире"
            );
        }
        Ok(Self {
            config: root.join("config"),
            builds: root.join("builds"),
            user_data: root.join("user-data"),
            cache: root.join("cache"),
            reports: root.join("diagnostics"),
            root,
        })
    }
}

/// One writer for an installation, held for the entire launcher lifetime.
pub struct ConfigStore {
    paths: AppPaths,
    _lock: File,
}

impl ConfigStore {
    pub fn open(paths: AppPaths) -> Result<Self> {
        require_plain_path(&paths.root)?;
        fs::create_dir_all(&paths.root).context("Не удалось создать каталог данных")?;
        let lock_path = paths.root.join(".launcher.lock");
        require_plain_path(&lock_path)?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(lock_path)?;
        lock.try_lock_exclusive()
            .context("Этот каталог уже открыт другим процессом лаунчера")?;
        for path in [
            &paths.config,
            &paths.builds,
            &paths.user_data,
            &paths.cache,
            &paths.reports,
        ] {
            require_plain_path(path)?;
            fs::create_dir_all(path)?;
        }
        Ok(Self { paths, _lock: lock })
    }

    pub fn paths(&self) -> &AppPaths {
        &self.paths
    }

    pub fn load_launcher(&self) -> Result<LauncherSettings> {
        let path = self.paths.config.join("launcher.json");
        let settings = load_or_default::<LauncherSettings>(&path)?;
        settings.validate()?;
        Ok(settings)
    }

    pub fn save_launcher(&self, settings: &LauncherSettings) -> Result<()> {
        settings.validate()?;
        // Never overwrite an unreadable or future-version settings file.
        self.load_launcher()?;
        self.save_category("launcher.json", settings)
    }

    pub fn load_game(&self) -> Result<GameSettings> {
        let settings = load_or_default::<GameSettings>(&self.paths.config.join("game.json"))?;
        settings.validate()?;
        Ok(settings)
    }

    pub fn save_game(&self, settings: &GameSettings) -> Result<()> {
        settings.validate()?;
        self.load_game()?;
        self.save_category("game.json", settings)
    }

    fn save_category(&self, name: &str, value: &impl Serialize) -> Result<()> {
        let path = self.paths.config.join(name);
        require_plain_path(&path)?;
        // A previous valid version survives replacement or a later interrupted write.
        if path.try_exists()? {
            let previous = fs::read(&path)?;
            atomic_write(&path.with_extension("previous.json"), &previous)?;
        }
        let bytes = serde_json::to_vec_pretty(value)?;
        ensure!(
            bytes.len() as u64 <= SETTINGS_LIMIT,
            "Настройки превышают допустимый размер"
        );
        atomic_write(&path, &bytes)
    }
}

fn load_or_default<T: serde::de::DeserializeOwned + Default>(path: &Path) -> Result<T> {
    require_plain_path(path)?;
    if !path.try_exists()? {
        return Ok(T::default());
    }
    read_json(path, SETTINGS_LIMIT)
}

pub fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    require_plain_path(path)?;
    AtomicFile::new(path, AllowOverwrite)
        .write(|file| {
            file.write_all(bytes)?;
            file.sync_all()
        })
        .map_err(|error| anyhow::anyhow!("Не удалось безопасно сохранить документ: {error}"))
}

/// Reports and exports never replace an existing user file.
pub fn atomic_write_new(path: &Path, bytes: &[u8]) -> Result<()> {
    require_plain_path(path)?;
    AtomicFile::new(path, DisallowOverwrite)
        .write(|file| {
            file.write_all(bytes)?;
            file.sync_all()
        })
        .map_err(|error| {
            anyhow::anyhow!(
                "Не удалось создать новый документ; существующий файл сохранён: {error}"
            )
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store() -> (tempfile::TempDir, ConfigStore) {
        let dir = tempfile::tempdir().unwrap();
        let store = ConfigStore::open(AppPaths::under(dir.path().to_path_buf()).unwrap()).unwrap();
        (dir, store)
    }

    #[test]
    fn enforces_one_writer_and_releases_lock_on_close() {
        let (dir, store) = store();
        assert!(ConfigStore::open(AppPaths::under(dir.path().to_path_buf()).unwrap()).is_err());
        drop(store);
        assert!(ConfigStore::open(AppPaths::under(dir.path().to_path_buf()).unwrap()).is_ok());
    }

    #[test]
    fn keeps_previous_valid_settings_and_ignores_interrupted_temp_file() {
        let (_dir, store) = store();
        let first = LauncherSettings::default();
        store.save_launcher(&first).unwrap();
        let next = LauncherSettings {
            animations: false,
            ..first.clone()
        };
        store.save_launcher(&next).unwrap();
        assert_eq!(store.load_launcher().unwrap(), next);
        let previous: LauncherSettings = read_json(
            &store.paths.config.join("launcher.previous.json"),
            SETTINGS_LIMIT,
        )
        .unwrap();
        assert_eq!(previous, first);
        fs::write(store.paths.config.join("interrupted.tmp"), b"{broken").unwrap();
        assert_eq!(store.load_launcher().unwrap(), next);
    }

    #[test]
    fn corrupt_and_future_settings_are_preserved_verbatim() {
        let (_dir, store) = store();
        let path = store.paths.config.join("launcher.json");
        for bytes in [b"{broken".as_slice(), br#"{"schema":99,"animations":true,"ui_scale":1.0,"channel":"stable","automatic_downloads":false}"#.as_slice()] {
            fs::write(&path, bytes).unwrap();
            assert!(store.save_launcher(&LauncherSettings::default()).is_err());
            assert_eq!(fs::read(&path).unwrap(), bytes);
        }
    }

    #[test]
    fn refuses_game_world_as_data_root() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("level.dat"), b"world").unwrap();
        assert!(AppPaths::under(dir.path().join("launcher")).is_err());
    }

    #[test]
    fn report_exports_never_overwrite_existing_files() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("profile.json");
        fs::write(&path, b"existing-user-data").unwrap();
        assert!(atomic_write_new(&path, b"report").is_err());
        assert_eq!(fs::read(&path).unwrap(), b"existing-user-data");
        let new = dir.path().join("report.json");
        atomic_write_new(&new, b"report").unwrap();
        assert_eq!(fs::read(new).unwrap(), b"report");
    }

    #[cfg(windows)]
    #[test]
    fn rejects_junctions_in_data_path() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("target");
        let link = dir.path().join("link");
        fs::create_dir(&target).unwrap();
        let status = std::process::Command::new("cmd")
            .args(["/C", "mklink", "/J"])
            .arg(&link)
            .arg(&target)
            .output()
            .unwrap();
        assert!(status.status.success());
        assert!(AppPaths::under(link.join("data")).is_err());
    }
}
