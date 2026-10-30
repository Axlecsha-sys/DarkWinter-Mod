// Defines shared launcher data types, validated paths and bounded file operations.
use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::{fs, io::Read, path::Path};

pub const SETTINGS_LIMIT: u64 = 64 * 1024;

/// Refuse symlinks and Windows junctions at every existing ancestor.
/// Callers still need a lock when mutating data; this is not a sandbox for hostile local users.
pub fn require_plain_path(path: &Path) -> Result<()> {
    for ancestor in path.ancestors() {
        let meta = match fs::symlink_metadata(ancestor) {
            Ok(meta) => meta,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error).context("Не удалось проверить путь"),
        };
        ensure!(
            !meta.file_type().is_symlink(),
            "Путь содержит символическую ссылку"
        );
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            ensure!(
                meta.file_attributes() & 0x400 == 0,
                "Путь содержит Windows reparse point"
            );
        }
    }
    Ok(())
}

/// Parse failures deliberately omit source text, which may contain profile secrets.
pub fn read_json<T: DeserializeOwned>(path: &Path, limit: u64) -> Result<T> {
    require_plain_path(path)?;
    let file = fs::File::open(path).context("Не удалось открыть документ")?;
    ensure!(file.metadata()?.is_file(), "Ожидался обычный файл");
    let mut bytes = Vec::new();
    file.take(limit + 1).read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() as u64 <= limit,
        "Документ превышает допустимый размер"
    );
    serde_json::from_slice(&bytes)
        .map_err(|_| anyhow::anyhow!("Некорректный формат JSON; исходный файл сохранён"))
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LauncherSettings {
    pub schema: u32,
    pub animations: bool,
    pub ui_scale: f32,
    pub channel: ReleaseChannel,
    pub automatic_downloads: bool,
}

impl Default for LauncherSettings {
    fn default() -> Self {
        Self {
            schema: 1,
            animations: true,
            ui_scale: 1.0,
            channel: ReleaseChannel::Stable,
            automatic_downloads: false,
        }
    }
}

impl LauncherSettings {
    pub fn validate(&self) -> Result<()> {
        ensure!(
            self.schema == 1,
            "Неизвестная версия настроек лаунчера; файл сохранён"
        );
        ensure!(
            self.ui_scale.is_finite() && (0.8..=1.5).contains(&self.ui_scale),
            "Масштаб должен быть от 80 до 150 процентов"
        );
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReleaseChannel {
    Stable,
    Preview,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum GraphicsProfile {
    Low,
    Balanced,
    High,
}

/// Separate versioned category; no changes to the game's files are implied by this record.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GameSettings {
    pub schema: u32,
    pub selected_build: Option<String>,
    pub java_path: Option<std::path::PathBuf>,
    pub memory_mib: u32,
    pub graphics: GraphicsProfile,
}

impl Default for GameSettings {
    fn default() -> Self {
        Self {
            schema: 1,
            selected_build: None,
            java_path: None,
            memory_mib: 4096,
            graphics: GraphicsProfile::Balanced,
        }
    }
}

impl GameSettings {
    pub fn validate(&self) -> Result<()> {
        ensure!(
            self.schema == 1,
            "Неизвестная версия настроек игры; файл сохранён"
        );
        ensure!(
            (512..=65536).contains(&self.memory_mib),
            "Некорректный объём памяти"
        );
        if let Some(build) = &self.selected_build {
            validate_build_id(build)?;
        }
        if let Some(path) = &self.java_path {
            ensure!(path.is_absolute(), "Путь Java должен быть абсолютным");
        }
        Ok(())
    }
}

pub fn validate_build_id(id: &str) -> Result<()> {
    let stem = id
        .split('.')
        .next()
        .unwrap_or_default()
        .to_ascii_uppercase();
    let reserved = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || (stem.len() == 4
            && (stem.starts_with("COM") || stem.starts_with("LPT"))
            && matches!(stem.as_bytes()[3], b'1'..=b'9'));
    if id.is_empty()
        || id.len() > 80
        || !id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.')
        || id.starts_with('.')
        || id.ends_with('.')
        || reserved
    {
        bail!("Некорректный идентификатор сборки");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_unknown_schemas_and_unsafe_build_ids() {
        let settings = LauncherSettings {
            schema: 2,
            ..Default::default()
        };
        assert!(settings.validate().is_err());
        for value in [
            "../saves",
            "C:disk",
            ".",
            "",
            "release.",
            "build name",
            "CON",
            "nul.json",
            "com1",
            "LPT9",
        ] {
            assert!(validate_build_id(value).is_err(), "{value}");
        }
        validate_build_id("1.0.0-rc1").unwrap();
    }

    #[test]
    fn rejects_invalid_ui_scale_and_unknown_fields() {
        for ui_scale in [f32::NAN, f32::INFINITY, 0.0, 2.0] {
            assert!(
                LauncherSettings {
                    ui_scale,
                    ..Default::default()
                }
                .validate()
                .is_err()
            );
        }
        let mut value = serde_json::to_value(LauncherSettings::default()).unwrap();
        value["extra"] = true.into();
        assert!(serde_json::from_value::<LauncherSettings>(value).is_err());
    }
}
