//! 规则侧 key-value 缓存（对齐原版 quickjs `Local` → `Prefers` SharedPreferences）。
//!
//! 为什么需要它：drpy 规则常用 `setItem('token', ...)` / `setItem('cookie', ...)`
//! 把鉴权信息存下来给后续请求复用。原版落在 SharedPreferences，跨线程、跨进程重启都有效。
//!
//! 我们原先把它放在 `globalThis.__LOCAL = {}` 这个纯 JS 对象里，而引擎上下文是
//! `thread_local!` 每线程一份 —— 主线程 set 的值，海报线程 / 搜索线程读不到，
//! 规则只能反复重新取 token，表现为"偶发失败、越用越慢"。
//!
//! 这里改成进程内全局 HashMap（跨线程唯一），并在拿到 filesDir 后落盘成 JSON，
//! 使重启后仍能命中（对齐原版持久化语义）。

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

static KV: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
static KV_DIR: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();

fn map() -> &'static Mutex<HashMap<String, String>> {
    KV.get_or_init(|| Mutex::new(HashMap::new()))
}

fn dir_slot() -> &'static Mutex<Option<PathBuf>> {
    KV_DIR.get_or_init(|| Mutex::new(None))
}

fn file_path() -> Option<PathBuf> {
    let d = dir_slot().lock().ok()?.clone()?;
    Some(d.join("rule_kv.json"))
}

/// 由 JNI `init` 传入 filesDir 绝对路径：建目录 + 载入已有缓存。
/// 幂等：157 个站点各调一次 init，同一路径只扫一次文件。
pub fn init_dir(dir: &str) {
    if dir.trim().is_empty() {
        return;
    }
    let p = PathBuf::from(dir);
    if let Ok(slot) = dir_slot().lock() {
        if slot.as_ref() == Some(&p) {
            return;
        }
    }
    let _ = std::fs::create_dir_all(&p);
    if let Ok(mut slot) = dir_slot().lock() {
        *slot = Some(p);
    }
    load();
}

fn load() {
    let Some(fp) = file_path() else { return };
    let Ok(txt) = std::fs::read_to_string(&fp) else { return };
    let Ok(v) = serde_json::from_str::<HashMap<String, String>>(&txt) else { return };
    if let Ok(mut m) = map().lock() {
        *m = v;
    }
}

fn save() {
    let Some(fp) = file_path() else { return };
    // 先 clone 快照再写文件：避免持锁期间做磁盘 IO，也避免与 get/set 争锁
    let snapshot = match map().lock() {
        Ok(m) => m.clone(),
        Err(_) => return,
    };
    if let Ok(txt) = serde_json::to_string(&snapshot) {
        let _ = std::fs::write(&fp, txt);
    }
}

pub fn get(key: &str) -> String {
    map()
        .lock()
        .map(|m| m.get(key).cloned().unwrap_or_default())
        .unwrap_or_default()
}

pub fn set(key: &str, value: &str) {
    if key.is_empty() {
        return;
    }
    if let Ok(mut m) = map().lock() {
        m.insert(key.to_string(), value.to_string());
    }
    save();
}

pub fn del(key: &str) {
    if let Ok(mut m) = map().lock() {
        m.remove(key);
    }
    save();
}

/// 清空规则缓存（设置页"清理缓存"可调）
pub fn clear() {
    if let Ok(mut m) = map().lock() {
        m.clear();
    }
    save();
}
