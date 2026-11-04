//! JNI 桥 —— 安卓侧（Java/Kotlin）调用 Rust 引擎的入口。
//!
//! 仅在 feature "jni" 下编译（`cargo ndk -t arm64-v8a build --release --features jni`），
//! 生成 `libtv_engine.so`，Java 侧通过 `System.loadLibrary("tv_engine")` 加载，
//! 再经 `native` 方法调用。
//!
//! 线程模型：与 fongmi 原版 quickjs 一致 —— JS 引擎（quickjs）非线程安全，
//! 原版用 ThreadLocal 每线程一份。这里同样用 `thread_local!` 持有每线程独立的
//! (Net, SpiderManager)，因此 DrpySpider（内部 quickjs）可安全运行。

use std::cell::RefCell;

use jni::objects::{JClass, JString};
use jni::sys::{jobject, jstring};
use jni::JNIEnv;

use crate::drpy_spider::DrpySpider;
use crate::json;
use crate::json_spider::JsonSpider;
use crate::net::Net;
use crate::spider::SpiderManager;

// 每线程一份引擎状态（对齐原版 quickjs 的 ThreadLocal 语义）。
thread_local! {
    static ENGINE: RefCell<(Net, SpiderManager)> = RefCell::new((Net::new(), SpiderManager::new()));
}

/// 通用入口：执行 `action`（init/home/category/detail/search/player/live/proxyLocal...）。
/// Java 侧签名：
///   public static native String execute(String action, String key, String params);
#[no_mangle]
pub extern "system" fn Java_com_xinghui_tv_Engine_execute(
    mut env: JNIEnv,
    _class: JClass,
    action: JString,
    key: JString,
    params: JString,
) -> jstring {
    // jni 0.21：get_string 返回 Result<JavaStr>，先 .map(|s| s.into()) 转 String 再取默认。
    let action: String = env.get_string(&action).map(|s| s.into()).unwrap_or_default();
    let key: String = env.get_string(&key).map(|s| s.into()).unwrap_or_default();
    let params: String = env.get_string(&params).map(|s| s.into()).unwrap_or_default();

    let result = ENGINE.with(|cell| dispatch(&action, &key, &params, &mut cell.borrow_mut().1));
    env.new_string(result)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// 分发器：`init` 注册 drpy 规则；其余按 action 路由到对应 Spider 方法，返回 JSON。
fn dispatch(action: &str, key: &str, params: &str, manager: &mut SpiderManager) -> String {
    match action {
        "init" => {
            // params: {key, dir, rule, engine, site_json}
            //   - drpy 源：Android 侧下载 ext 规则脚本 → rule/engine → DrpySpider
            //   - JSON 规则源：整个站点 JSON（含 ext 数组）→ site_json → JsonSpider
            let p = json::safe_object(&json::parse(params));
            let dir = p.get("dir").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let rule = p.get("rule").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let engine_script = p.get("engine").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let site_json = p.get("site_json").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if !site_json.is_empty() {
                match JsonSpider::new(&site_json) {
                    Ok(spider) => {
                        manager.register(key, Box::new(spider));
                        r#"{"code":0,"msg":"ok"}"#.to_string()
                    }
                    Err(e) => format!(r#"{{"code":-1,"msg":"{}"}}"#, e),
                }
            } else if !rule.is_empty() {
                let spider = DrpySpider::new(dir, rule, engine_script);
                manager.register(key, Box::new(spider));
                r#"{"code":0,"msg":"ok"}"#.to_string()
            } else {
                r#"{"code":-1,"msg":"no rule"}"#.to_string()
            }
        }
        _ => {
            let Some(spider) = manager.get(key) else {
                return String::new();
            };
            let param_map = if params.is_empty() {
                serde_json::Map::new()
            } else {
                json::safe_object(&json::parse(params)).as_object().cloned().unwrap_or_default()
            };
            let extend: std::collections::HashMap<String, String> =
                param_map.iter().map(|(k, v)| (k.clone(), v.as_str().unwrap_or_default().to_string())).collect();

            match action {
                "home" => spider.home_content(false).unwrap_or_default(),
                "category" => spider
                    .category_content(
                        extend.get("tid").map(String::as_str).unwrap_or(""),
                        extend.get("pg").map(String::as_str).unwrap_or("1"),
                        false,
                        &extend,
                    )
                    .unwrap_or_default(),
                "detail" => {
                    let ids: Vec<String> =
                        extend.get("ids").map(|s| s.split(',').map(String::from).collect()).unwrap_or_default();
                    spider.detail_content(&ids).unwrap_or_default()
                }
                "search" => spider
                    .search_content(extend.get("key").map(String::as_str).unwrap_or(""), false, "1")
                    .unwrap_or_default(),
                "player" => spider
                    .player_content(
                        extend.get("flag").map(String::as_str).unwrap_or(""),
                        extend.get("id").map(String::as_str).unwrap_or(""),
                        &[],
                    )
                    .unwrap_or_default(),
                "live" => spider.live_content().unwrap_or_default(),
                _ => String::new(),
            }
        }
    }
}

/// 保留 JNI 入口占位，供后续扩展（如 proxyLocal 字节流返回）。
#[allow(dead_code)]
pub fn proxy_entry(_env: JNIEnv, _class: JClass, _key: JString, _params: JString) -> jobject {
    std::ptr::null_mut()
}
