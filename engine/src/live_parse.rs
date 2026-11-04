//! LiveParse —— 直播源解析器（对齐原项目 LiveParser / LiveConfig，Rust 下沉）。
//!
//! 直播源为独立配置（与站点无关），支持三种格式：
//!   - diyp JSON：`[{"group":"央视","channels":[{"name":"CCTV1","url":"http://..."}]}]`
//!     或 `{"lives":[...]}`；channel 的 url 支持 `#` 多源（取第一个）
//!   - txt：`分组,#genre#` 行开始新分组，其余 `名称,url` 加入当前分组
//!   - m3u：`#EXTINF:...group-title="分组",名称` + 下一行 url
//!
//! `live_source(url, tid)`：
//!   - tid 为空 → 返回 `{"class":[{"type_id":"分组","type_name":"分组"}]}`
//!   - tid 非空 → 返回 `{"list":[{"vod_id":"url","vod_name":"名称","vod_play_url":"url"}]}`
//! 壳侧直播页直接透传展示（与 ApiSpider/DrpySpider 的返回结构一致）。

use crate::net::Net;

/// 入口：抓取直播源 → 按 tid 返回分组或频道 JSON。
pub fn live_source(url: &str, tid: &str) -> String {
    if url.is_empty() {
        return r#"{"class":[],"list":[]}"#.to_string();
    }
    let net = Net::new();
    let text = net.string(url);
    if text.is_empty() {
        return r#"{"class":[],"list":[]}"#.to_string();
    }
    let groups = parse_groups(&text);
    if tid.is_empty() {
        let class: Vec<serde_json::Value> = groups
            .iter()
            .map(|(g, _)| serde_json::json!({"type_id": g, "type_name": g}))
            .collect();
        serde_json::json!({"class": class}).to_string()
    } else {
        let list: Vec<serde_json::Value> = groups
            .iter()
            .find(|(g, _)| g == tid)
            .map(|(_, chs)| {
                chs.iter()
                    .map(|(n, u)| {
                        serde_json::json!({"vod_id": u, "vod_name": n, "vod_play_url": u})
                    })
                    .collect()
            })
            .unwrap_or_default();
        serde_json::json!({"list": list}).to_string()
    }
}

/// 按内容形态分派解析：JSON（diyp）→ txt → m3u。
fn parse_groups(text: &str) -> Vec<(String, Vec<(String, String)>)> {
    let t = text.replace("\r\n", "\n");
    let head = t.trim_start();
    if head.starts_with('[') || head.starts_with('{') {
        parse_json(&t)
    } else if t.contains("#EXTM3U") {
        parse_m3u(&t)
    } else {
        parse_txt(&t)
    }
}

/// diyp JSON：顶层数组 [{group, channels:[{name,url}]}] 或 {lives:[...]}。
fn parse_json(text: &str) -> Vec<(String, Vec<(String, String)>)> {
    let v: serde_json::Value = serde_json::from_str(text).unwrap_or_default();
    let arr = if v.is_array() {
        v.clone()
    } else {
        v.get("lives").cloned().unwrap_or_default()
    };
    let mut groups = Vec::new();
    if let Some(list) = arr.as_array() {
        for g in list {
            let name = g.get("group").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let chs: Vec<(String, String)> = g
                .get("channels")
                .and_then(|c| c.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|c| {
                            let n = c.get("name").and_then(|x| x.as_str()).unwrap_or("").to_string();
                            let u = c.get("url").and_then(|x| x.as_str()).unwrap_or("").to_string();
                            if u.is_empty() {
                                None
                            } else {
                                Some((n, u.split('#').next().unwrap_or("").trim().to_string()))
                            }
                        })
                        .collect()
                })
                .unwrap_or_default();
            if !chs.is_empty() {
                groups.push((name, chs));
            }
        }
    }
    groups
}

/// txt：`分组,#genre#` 新分组；其余 `名称,url1#url2` 加入当前分组（取首个 url）。
fn parse_txt(text: &str) -> Vec<(String, Vec<(String, String)>)> {
    let mut groups: Vec<(String, Vec<(String, String)>)> = Vec::new();
    let mut cur: Option<usize> = None;
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        }
        if line.contains("#genre#") {
            let name = line.split(',').next().unwrap_or("").trim().to_string();
            groups.push((name, Vec::new()));
            cur = Some(groups.len() - 1);
        } else if let Some(idx) = cur {
            if let Some(comma) = line.find(',') {
                let name = line[..comma].trim().to_string();
                let rest = &line[comma + 1..];
                let url = rest.split('#').next().unwrap_or("").trim().to_string();
                if url.contains("://") {
                    groups[idx].1.push((name, url));
                }
            }
        }
    }
    groups
}

/// m3u：`#EXTINF:... group-title="分组", 名称` + 下一行 url（| 后为额外 header，取首个 url）。
fn parse_m3u(text: &str) -> Vec<(String, Vec<(String, String)>)> {
    let mut groups: Vec<(String, Vec<(String, String)>)> = Vec::new();
    let mut pending_group = String::new();
    let mut pending_name = String::new();
    for raw in text.lines() {
        let line = raw.trim();
        if line.starts_with("#EXTINF:") {
            pending_group = extract_attr(line, "group-title").unwrap_or_default();
            pending_name = line.rsplit(',').next().unwrap_or("").trim().to_string();
        } else if !line.starts_with('#') && line.contains("://") && !pending_name.is_empty() {
            let url = line.split('|').next().unwrap_or("").trim().to_string();
            let g = if pending_group.is_empty() {
                "默认分组".to_string()
            } else {
                pending_group.clone()
            };
            match groups.iter_mut().find(|(n, _)| n == &g) {
                Some((_, chs)) => chs.push((pending_name.clone(), url)),
                None => groups.push((g, vec![(pending_name.clone(), url)])),
            }
            pending_name.clear();
        }
    }
    groups
}

/// 提取 `attr="value"` 属性值。
fn extract_attr(line: &str, attr: &str) -> Option<String> {
    let key = format!("{attr}=\"");
    let s = line.find(&key)?;
    let rest = &line[s + key.len()..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}
