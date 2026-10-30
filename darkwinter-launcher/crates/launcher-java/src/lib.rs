// Discovers and probes Java runtimes without inheriting ambient Java launch options.
use anyhow::{Context, Result, ensure};
use serde::Serialize;
use std::{
    collections::HashSet,
    io::Read,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::mpsc,
    time::{Duration, Instant},
};
use wait_timeout::ChildExt;

#[derive(Clone, Debug, Serialize)]
pub struct JavaRuntime {
    pub executable: PathBuf,
    pub version: String,
    pub major: u32,
    pub architecture: String,
    pub vendor: String,
}

impl JavaRuntime {
    pub fn compatible_with(&self, required_major: u32) -> bool {
        // Exact major until a release manifest explicitly approves a wider range.
        self.major == required_major && matches!(self.architecture.as_str(), "amd64" | "x86_64")
    }
}

/// Bounded local discovery only; no PATH/JAVA_HOME/registry changes or downloads.
pub fn discover(explicit: Option<&Path>) -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(path) = explicit {
        candidates.push(path.to_path_buf());
    }
    if let Some(home) = std::env::var_os("JAVA_HOME") {
        candidates.push(PathBuf::from(home).join("bin/java.exe"));
    }
    if let Some(path) = std::env::var_os("PATH") {
        candidates.extend(
            std::env::split_paths(&path)
                .filter(|p| p.is_absolute())
                .map(|p| p.join("java.exe")),
        );
    }
    let mut roots = Vec::new();
    if let Some(home) = std::env::var_os("USERPROFILE") {
        roots.push(PathBuf::from(home).join(".jdks"));
    }
    if let Some(programs) = std::env::var_os("ProgramFiles") {
        for vendor in ["Java", "Eclipse Adoptium", "Microsoft", "Amazon Corretto"] {
            roots.push(PathBuf::from(&programs).join(vendor));
        }
    }
    for root in roots {
        if let Ok(entries) = std::fs::read_dir(root) {
            let mut homes: Vec<_> = entries
                .filter_map(Result::ok)
                .take(64)
                .map(|entry| entry.path())
                .collect();
            homes.sort();
            candidates.extend(homes.into_iter().map(|p| p.join("bin/java.exe")));
        }
    }
    let mut seen = HashSet::new();
    candidates
        .into_iter()
        .filter(|path| path.is_absolute() && path.is_file())
        .filter_map(|path| std::fs::canonicalize(path).ok())
        .filter(|path| seen.insert(path.to_string_lossy().to_lowercase()))
        .take(16)
        .collect()
}

pub fn inspect(executable: &Path, timeout: Duration) -> Result<JavaRuntime> {
    ensure!(executable.is_absolute(), "Для Java нужен абсолютный путь");
    let mut command = Command::new(executable);
    command
        .args(["-XshowSettings:properties", "-version"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped());
    // Avoid ambient agent/classpath injection in a version probe.
    for variable in [
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "CLASSPATH",
    ] {
        command.env_remove(variable);
    }
    let bytes = run_probe(command, timeout)?;
    parse_properties(executable.to_path_buf(), &String::from_utf8_lossy(&bytes))
}

fn run_probe(mut command: Command, timeout: Duration) -> Result<Vec<u8>> {
    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }
    let mut child = command
        .spawn()
        .context("Не удалось запустить проверку Java")?;
    let stderr = child
        .stderr
        .take()
        .context("Java не предоставила диагностический канал")?;
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let mut bytes = Vec::new();
        let result = stderr.take(65537).read_to_end(&mut bytes).map(|_| bytes);
        let _ = tx.send(result);
    });
    let deadline = Instant::now() + timeout;
    let wait = child.wait_timeout(timeout);
    match wait {
        Ok(Some(status)) => {
            ensure!(status.success(), "Java завершила проверку с ошибкой");
            // An inherited pipe must not make a successful process wait indefinitely.
            let bytes = rx
                .recv_timeout(deadline.saturating_duration_since(Instant::now()))
                .map_err(|_| anyhow::anyhow!("Истекло время чтения ответа Java"))??;
            ensure!(bytes.len() <= 65536, "Java вернула слишком большой ответ");
            Ok(bytes)
        }
        outcome => {
            let _ = child.kill();
            let _ = child.wait();
            // Do not wait on an inherited pipe from a misbehaving non-Java executable.
            if let Err(error) = outcome {
                return Err(error).context("Ошибка ожидания Java");
            }
            anyhow::bail!("Java не ответила за отведённое время")
        }
    }
}

fn parse_properties(executable: PathBuf, text: &str) -> Result<JavaRuntime> {
    let property = |name: &str| -> Result<String> {
        let mut values = text
            .lines()
            .filter_map(|line| line.trim().split_once(" = "))
            .filter(|(key, _)| *key == name)
            .map(|(_, value)| value.trim());
        let value = values
            .next()
            .context("Java не сообщила обязательные свойства")?;
        ensure!(
            values.next().is_none() && !value.is_empty() && value.len() <= 256,
            "Некорректные свойства Java"
        );
        Ok(value.to_owned())
    };
    let version = property("java.version")?;
    let component = if let Some(legacy) = version.strip_prefix("1.") {
        legacy
    } else {
        &version
    };
    let digits: String = component
        .chars()
        .take_while(|c| c.is_ascii_digit())
        .collect();
    let major: u32 = digits
        .parse()
        .context("Не удалось определить версию Java")?;
    ensure!(major > 0 && major < 1000, "Некорректная версия Java");
    Ok(JavaRuntime {
        executable,
        version,
        major,
        architecture: property("os.arch")?,
        vendor: property("java.vendor")?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distinguishes_java25_from_legacy_and_wrong_architecture() {
        let parse = |version: &str, arch: &str| {
            parse_properties(PathBuf::from("java.exe"), &format!(
            "Property settings:\n    java.version = {version}\n    java.vendor = OpenJDK\n    os.arch = {arch}\n" )).unwrap()
        };
        assert!(parse("25.0.1", "amd64").compatible_with(25));
        assert!(parse("25-ea", "x86_64").compatible_with(25));
        assert!(!parse("21.0.2", "amd64").compatible_with(25));
        assert!(!parse("25", "x86").compatible_with(25));
        assert!(!parse("26", "amd64").compatible_with(25));
        assert_eq!(parse("1.8.0_401", "amd64").major, 8);
    }

    #[test]
    fn rejects_incomplete_and_duplicate_properties() {
        assert!(parse_properties(PathBuf::new(), "java version 25").is_err());
        assert!(
            parse_properties(
                PathBuf::new(),
                "java.version = 25\njava.version = 21\nos.arch = amd64\njava.vendor = Test"
            )
            .is_err()
        );
    }

    // This executable doubles as an isolated Rust subprocess fixture.
    #[test]
    fn probe_process_fixture() {
        match std::env::var("DWL_JAVA_PROBE_FIXTURE").as_deref() {
            Ok("hang") => std::thread::sleep(Duration::from_secs(10)),
            Ok("success") => eprintln!("java.version = 25\nos.arch = amd64\njava.vendor = Fixture"),
            _ => {}
        }
    }

    fn fixture_command(mode: &str) -> Command {
        let mut command = Command::new(std::env::current_exe().unwrap());
        command
            .args(["--exact", "tests::probe_process_fixture", "--nocapture"])
            .env("DWL_JAVA_PROBE_FIXTURE", mode);
        command
    }

    #[test]
    fn bounds_real_process_execution_and_drains_stderr() {
        let bytes = run_probe(fixture_command("success"), Duration::from_secs(3)).unwrap();
        assert!(
            parse_properties(
                PathBuf::from("fixture.exe"),
                &String::from_utf8_lossy(&bytes)
            )
            .unwrap()
            .compatible_with(25)
        );
        let start = Instant::now();
        assert!(run_probe(fixture_command("hang"), Duration::from_millis(200)).is_err());
        assert!(start.elapsed() < Duration::from_secs(3));
    }
}
