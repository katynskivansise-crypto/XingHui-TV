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
            // 站点级 header JSON（drpy2.js 规则源如"新闪雷/茶杯狐"配置了站点 header，对齐原项目 getHeaders）
            let header = p
                .get("header")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            if !site_json.is_empty() {
                // 站点 JSON 含 api 字段 → TVBox 标准 api 接口源（对齐原项目 SiteViewModel）；
                // 否则为纯静态 JSON 规则（type=3，数据写在 ext[].list）→ JsonSpider
                let sv: serde_json::Value = serde_json::from_str(&site_json).unwrap_or_default();
                let has_api = sv
                    .get("api")
                    .and_then(|x| x.as_str())
                    .map(|s| !s.is_empty())
                    .unwrap_or(false);
                if has_api {
                    // 传全局 UA/代理给 ApiSpider（站点 header 优先、全局兜底，对齐原项目 getHeaders）
                    match crate::api_spider::ApiSpider::new(&site_json, &ua, &proxy) {
                        Ok(spider) => {
                            manager.register(key, Box::new(spider));
                            r#"{"code":0,"msg":"ok"}"#.to_string()
                        }
                        Err(e) => format!(r#"{{"code":-1,"msg":"{}"}}"#, e),
                    }
                } else {
                    match JsonSpider::new(&site_json) {
                        Ok(spider) => {
                            manager.register(key, Box::new(spider));
                            r#"{"code":0,"msg":"ok"}"#.to_string()
                        }
                        Err(e) => format!(r#"{{"code":-1,"msg":"{}"}}"#, e),
                    }
                }
            } else if !rule.is_empty() {
                let spider = DrpySpider::new(dir, rule, engine_script, &ua, &proxy, &header);
                manager.register(key, Box::new(spider));
                r#"{"code":0,"msg":"ok"}"#.to_string()
            } else {
                r#"{"code":-1,"msg":"no rule"}"#.to_string()
            }
        }
        // 聚合搜索：遍历全部已注册源，并行搜索并合并结果（对齐原项目聚合搜索）。
        // params: {key: 搜索词, keepAll: 可选是否保多站}；返回 {list:[...], source:"all"}，
        // 每条带 source 站点名。keepAll=true（自动换站用）时不去重，保留各站同名结果。
        "searchAll" => {
            let p = json::safe_object(&json::parse(params));
            let keyword = p.get("key").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let keep_all = p.get("keepAll").and_then(|v| v.as_bool()).unwrap_or(false);
            let mut out: Vec<serde_json::Value> = Vec::new();
            for k in manager.keys() {
                if let Some(s) = manager.get(&k) {
                    if let Ok(res) = s.search_content(&keyword, false, "1") {
                        let v = json::parse(&res);
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
            let dedup: Vec<serde_json::Value> = if keep_all {
                out
            } else {
                // 按影片名去重（多源重复合并，保留首个含来源标注），避免壳侧展示重复
                let mut seen = std::collections::HashSet::new();
                out.into_iter().filter(|it| {
                    let name = it.get("vod_name").and_then(|v| v.as_str()).unwrap_or("");
                    if name.is_empty() {
                        true
                    } else {
                        seen.insert(name.to_string())
                    }
                }).collect()
            };
            // 按名称稳定排序（引擎内统一排序，壳直接透传）
            let mut dedup = dedup;
            dedup.sort_by(|a, b| {
                let na = a.get("vod_name").and_then(|v| v.as_str()).unwrap_or("");
                let nb = b.get("vod_name").and_then(|v| v.as_str()).unwrap_or("");
                na.cmp(nb)
            });
            serde_json::json!({"list": dedup, "source": "all"}).to_string()
        }
        // 海报代理（对齐原项目 ImageLoader 走网络层）：壳传 url/ua/proxy，引擎用临时
        // Net 拉图片字节 → base64 返回。复用现有 jstring 通道（execute 签名不变），
        // 不带防盗链 Referer 时仍比壳 HttpURLConnection 直连更稳（统一 ureq + UA/代理）。
        "proxy_image" => {
            let p = json::safe_object(&json::parse(params));
            let url = p.get("url").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let ua = p.get("ua").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let proxy = p.get("proxy").and_then(|v| v.as_str()).unwrap_or("").to_string();
            if url.is_empty() {
                String::new()
            } else {
                let mut net = Net::new();
                if !ua.is_empty() {
                    net.set_ua(&ua);
                }
                if !proxy.is_empty() {
                    net.set_proxy(&proxy);
                }
                let bytes = net.bytes(&url);
                if bytes.is_empty() {
                    String::new()
                } else {
                    base64_encode(&bytes)
                }
            }
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
                "home" => match spider.home_content(false) {
                    Ok(s) => s,
                    Err(e) => serde_json::json!({"code":-1,"msg":e}).to_string(),
                },
                "category" => match spider.category_content(
                    extend.get("tid").map(String::as_str).unwrap_or(""),
                    extend.get("pg").map(String::as_str).unwrap_or("1"),
                    false,
                    &extend,
                ) {
                    Ok(s) => s,
                    Err(e) => serde_json::json!({"code":-1,"msg":e}).to_string(),
                },
                "detail" => {
                    let ids: Vec<String> =
                        extend.get("ids").map(|s| s.split(',').map(String::from).collect()).unwrap_or_default();
                    let raw = match spider.detail_content(&ids) {
                        Ok(s) => s,
                        Err(e) => return serde_json::json!({"code":-1,"msg":e}).to_string(),
                    };
                    // 下沉多线路解析：vod_play_url → 结构化 vod_lines（壳直接透传，缺失时壳回退本地解析）
                    let parsed = json::parse(&raw);
                    if parsed.is_object() {
                        let mut v = parsed;
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
                "search" => match spider
                    .search_content(extend.get("key").map(String::as_str).unwrap_or(""), false, "1")
                {
                    Ok(s) => s,
                    Err(e) => serde_json::json!({"code":-1,"msg":e}).to_string(),
                },
                "player" => {
                    let raw = match spider.player_content(
                        extend.get("flag").map(String::as_str).unwrap_or(""),
                        extend.get("id").map(String::as_str).unwrap_or(""),
                        &[],
                    ) {
                        Ok(s) => s,
                        Err(e) => return serde_json::json!({"code":-1,"msg":e}).to_string(),
                    };
                    // 多清晰度（对齐原版 Url.values）：播放地址含 TVBox `#` 分隔多清晰度、
                    // `name$url` 命名时拆成 urls 数组，壳据此展示清晰度切换；否则原样透传。
                    let parsed = json::parse(&raw);
                    // 先提取为 owned String 再 move parsed，避免 E0505 借用冲突
                    let url_owned = parsed
                        .get("url")
                        .and_then(|x| x.as_str())
                        .unwrap_or("")
                        .to_string();
                    if !url_owned.is_empty()
                        && url_owned.split('#').filter(|s| !s.is_empty()).count() > 1
                    {
                        let mut v = parsed;
                        if let Some(obj) = v.as_object_mut() {
                            let parts: Vec<&str> = url_owned.split('#').filter(|s| !s.is_empty()).collect();
                            let urls: Vec<serde_json::Value> = parts.iter().enumerate().map(|(i, p)| {
                                if let Some(idx) = p.find('$') {
                                    let (n, vv) = p.split_at(idx);
                                    serde_json::json!({"name": n, "v": vv.trim_start_matches('$')})
                                } else {
                                    serde_json::json!({"name": format!("清晰度{}", i + 1), "v": p})
                                }
                            }).collect();
                            obj.insert("url".to_string(), serde_json::json!(parts[0]));
                            obj.insert("urls".to_string(), serde_json::json!(urls));
                        }
                        v.to_string()
                    } else {
                        raw
                    }
                }
                // 直播：params {url, tid} —— url 非空为独立直播源（diyp/txt/m3u，对齐原版
                // LiveConfig）；url 空则回退站点规则直播（spider.live_content）
                "live" => {
                    let pv: serde_json::Value = json::parse(&params);
                    let url = pv
                        .get("url")
                        .and_then(|x| x.as_str())
                        .unwrap_or("")
                        .to_string();
                    if url.is_empty() {
                        match spider.live_content() {
                            Ok(s) => s,
                            Err(e) => serde_json::json!({"code":-1,"msg":e}).to_string(),
                        }
                    } else {
                        let tid = pv
                            .get("tid")
                            .and_then(|x| x.as_str())
                            .unwrap_or("")
                            .to_string();
                        crate::live_parse::live_source(&url, &tid)
                    }
                }
                "proxy" => match spider.proxy_local(&extend) {
                    Some(r) => {
                        let mut o = serde_json::Map::new();
                        o.insert("code".to_string(), serde_json::json!(r.status));
                        o.insert("type".to_string(), serde_json::json!(r.content_type));
                        o.insert("b64".to_string(), serde_json::json!(base64_encode(&r.body)));
                        o.insert("headers".to_string(), serde_json::json!(r.headers));
                        serde_json::Value::Object(o).to_string()
                    }
                    None => String::new(),
                },
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

/// 纯 Rust base64 编码（避免为单个图片代理引入额外 crate，编译更稳）。
fn base64_encode(data: &[u8]) -> String {
    const TABLE: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(((data.len() + 2) / 3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = chunk.get(1).map(|&b| b as u32).unwrap_or(0);
        let b2 = chunk.get(2).map(|&b| b as u32).unwrap_or(0);
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[((n >> 18) & 63) as usize] as char);
        out.push(TABLE[((n >> 12) & 63) as usize] as char);
        out.push(if chunk.len() > 1 {
            TABLE[((n >> 6) & 63) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            TABLE[(n & 63) as usize] as char
        } else {
            '='
        });
    }
    out
}
