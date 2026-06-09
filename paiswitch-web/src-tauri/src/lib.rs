use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::Mutex,
    time::Duration,
};

use fs2::FileExt;
use tauri::{AppHandle, Manager};

const BACKEND_JAR_RESOURCE: &str = "resources/backend/paiswitch-backend.jar";
const RUNTIME_JAVA_RESOURCE: &str = "resources/runtime/bin/java";
const DEV_BACKEND_JAR: &str = "../../paiswitch-backend/target/paiswitch-backend-1.0.0-SNAPSHOT.jar";
const DEV_RUNTIME_JAVA: &str = "resources/runtime/bin/java";

struct BackendProcess {
    child: Option<Child>,
    api_base_url: String,
    pid_path: PathBuf,
}

impl BackendProcess {
    fn start(app: &AppHandle, data_dir: &Path) -> Result<Self, String> {
        let port = allocate_backend_port()?;
        let jar_path = resolve_backend_jar(app)?;
        let java_path = resolve_java_executable(app);
        let pid_path = data_dir.join("backend.pid");

        cleanup_stale_backend(&pid_path);

        let mut child = Command::new(&java_path)
            .arg("-jar")
            .arg(&jar_path)
            .arg("--spring.profiles.active=desktop")
            .env("PAISWITCH_DATA_DIR", data_dir)
            .env("PAISWITCH_BACKEND_PORT", port.to_string())
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|error| {
                format!(
                    "failed to start backend jar {} with Java {}: {error}",
                    jar_path.display(),
                    java_path.display()
                )
            })?;

        write_pid_file(&pid_path, child.id())?;

        if let Err(error) = wait_for_backend(port) {
            let _ = child.kill();
            let _ = std::fs::remove_file(&pid_path);
            return Err(error);
        }

        Ok(Self {
            child: Some(child),
            api_base_url: format!("http://127.0.0.1:{port}/api/v1"),
            pid_path,
        })
    }

    fn stop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        let _ = std::fs::remove_file(&self.pid_path);
    }
}

impl Drop for BackendProcess {
    fn drop(&mut self) {
        self.stop();
    }
}

struct AppLock {
    _file: File,
}

impl AppLock {
    fn acquire(data_dir: &Path) -> Result<Self, String> {
        let lock_path = data_dir.join("app.lock");
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .open(&lock_path)
            .map_err(|error| format!("failed to open app lock {}: {error}", lock_path.display()))?;
        file.try_lock_exclusive()
            .map_err(|_| "PaiSwitch is already running. Please close the existing window first.".to_string())?;
        Ok(Self { _file: file })
    }
}

struct DesktopState {
    lock: Option<AppLock>,
    backend: Option<BackendProcess>,
    data_dir: PathBuf,
    startup_error: Option<String>,
}

#[tauri::command]
fn desktop_runtime() -> &'static str {
    "tauri"
}

#[tauri::command]
fn backend_api_base_url(state: tauri::State<'_, Mutex<DesktopState>>) -> Result<String, String> {
    let state = state
        .lock()
        .map_err(|_| "desktop state is poisoned".to_string())?;
    state
        .backend
        .as_ref()
        .map(|backend| backend.api_base_url.clone())
        .ok_or_else(|| {
            state
                .startup_error
                .clone()
                .unwrap_or_else(|| "PaiSwitch backend is not running".to_string())
        })
}

#[tauri::command]
fn repair_and_restart_backend(
    app: AppHandle,
    state: tauri::State<'_, Mutex<DesktopState>>,
) -> Result<String, String> {
    let mut state = state
        .lock()
        .map_err(|_| "desktop state is poisoned".to_string())?;

    if state.lock.is_none() {
        state.lock = Some(AppLock::acquire(&state.data_dir)?);
    }

    if let Some(mut backend) = state.backend.take() {
        backend.stop();
    }

    match BackendProcess::start(&app, &state.data_dir) {
        Ok(backend) => {
            let api_base_url = backend.api_base_url.clone();
            state.backend = Some(backend);
            state.startup_error = None;
            Ok(api_base_url)
        }
        Err(error) => {
            state.startup_error = Some(error.clone());
            Err(error)
        }
    }
}

#[tauri::command]
fn open_log_folder(state: tauri::State<'_, Mutex<DesktopState>>) -> Result<(), String> {
    let state = state
        .lock()
        .map_err(|_| "desktop state is poisoned".to_string())?;
    let log_dir = state.data_dir.join("logs");
    std::fs::create_dir_all(&log_dir)
        .map_err(|error| format!("failed to create log dir {}: {error}", log_dir.display()))?;
    open_path(&log_dir)
}

#[tauri::command]
fn copy_diagnostics(state: tauri::State<'_, Mutex<DesktopState>>) -> Result<String, String> {
    let state = state
        .lock()
        .map_err(|_| "desktop state is poisoned".to_string())?;
    Ok(build_diagnostics(&state))
}

#[tauri::command]
fn quit_app(app: AppHandle) {
    app.exit(0);
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            desktop_runtime,
            backend_api_base_url,
            repair_and_restart_backend,
            open_log_folder,
            copy_diagnostics,
            quit_app
        ])
        .setup(|app| {
            let data_dir = app
                .path()
                .app_data_dir()
                .map_err(|error| format!("failed to resolve app data dir: {error}"))?;

            std::fs::create_dir_all(&data_dir)
                .map_err(|error| format!("failed to create app data dir {}: {error}", data_dir.display()))?;

            let (app_lock, backend, startup_error) = match AppLock::acquire(&data_dir) {
                Ok(lock) => match BackendProcess::start(app.handle(), &data_dir) {
                    Ok(process) => (Some(lock), Some(process), None),
                    Err(error) => {
                        eprintln!("PaiSwitch backend autostart failed: {error}");
                        (Some(lock), None, Some(error))
                    }
                },
                Err(error) => {
                    eprintln!("PaiSwitch app lock failed: {error}");
                    (None, None, Some(error))
                }
            };

            app.manage(Mutex::new(DesktopState {
                lock: app_lock,
                backend,
                data_dir,
                startup_error,
            }));

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("failed to run PaiSwitch desktop app");
}

fn allocate_backend_port() -> Result<u16, String> {
    let listener = TcpListener::bind(("127.0.0.1", 0))
        .map_err(|error| format!("failed to allocate backend port: {error}"))?;
    let port = listener
        .local_addr()
        .map_err(|error| format!("failed to read backend port: {error}"))?
        .port();
    drop(listener);
    Ok(port)
}

fn wait_for_backend(port: u16) -> Result<(), String> {
    let address = SocketAddr::from(([127, 0, 0, 1], port));
    for _ in 0..80 {
        if TcpStream::connect_timeout(&address, Duration::from_millis(250)).is_ok() {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(250));
    }

    Err(format!("backend did not become ready on port {port}"))
}

fn write_pid_file(pid_path: &Path, pid: u32) -> Result<(), String> {
    let mut file = File::create(pid_path)
        .map_err(|error| format!("failed to write backend pid file {}: {error}", pid_path.display()))?;
    writeln!(file, "{pid}")
        .map_err(|error| format!("failed to write backend pid file {}: {error}", pid_path.display()))
}

fn cleanup_stale_backend(pid_path: &Path) {
    let Some(pid) = read_pid(pid_path) else {
        return;
    };
    if is_paiswitch_backend_process(pid) {
        let _ = kill_process(pid);
    }
    let _ = std::fs::remove_file(pid_path);
}

fn read_pid(pid_path: &Path) -> Option<u32> {
    let mut file = File::open(pid_path).ok()?;
    let mut text = String::new();
    file.read_to_string(&mut text).ok()?;
    text.trim().parse::<u32>().ok()
}

#[cfg(unix)]
fn is_paiswitch_backend_process(pid: u32) -> bool {
    let output = Command::new("ps")
        .arg("-p")
        .arg(pid.to_string())
        .arg("-o")
        .arg("command=")
        .output();
    output
        .ok()
        .and_then(|out| String::from_utf8(out.stdout).ok())
        .map(|command| command.contains("paiswitch-backend") || command.contains("paiswitch-backend.jar"))
        .unwrap_or(false)
}

#[cfg(not(unix))]
fn is_paiswitch_backend_process(_pid: u32) -> bool {
    true
}

#[cfg(unix)]
fn kill_process(pid: u32) -> std::io::Result<()> {
    Command::new("kill").arg(pid.to_string()).status().map(|_| ())
}

#[cfg(windows)]
fn kill_process(pid: u32) -> std::io::Result<()> {
    Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/F"])
        .status()
        .map(|_| ())
}

fn build_diagnostics(state: &DesktopState) -> String {
    let log_path = state.data_dir.join("logs").join("backend.log");
    let backend_pid = read_pid(&state.data_dir.join("backend.pid"))
        .map(|pid| pid.to_string())
        .unwrap_or_else(|| "<none>".to_string());
    let api_base_url = state
        .backend
        .as_ref()
        .map(|backend| backend.api_base_url.as_str())
        .unwrap_or("<not running>");
    let startup_error = state.startup_error.as_deref().unwrap_or("<none>");
    let log_tail = read_log_tail(&log_path, 12000);

    format!(
        "PaiSwitch Desktop Diagnostics\n\
         data_dir: {}\n\
         backend_pid: {}\n\
         backend_api_base_url: {}\n\
         startup_error: {}\n\
         log_path: {}\n\
         \n\
         --- backend.log tail ---\n\
         {}\n",
        state.data_dir.display(),
        backend_pid,
        api_base_url,
        startup_error,
        log_path.display(),
        log_tail
    )
}

fn read_log_tail(path: &Path, max_chars: usize) -> String {
    let Ok(text) = std::fs::read_to_string(path) else {
        return "<log file not found>".to_string();
    };
    if text.len() <= max_chars {
        return text;
    }

    let mut start = text.len() - max_chars;
    while !text.is_char_boundary(start) {
        start += 1;
    }
    text[start..].to_string()
}

#[cfg(target_os = "macos")]
fn open_path(path: &Path) -> Result<(), String> {
    Command::new("open")
        .arg(path)
        .status()
        .map_err(|error| format!("failed to open {}: {error}", path.display()))
        .and_then(|status| {
            if status.success() {
                Ok(())
            } else {
                Err(format!("failed to open {}: exit status {status}", path.display()))
            }
        })
}

#[cfg(target_os = "windows")]
fn open_path(path: &Path) -> Result<(), String> {
    Command::new("explorer")
        .arg(path)
        .status()
        .map_err(|error| format!("failed to open {}: {error}", path.display()))
        .and_then(|status| {
            if status.success() {
                Ok(())
            } else {
                Err(format!("failed to open {}: exit status {status}", path.display()))
            }
        })
}

#[cfg(all(unix, not(target_os = "macos")))]
fn open_path(path: &Path) -> Result<(), String> {
    Command::new("xdg-open")
        .arg(path)
        .status()
        .map_err(|error| format!("failed to open {}: {error}", path.display()))
        .and_then(|status| {
            if status.success() {
                Ok(())
            } else {
                Err(format!("failed to open {}: exit status {status}", path.display()))
            }
        })
}

fn resolve_backend_jar(app: &AppHandle) -> Result<PathBuf, String> {
    if let Ok(resource_dir) = app.path().resource_dir() {
        let bundled = resource_dir.join(BACKEND_JAR_RESOURCE);
        if bundled.exists() {
            return Ok(bundled);
        }
    }

    let dev_path = Path::new(env!("CARGO_MANIFEST_DIR")).join(DEV_BACKEND_JAR);
    if dev_path.exists() {
        return Ok(dev_path);
    }

    Err(format!(
        "backend jar not found; run `npm run build:backend` before starting desktop app"
    ))
}

fn resolve_java_executable(app: &AppHandle) -> PathBuf {
    if let Ok(resource_dir) = app.path().resource_dir() {
        let bundled = resource_dir.join(RUNTIME_JAVA_RESOURCE);
        if bundled.exists() {
            return bundled;
        }
    }

    let dev_runtime = Path::new(env!("CARGO_MANIFEST_DIR")).join(DEV_RUNTIME_JAVA);
    if dev_runtime.exists() {
        return dev_runtime;
    }

    PathBuf::from("java")
}
