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
            // params: {key, dir, rule, engine, site_json, ua, proxy}
            //   - drpy 源：Android 侧下载 ext 规则脚本 → rule/engine → DrpySpider
            //   - JSON 规则源：整个站点 JSON（含 ext 数组）→ site_json → JsonSpider
            //   - ua：可选全局 User-Agent（对齐原设置 UA，注入 drpy 网络请求）
            //   - proxy：可选代理地址 scheme://user:pass@host:port（对齐原代理设置）
            let p = json::safe_object(&json::parse(params));
            let dir = p.get("dir").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let rule = p.get("rule").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let engine_script = p.get("engine").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let site_json = p.get("site_json").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let ua = p.get("ua").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let proxy = p.get("proxy").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if !site_json.is_empty() {
                match JsonSpider::new(&site_json) {
                    Ok(spider) => {
                        manager.register(key, Box::new(spider));
                        r#"{"code":0,"msg":"ok"}"#.to_string()
                    }
                    Err(e) => format!(r#"{{"code":-1,"msg":"{}"}}"#, e),
                }
            } else if !rule.is_empty() {
                let spider = DrpySpider::new(dir, rule, engine_script, &ua, &proxy);
                manager.register(key, Box::new(spider));
                r#"{"code":0,"msg":"ok"}"#.to_string()
            } else {
                r#"{"code":-1,"msg":"no rule"}"#.to_string()
            }
        }
        // 聚合搜索：遍历全部已注册源，并行搜索并合并结果（对齐原项目聚合搜索）。
        // params: {key: 搜索词}；返回 {list:[...], source:"all"}，每条带 source 站点名。
        "searchAll" => {
            let p = json::safe_object(&json::parse(params));
            let keyword = p.get("key").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let mut out: Vec<serde_json::Value> = Vec::new();
            for k in manager.keys() {
                if let Some(s) = manager.get(&k) {
                    if let Ok(res) = s.search_content(&keyword, false, "1") {
                        if let Ok(v) = json::parse(&res) {
                            if let Some(arr) = v.get("list").and_then(|x| x.as_array()) {
                                for item in arr {
                                    let mut it = item.clone();
                                    if let Some(obj) = it.as_object_mut() {
                                        obj.insert("source".to_string(), serde_json::json!(k));
                                    }
                                    out.push(it);
                                }
                            }
                        }
                    }
                }
            }
            // 按影片名去重（多源重复合并，保留首个含来源标注），避免壳侧展示重复
            let mut seen = std::collections::HashSet::new();
            let mut dedup: Vec<serde_json::Value> = out.into_iter().filter(|it| {
                let name = it.get("vod_name").and_then(|v| v.as_str()).unwrap_or("");
                if name.is_empty() {
                    true
                } else {
                    seen.insert(name.to_string())
                }
            }).collect();
            // 按名称稳定排序（引擎内统一排序，壳直接透传）
            dedup.sort_by(|a, b| {
                let na = a.get("vod_name").and_then(|v| v.as_str()).unwrap_or("");
                let nb = b.get("vod_name").and_then(|v| v.as_str()).unwrap_or("");
                na.cmp(nb)
            });
            serde_json::json!({"list": dedup, "source": "all"}).to_string()
        }
        // EPG 节目单：抓取 XMLTV 并按频道名返回节目单（对齐原项目直播 EPG）。
        // params: {url: XMLTV 地址, name: 频道名}；返回 {list:[{start,end,time,title}], channel}
        "epg" => {
            let p = json::safe_object(&json::parse(params));
            let url = p.get("url").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let name = p.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
            crate::epg::fetch_epg(&Net::new(), &url, &name)
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
                    let raw = spider.detail_content(&ids).unwrap_or_default();
                    // 下沉多线路解析：vod_play_url → 结构化 vod_lines（壳直接透传，缺失时壳回退本地解析）
                    if let Ok(mut v) = json::parse(&raw) {
                        if let Some(arr) = v.get_mut("list").and_then(|x| x.as_array_mut()) {
                            for item in arr.iter_mut() {
                                if let Some(obj) = item.as_object_mut() {
                                    let from = obj.get("vod_play_from").and_then(|x| x.as_str()).unwrap_or("");
                                    let url = obj.get("vod_play_url").and_then(|x| x.as_str()).unwrap_or("");
                                    obj.insert("vod_lines".to_string(), parse_vod_lines(from, url));
                                }
                            }
                        }
                        v.to_string()
                    } else {
                        raw
                    }
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

/// 解析 TVBox 播放线路为结构化 JSON（下沉壳的多线路解析）。
/// vod_play_url：$$$ 分隔线路，# 分隔集，$ 分隔 名称/地址。
/// 返回 [{name: 线路名, episodes:[{name, url}]}]，无数据返回 null。
fn parse_vod_lines(vod_play_from: &str, vod_play_url: &str) -> serde_json::Value {
    if vod_play_url.is_empty() {
        return serde_json::Value::Null;
    }
    let froms: Vec<&str> = vod_play_from.split("$$$").filter(|s| !s.is_empty()).collect();
    let urls: Vec<&str> = vod_play_url.split("$$$").filter(|s| !s.is_empty()).collect();
    let lines: Vec<serde_json::Value> = urls.iter().enumerate().map(|(i, u)| {
        let name = froms
            .get(i)
            .map(|s| s.to_string())
            .unwrap_or_else(|| format!("线路{}", i + 1));
        let eps: Vec<serde_json::Value> = u.split('#')
            .filter(|s| !s.is_empty())
            .map(|ep| match ep.split_once('$') {
                Some((n, uu)) => serde_json::json!({"name": n, "url": uu}),
                None => serde_json::json!({"name": ep, "url": ep}),
            })
            .collect();
        serde_json::json!({"name": name, "episodes": eps})
    }).collect();
    serde_json::json!(lines)
}
