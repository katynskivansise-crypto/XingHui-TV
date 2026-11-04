//! EPG（电子节目单）抓取与解析 —— 对应原项目 `bean/Epg` + EPG 数据源。
//!
//! 引擎提供 XMLTV 抓取 + 频道节目单解析能力（跨端复用；源提供 EPG 即生效）。
//! XMLTV 结构（规整、适合正则解析，避免引入 XML 依赖）：
//!   <tv>
//!     <channel id="cctv1"><display-name>CCTV-1</display-name>...</channel>
//!     <programme channel="cctv1" start="20241104193000 +0800" stop="...">
//!       <title>新闻联播</title>...</programme>
//!   </tv>

use regex::Regex;

use crate::net::Net;

/// 抓取 XMLTV 并按频道名返回节目单。返回 JSON：{list:[{start,end,time,title}], channel}
pub fn fetch_epg(net: &Net, xmltv_url: &str, channel_name: &str) -> String {
    if xmltv_url.is_empty() || channel_name.is_empty() {
        return r#"{"list":[]}"#.to_string();
    }
    let xml = net.string(xmltv_url);
    if xml.is_empty() {
        return r#"{"list":[]}"#.to_string();
    }

    // 1) 按 display-name 找到频道 id
    let re_chan = Regex::new(r#"<channel\s+id="([^"]+)"[^>]*>\s*<display-name[^>]*>\s*([^<]+)"#)
        .expect("invalid channel regex");
    let mut cid = "";
    for cap in re_chan.captures_iter(&xml) {
        if cap[2].trim() == channel_name {
            cid = &cap[1];
            break;
        }
    }
    if cid.is_empty() {
        return r#"{"list":[]}"#.to_string();
    }

    // 2) 匹配该频道节目，提取 start/stop/title
    let pat = format!(
        r#"<programme\s+channel="{}"[^>]*start="([^"]+)"[^>]*stop="([^"]+)"[^>]*>\s*<title[^>]*>\s*([^<]+)"#,
        regex::escape(cid)
    );
    let re_prog = Regex::new(&pat).expect("invalid programme regex");
    let mut list: Vec<serde_json::Value> = Vec::new();
    for cap in re_prog.captures_iter(&xml) {
        let start = cap[1].trim().to_string();
        let end = cap[2].trim().to_string();
        let title = cap[3].trim().to_string();
        list.push(serde_json::json!({
            "start": start,
            "end": end,
            "time": to_hhmm(&start),
            "title": title,
        }));
    }
    serde_json::json!({"list": list, "channel": cid}).to_string()
}

/// XMLTV start "YYYYMMDDHHMMSS +0800" → "HH:mm"（截取中间 4 位）。
fn to_hhmm(start: &str) -> String {
    let d = start.chars().filter(|c| c.is_ascii_digit()).collect::<String>();
    if d.len() >= 8 {
        format!("{}:{}", &d[6..8], &d[8..10])
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
  <programme channel="cctv1" start="20241104200000 +0800" stop="20241104213000 +0800"><title>天气预报</title></programme>
</tv>"#;

    #[test]
    fn matches_channel_and_returns_programme() {
        // 用临时本地服务模拟 XMLTV，走 Net
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
        let out = fetch_epg(&net, &format!("http://{addr}/epg"), "CCTV-1");
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["channel"], "cctv1");
        let list = v["list"].as_array().unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0]["title"], "新闻联播");
        assert_eq!(list[0]["time"], "19:30");
    }
}
