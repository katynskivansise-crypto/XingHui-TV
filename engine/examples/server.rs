//! Rust 引擎 → HTTP 数据接口
//!
//! 沙盒模拟 Tauri/安卓的调用链：前端通过 HTTP 拉取引擎数据（P4 核心体验联调用）。
//! 用标准库 TcpListener 写极简 JSON 服务，暴露 Spider 契约全接口：
//!   GET /home  GET /category?tid=..&pg=..  GET /detail?ids=..
//!   GET /search?key=..  GET /player?flag=..&id=..
//!
//! 运行：cargo run --example server   （监听 127.0.0.1:9898）
//! 测试：curl http://127.0.0.1:9898/home

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::Arc;
use std::sync::Mutex;

use tv_engine::drpy_engine::{DrpyEngine, ReqImpl};
use tv_engine::spider::{Spider, SpiderManager};

/// 用真实 drpy 规则（samples/rules/drpy.js + drpy2.min.js）的引擎。
/// 沙盒豆瓣 frodo 不可达，这里提供一个"含完整演示数据"的回退实现，
/// 保证 P4 前端各页面（首页/分类/详情/搜索/播放）都能拿到数据联调。
struct DemoSpider;

impl Spider for DemoSpider {
    fn home_content(&self, _filter: bool) -> Result<String, String> {
        Ok(format!(
            r#"{{"class":{CATS},"list":{CAT_LIST}}}"#,
            CATS = cats_json(),
            CAT_LIST = list_json("1", 8),
        ))
    }

    fn category_content(
        &self,
        tid: &str,
        pg: &str,
        _filter: bool,
        _extend: &HashMap<String, String>,
    ) -> Result<String, String> {
        Ok(format!(
            r#"{{"page":{pg},"pagecount":999,"limit":20,"total":40,"list":{list}}}"#,
            pg = pg,
            list = list_json(tid, 20),
        ))
    }

    fn detail_content(&self, ids: &[String]) -> Result<String, String> {
        let id = ids.first().cloned().unwrap_or_else(|| "1001".to_string());
        let name = format!("影片{}", id);
        // 多线路：vod_play_from 用 $$$ 分隔线路，vod_play_url 对应各线路（$$$ 分线路、$ 分集）
        let vod = format!(
            r#"{{
              "vod_id":"{id}","vod_name":"{name}","vod_year":"2026","vod_area":"演示","vod_remarks":"HD",
              "vod_actor":"演员A / 演员B","vod_director":"导演C","vod_content":"这是 P5 演示数据：详情页展示剧情、演员、多线路与选集，点击播放测试流。",
              "vod_play_from":"线路1$$$线路2",
              "vod_play_url":"第1集${EP1}$第2集${EP2}$第3集${EP3}$$$线路2·第1集${EP3}$线路2·第2集${EP1}$线路2·第3集${EP2}"
            }}"#,
            id = id,
            name = name,
            EP1 = EP[0],
            EP2 = EP[1],
            EP3 = EP[2],
        );
        Ok(format!(r#"{{"list":[{vod}]}}"#, vod = vod))
    }

    fn search_content(&self, key: &str, _quick: bool, _pg: &str) -> Result<String, String> {
        let n = if key.chars().count() < 2 { 2 } else { 4 };
        let list = list_json("s", n);
        Ok(format!(r#"{{"list":{list}}}"#, list = list))
    }

    fn player_content(&self, _flag: &str, id: &str, _vip_flags: &[String]) -> Result<String, String> {
        // 播放地址：按所选集数返回测试流（Web 端 HTML5 可播）
        let idx = id.trim().parse::<usize>().ok().unwrap_or(0) % 3;
        let url = EP[idx];
        Ok(format!(r#"{{"url":"{url}","parse":0}}"#, url = url))
    }

    fn live_content(&self) -> Result<String, String> {
        // fongmi 期望：{"list":[{group,channels:[{name,url}]}]}
        let groups = vec![
            ("央视", vec![("CCTV-1", EP[0]), ("CCTV-5", EP[1]), ("CCTV-13", EP[2])]),
            ("卫视", vec![("湖南卫视", EP[2]), ("东方卫视", EP[1])]),
            ("测试", vec![("测试频道1", EP[0]), ("测试频道2", EP[1])]),
        ];
        let arr: Vec<String> = groups
            .iter()
            .map(|(g, chs)| {
                let ch: Vec<String> = chs
                    .iter()
                    .map(|(n, u)| format!(r#"{{"name":"{n}","url":"{u}"}}"#, n = n, u = u))
                    .collect();
                format!(r#"{{"group":"{g}","channels":[{list}]}}"#, g = g, list = ch.join(","))
            })
            .collect();
        Ok(format!(r#"{{"list":[{all}]}}"#, all = arr.join(",")))
    }
}

/// 公共可访问测试流（浏览器可播）。
const EP: [&str; 3] = [
    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
];

const CAT_IDS: [&str; 6] = ["1", "2", "3", "4", "5", "6"];
const CAT_NAMES: [&str; 6] = ["电影", "电视剧", "综艺", "动漫", "纪录片", "直播"];

fn cats_json() -> String {
    let arr: Vec<String> = CAT_IDS
        .iter()
        .zip(CAT_NAMES.iter())
        .map(|(id, name)| format!(r#"{{"type_id":"{id}","type_name":"{name}"}}"#))
        .collect();
    format!("[{}]", arr.join(","))
}

fn list_json(tid: &str, n: usize) -> String {
    let items: Vec<String> = (0..n)
        .map(|i| {
            let idx = i + 1;
            let name = format!("{}{}", cat_name(tid), idx);
            format!(
                r#"{{"vod_id":"{tid}{idx:03}","vod_name":"{name}","vod_year":"2026","vod_area":"演示","vod_remarks":"更新至{idx}集","vod_pic":""}}"#
            )
        })
        .collect();
    format!("[{}]", items.join(","))
}

fn cat_name(tid: &str) -> &'static str {
    let i = CAT_IDS.iter().position(|c| *c == tid).unwrap_or(0);
    CAT_NAMES[i]
}

/// 真实 DrpyEngine 例：加载 samples/rules 的真实 drpy 规则（用于真实源验证）。
#[allow(dead_code)]
fn real_drpy_engine() -> Result<DrpyEngine, String> {
    let dir = "../samples/rules";
    let rule = std::fs::read_to_string(format!("{dir}/drpy.js")).map_err(|e| e.to_string())?;
    let engine = std::fs::read_to_string(format!("{dir}/drpy2.min.js")).map_err(|e| e.to_string())?;
    let net = Arc::new(tv_engine::net::Net::new());
    let req: ReqImpl = Arc::new(move |url: &str| net.string(url));
    DrpyEngine::new(dir, &rule, &engine, req)
}

fn main() {
    let mut mgr = SpiderManager::new();
    mgr.register("demo", Box::new(DemoSpider));

    // 若真实 drpy 素材在，注册为真实源（豆瓣，沙盒网络受限时前端走 demo）
    let has_real = std::path::Path::new("../samples/rules/drpy.js").exists()
        && std::path::Path::new("../samples/rules/drpy2.min.js").exists();
    if has_real {
        println!("[server] 检测到真实 drpy 素材，注册 'drpy' 真实源");
        if let Ok(dir) = std::fs::canonicalize("../samples/rules") {
            let dirs = dir.to_string_lossy().into_owned();
            let rule = std::fs::read_to_string(format!("{dirs}/drpy.js")).unwrap_or_default();
            let engine = std::fs::read_to_string(format!("{dirs}/drpy2.min.js")).unwrap_or_default();
            mgr.register("drpy", Box::new(tv_engine::drpy_spider::DrpySpider::new(dirs, rule, engine)));
        }
    }

    let listener = TcpListener::bind("127.0.0.1:9898").expect("bind 127.0.0.1:9898");
    println!("[server] Rust 引擎已就绪，监听 http://127.0.0.1:9898  (/home /category /detail /search /player)");

    for stream in listener.incoming() {
        let mut stream = match stream {
            Ok(s) => s,
            Err(_) => continue,
        };
        let mut buf = [0u8; 8192];
        let n = stream.read(&mut buf).unwrap_or(0);
        let req = String::from_utf8_lossy(&buf[..n]);
        let (path, query) = parse_request(&req);
        let key = query_val(&query, "key").unwrap_or_else(|| "demo".to_string());
        let params = query_val(&query, "params").unwrap_or_default();

        let body = route(&mgr, &path, &key, &params);
        let resp = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
            body.len(),
            body
        );
        let _ = stream.write_all(resp.as_bytes());
    }
}

fn route(mgr: &SpiderManager, path: &str, key: &str, params: &str) -> String {
    let ext = if params.is_empty() {
        HashMap::new()
    } else {
        serde_json::from_str::<serde_json::Value>(params)
            .ok()
            .and_then(|v| v.as_object().cloned())
            .map(|m| m.into_iter().map(|(k, v)| (k, v.as_str().unwrap_or("").to_string())).collect())
            .unwrap_or_default()
    };
    let Some(sp) = mgr.get(key) else { return r#"{"code":500,"msg":"no spider"}"#.to_string() };

    match path {
        "/home" => sp.home_content(false).unwrap_or_default(),
        "/category" => {
            let tid = ext.get("tid").map(String::as_str).unwrap_or("");
            let pg = ext.get("pg").map(String::as_str).unwrap_or("1");
            sp.category_content(tid, pg, false, &ext).unwrap_or_default()
        }
        "/detail" => {
            let ids: Vec<String> = ext
                .get("ids")
                .map(|s| s.split(',').map(String::from).collect())
                .unwrap_or_default();
            sp.detail_content(&ids).unwrap_or_default()
        }
        "/search" => {
            let k = ext.get("key").map(String::as_str).unwrap_or("");
            sp.search_content(k, false, "1").unwrap_or_default()
        }
        "/player" => {
            let flag = ext.get("flag").map(String::as_str).unwrap_or("");
            let id = ext.get("id").map(String::as_str).unwrap_or("");
            sp.player_content(flag, id, &[]).unwrap_or_default()
        }
        "/live" => sp.live_content().unwrap_or_default(),
        "/search_all" => {
            // 聚合搜索：遍历所有已注册源，合并各自结果并打来源标记
            let k = ext.get("key").map(String::as_str).unwrap_or("");
            let mut merged: Vec<serde_json::Value> = Vec::new();
            for sk in mgr.keys() {
                if let Some(s) = mgr.get(&sk) {
                    if let Ok(raw) = s.search_content(k, false, "1") {
                        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) {
                            if let Some(list) = v.get("list").and_then(|l| l.as_array()) {
                                for mut item in list.clone() {
                                    item["vod_source"] = serde_json::Value::String(sk.clone());
                                    merged.push(item);
                                }
                            }
                        }
                    }
                }
            }
            let dedup: Vec<serde_json::Value> = {
                let mut seen = std::collections::HashSet::new();
                merged.into_iter().filter(|it| {
                    let k = it["vod_id"].as_str().unwrap_or("").to_string() + it["vod_source"].as_str().unwrap_or("");
                    seen.insert(k)
                }).collect()
            };
            serde_json::json!({ "list": dedup }).to_string()
        }
        "/sources" => {
            // 列出可用源及其状态（订阅源管理增强）
            let defs = [
                ("demo", "演示源", "本地完整演示数据"),
                ("drpy", "真实豆瓣源", "samples/rules/drpy.js 真实 drpy 规则"),
            ];
            let arr: Vec<serde_json::Value> = defs
                .iter()
                .map(|(k, n, d)| {
                    let alive = mgr.get(*k).is_some();
                    serde_json::json!({ "key": k, "name": n, "desc": d, "alive": alive })
                })
                .collect();
            serde_json::json!({ "sources": arr }).to_string()
        }
        _ => r#"{"code":404,"msg":"not found"}"#.to_string(),
    }
}

fn parse_request(req: &str) -> (String, String) {
    let line = req.lines().next().unwrap_or("").to_string();
    let parts: Vec<&str> = line.split_whitespace().collect();
    let target = parts.get(1).copied().unwrap_or("/");
    let (path, query) = match target.find('?') {
        Some(i) => (target[..i].to_string(), target[i + 1..].to_string()),
        None => (target.to_string(), String::new()),
    };
    (path, query)
}

fn query_val(query: &str, key: &str) -> Option<String> {
    query
        .split('&')
        .filter_map(|kv| {
            let mut it = kv.splitn(2, '=');
            let k = it.next()?;
            let v = it.next()?;
            if k == key {
                Some(percent_decode(v))
            } else {
                None
            }
        })
        .next()
}

fn percent_decode(s: &str) -> String {
    // 极简 percent-decode（处理中文字符等）
    let bytes = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let Ok(b) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(b);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[allow(dead_code)]
struct _KeepMutex(Mutex<()>); // 占位避免未用导入警告
