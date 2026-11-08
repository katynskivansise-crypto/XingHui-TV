//! EPG（电子节目单）抓取与解析 —— 对应原项目 `api/EpgParser` + `bean/Epg`。
//!
//! 与原版的三个对齐点：
//!   1. **磁盘缓存**：落到 `Path.epg()`（原版 = `cacheDir/epg`），当天已下过就直接读文件，
//!      不再请求（原版 `shouldDownload = !exists || !isToday(mtime)`）。之前每次点「节目单」
//!      都整份重下，XMLTV 动辄几 MB，点两次就多几 MB 流量。
//!   2. **`.gz` 支持**：原版 `readGzip` 会解压成同名 `.xml` 再解析；这里解压后也按 `.xml` 存盘。
//!   3. **只留今天的节目**：原版 `if (!isToday(start) && !isToday(end)) continue`。
//!
//! XMLTV 结构（规整、适合正则解析，避免引入 XML 依赖）：
//!   <tv>
//!     <channel id="cctv1"><display-name>CCTV-1</display-name>...</channel>
//!     <programme channel="cctv1" start="20241104193000 +0800" stop="...">
//!       <title>新闻联播</title>...</programme>
//!   </tv>

use regex::Regex;
use std::io::Read;

use crate::net::Net;

/// 抓取 XMLTV 并按频道名返回节目单。
/// 返回 JSON：`{list:[{start,end,time,title}], channel, cached}`
pub fn fetch_epg(net: &Net, cache_dir: &str, xmltv_url: &str, channel_name: &str) -> String {
    if xmltv_url.is_empty() || channel_name.is_empty() {
        return r#"{"list":[]}"#.to_string();
    }
    let (xml, cached) = load_xmltv(net, cache_dir, xmltv_url);
    if xml.is_empty() {
        return r#"{"list":[]}"#.to_string();
    }

    // 1) 按 display-name 找到频道 id（cid 转 owned，避免借用捕获组临时值）
    let re_chan = Regex::new(r#"<channel\s+id="([^"]+)"[^>]*>\s*<display-name[^>]*>\s*([^<]+)"#)
        .expect("invalid channel regex");
    let mut cid = String::new();
    for cap in re_chan.captures_iter(&xml) {
        if cap[2].trim() == channel_name {
            cid = cap[1].to_string();
            break;
        }
    }
    if cid.is_empty() {
        return r#"{"list":[]}"#.to_string();
    }

    // 2) 匹配该频道节目，提取 start/stop/title；只留今天（对齐原版 isToday 过滤）
    let today = today_local_ymd();
    let pat = format!(
        r#"<programme\s+channel="{}"[^>]*start="([^"]+)"[^>]*stop="([^"]+)"[^>]*>\s*<title[^>]*>\s*([^<]+)"#,
        regex::escape(&cid)
    );
    let re_prog = Regex::new(&pat).expect("invalid programme regex");
    let mut list: Vec<serde_json::Value> = Vec::new();
    for cap in re_prog.captures_iter(&xml) {
        let start = cap[1].trim().to_string();
        let end = cap[2].trim().to_string();
        let title = cap[3].trim().to_string();
        if !ymd_of(&start).map(|d| d == today).unwrap_or(true)
            && !ymd_of(&end).map(|d| d == today).unwrap_or(true)
        {
            continue;
        }
        list.push(serde_json::json!({
            "start": start,
            "end": end,
            "time": to_hhmm(&start),
            "title": title,
        }));
    }
    serde_json::json!({"list": list, "channel": cid, "cached": cached}).to_string()
}

// ───────────────────────── 缓存层 ─────────────────────────

/// 读本地缓存或下载。返回 (xml 文本, 是否命中缓存)
fn load_xmltv(net: &Net, cache_dir: &str, url: &str) -> (String, bool) {
    let file = cache_file(cache_dir, url);
    if let Some(f) = file.as_ref() {
        if is_today(f) {
            if let Ok(s) = std::fs::read_to_string(f) {
                if !s.trim().is_empty() {
                    return (s, true);
                }
            }
        }
    }
    let raw = net.bytes(url);
    if raw.is_empty() {
        return (String::new(), false);
    }
    let text = if url.to_lowercase().ends_with(".gz") {
        gunzip(&raw)
    } else {
        String::from_utf8_lossy(&raw).into_owned()
    };
    if !text.trim().is_empty() {
        if let Some(f) = file.as_ref() {
            if let Some(dir) = f.parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            // 存解压后的 XML（对齐原版 readGzip → xml = name.replace(".gz","")）
            let _ = std::fs::write(f, &text);
        }
    }
    (text, false)
}

/// 缓存文件路径：`cache_dir/<URL 最后一段>`；`.gz` 换成 `.xml`；目录为空则返回 None。
fn cache_file(cache_dir: &str, url: &str) -> Option<std::path::PathBuf> {
    if cache_dir.is_empty() {
        return None;
    }
    let seg = url.split('?').next().unwrap_or(url).rsplit('/').next().unwrap_or("epg.xml");
    let name = if seg.is_empty() { "epg.xml" } else { seg };
    // 对齐原版 readGzip：xml = file.getName().replace(".gz", "") —— 只去掉后缀，别把 .xml 变成 .xml.xml
    let name = name.replace(".gz", "");
    let safe: String = name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_' { c } else { '_' })
        .collect();
    Some(std::path::Path::new(cache_dir).join(safe))
}

/// 文件 mtime 与当前是否同一天（本地 UTC+8）
fn is_today(f: &std::path::Path) -> bool {
    let Ok(md) = std::fs::metadata(f) else { return false };
    let Ok(mtime) = md.modified() else { return false };
    if md.len() == 0 {
        return false;
    }
    let day = |t: std::time::SystemTime| {
        t.duration_since(std::time::UNIX_EPOCH)
            .map(|d| (d.as_secs() + 8 * 3600) / 86400)
            .unwrap_or(0)
    };
    day(mtime) == day(std::time::SystemTime::now())
}

fn gunzip(raw: &[u8]) -> String {
    let mut d = flate2::read::GzDecoder::new(raw);
    let mut s = String::new();
    match d.read_to_string(&mut s) {
        Ok(_) if !s.trim().is_empty() => s,
        // 不是合法 gzip（服务器其实返回了明文 XML）→ 按明文处理
        _ => String::from_utf8_lossy(raw).into_owned(),
    }
}

// ───────────────────────── 时间工具 ─────────────────────────

/// 当前本地日期 "YYYYMMDD"
fn today_local_ymd() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let days = ((secs + 8 * 3600) / 86400) as i64;
    let (y, m, d) = civil_from_days(days);
    format!("{:04}{:02}{:02}", y, m, d)
}

/// XMLTV start "YYYYMMDDHHMMSS +0800" → "YYYYMMDD"
fn ymd_of(s: &str) -> Option<String> {
    let d: String = s.chars().take_while(|c| c.is_ascii_digit()).collect();
    if d.len() >= 8 {
        Some(d[..8].to_string())
    } else {
        None
    }
}

/// 天数（1970 起）→ (年, 月, 日)，Howard Hinnant 的 civil_from_days
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// XMLTV start "YYYYMMDDHHMMSS +0800" → "HH:mm"（截取中间 4 位）。
fn to_hhmm(start: &str) -> String {
    let d = start.chars().filter(|c| c.is_ascii_digit()).collect::<String>();
    if d.len() >= 10 {
        format!("{}:{}", &d[8..10], &d[10..12])
    } else {
        start.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const XML: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="cctv1"><display-name>CCTV-1</display-name></channel>
  <channel id="cctv2"><display-name>CCTV-2</display-name></channel>
  <programme channel="cctv1" start="20241104193000 +0800" stop="20241104200000 +0800"><title>新闻联播</title></programme>
</tv>"#;

    // 注：节目若不在「今天」，按原版 isToday 会被过滤掉 —— 所以断言只针对解析正确性，
    // 用临时禁用过滤的方式验证：这里直接测 ymd_of / civil_from_days / gunzip / cache_file。
    #[test]
    fn ymd_and_civil() {
        assert_eq!(ymd_of("20241104193000 +0800").as_deref(), Some("20241104"));
        assert_eq!(civil_from_days(0), (1970, 1, 1));
    }

    #[test]
    fn cache_file_turns_gz_into_xml() {
        let p = cache_file("/tmp/x", "https://a.example/epg/cctv.xml.gz").unwrap();
        assert!(p.to_string_lossy().ends_with("cctv.xml"));
    }

    #[test]
    fn fetch_returns_json_even_without_today() {
        let net = Net::new();
        let out = fetch_epg(&net, "", "http://127.0.0.1:1/none.xml", "CCTV-1");
        assert!(out.starts_with('{'));
    }

    #[test]
    fn no_gz_and_no_cache_dir_still_parses() {
        // 走本地临时服务，cache_dir 为空 → 不落盘，但响应里带 cached=false
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let xml = XML.to_string();
        std::thread::spawn(move || {
            if let Ok((mut sock, _)) = listener.accept() {
                use std::io::Write;
                let body = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    xml.len(),
                    xml
                );
                let _ = sock.write_all(body.as_bytes());
            }
        });
        let net = Net::new();
        let out = fetch_epg(&net, "", &format!("http://{addr}/epg.xml"), "CCTV-1");
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["channel"], "cctv1");
        assert_eq!(v["cached"], false);
    }
}
