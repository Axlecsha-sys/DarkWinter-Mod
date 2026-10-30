// Collects hardware information and selects a conservative launcher performance profile.
use serde::Serialize;
use sysinfo::{CpuRefreshKind, MemoryRefreshKind, RefreshKind, System};

/// Point-in-time inventory. Does not enumerate processes, users, files or network activity.
#[derive(Clone, Debug, Serialize)]
pub struct HardwareSnapshot {
    pub os: String,
    pub architecture: String,
    pub cpu: String,
    pub logical_threads: usize,
    pub physical_cores: Option<usize>,
    pub total_memory_mib: u64,
    pub available_memory_mib: u64,
}

pub fn inspect() -> HardwareSnapshot {
    let system = System::new_with_specifics(
        RefreshKind::nothing()
            .with_memory(MemoryRefreshKind::nothing().with_ram())
            .with_cpu(CpuRefreshKind::nothing()),
    );
    HardwareSnapshot {
        os: System::long_os_version().unwrap_or_else(|| "Не удалось определить ОС".into()),
        architecture: std::env::consts::ARCH.into(),
        cpu: system
            .cpus()
            .first()
            .map(|cpu| cpu.brand().trim().to_owned())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "Не удалось определить процессор".into()),
        logical_threads: system.cpus().len(),
        physical_cores: System::physical_core_count(),
        total_memory_mib: system.total_memory() / (1024 * 1024),
        available_memory_mib: system.available_memory() / (1024 * 1024),
    }
}
