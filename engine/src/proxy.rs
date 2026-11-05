//! 本地代理配置 —— 对应原 `catvod/src/main/java/com/github/catvod/Proxy.java`
//!
//! 原实现：默认端口 9978，提供本机/局域网可访问的 /proxy 地址。
//! 重构版保留常量与占位 API；实际代理服务（对应 app/server）由安卓侧或独立
//! Rust 服务实现，这里提供图片/资源代理的字节能力（对应原 proxyLocal）。

use std::collections::HashMap;

use crate::net::Net;
use crate::spider::ProxyResp;

/// 默认代理端口（原 `port = 9978`）
pub const DEFAULT_PORT: u16 = 9978;

/// 代理服务（占位；后续可扩展为真实的服务句柄/生命周期管理）
#[derive(Clone, Copy, Debug)]
pub struct Proxy {
    pub port: u16,
}

impl Default for Proxy {
    fn default() -> Self {
        Proxy { port: DEFAULT_PORT }
    }
}

impl Proxy {
    pub fn set_port(port: u16) -> Proxy {
        Proxy { port }
    }

    /// 本机地址（原 `getUrl(true)` -> http://127.0.0.1:9978/proxy）
    pub fn local_url(&self) -> String {
        format!("http://127.0.0.1:{}/proxy", self.port)
    }
}

/// 图片/资源代理：按 params（`url`，可选 `referer`/`ua`）抓取字节，返回 ProxyResp。
/// 对应原 Spider.proxyLocal 的常见用途（图片防盗链代理）。
pub fn image_proxy(net: &Net, params: &HashMap<String, String>) -> Option<ProxyResp> {
    let url = params.get("url")?;
    let mut headers = HashMap::new();
    if let Some(r) = params.get("referer") {
        headers.insert("Referer".to_string(), r.clone());
    }
    if let Some(ua) = params.get("ua") {
        headers.insert("User-Agent".to_string(), ua.clone());
    }
    let body = if headers.is_empty() {
        net.bytes(url)
    } else {
        net.bytes_with_headers(url, &headers)
    };
    if body.is_empty() {
        return None;
    }
    Some(ProxyResp { status: 200, content_type: detect_content_type(&body), body, headers: HashMap::new() })
}

/// 解析 drpy `proxy(params)` 返回的数组（对齐原 `Spider.proxy1`）：
/// `[status, contentType, body, headers?, base64Flag?]`
/// - body 为 JSON 数组 → 每个元素是一个字节（原 getStream 的 JSONArray 分支）
/// - body 为字符串 → base64Flag==1 时按 base64 解码（先去 "base64," 前缀），否则取 UTF-8 字节
pub fn parse_proxy_array(json: &str) -> Option<ProxyResp> {
    let v: serde_json::Value = serde_json::from_str(json).ok()?;
    let arr = v.as_array()?;
    let status = arr.first().and_then(|x| x.as_i64()).unwrap_or(200) as u16;
    let content_type = arr.get(1).and_then(|x| x.as_str()).unwrap_or("text/plain").to_string();
    let body = match arr.get(2)? {
        serde_json::Value::Array(a) => a.iter().map(|x| x.as_i64().unwrap_or(0) as u8).collect(),
        serde_json::Value::String(text) => {
            let use_base64 = arr.get(4).and_then(|x| x.as_i64()).unwrap_or(0) == 1;
            if use_base64 {
                let payload = text.rsplit("base64,").next().unwrap_or(text);
                base64_decode(payload).unwrap_or_else(|| text.as_bytes().to_vec())
            } else {
                text.as_bytes().to_vec()
            }
        }
        serde_json::Value::Null => Vec::new(),
        _ => return None,
    };
    let headers = arr
        .get(3)
        .and_then(|x| x.as_object())
        .map(|o| {
            o.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect::<HashMap<String, String>>()
        })
        .unwrap_or_default();
    Some(ProxyResp { status, content_type, body, headers })
}

/// 纯 Rust base64 解码（proxy 规则 base64Flag=1 时使用）。
fn base64_decode(s: &str) -> Option<Vec<u8>> {
    let val = |c: u8| -> Option<u8> {
        match c {
            b'A'..=b'Z' => Some(c - b'A'),
            b'a'..=b'z' => Some(c - b'a' + 26),
            b'0'..=b'9' => Some(c - b'0' + 52),
            b'+' => Some(62),
            b'/' => Some(63),
            _ => None,
        }
    };
    let mut out = Vec::new();
    let mut buf = [0u8; 4];
    let mut n = 0usize;
    for c in s.bytes() {
        if c == b'=' {
            break;
        }
        if c == b'\n' || c == b'\r' || c == b' ' || c == b'\t' {
            continue;
        }
        match val(c) {
            Some(v) => {
                buf[n] = v;
                n += 1;
                if n == 4 {
                    out.push((buf[0] << 2) | (buf[1] >> 4));
                    out.push((buf[1] << 4) | (buf[2] >> 2));
                    out.push((buf[2] << 6) | buf[3]);
                    n = 0;
                }
            }
            None => return None,
        }
    }
    match n {
        2 => out.push((buf[0] << 2) | (buf[1] >> 4)),
        3 => {
            out.push((buf[0] << 2) | (buf[1] >> 4));
            out.push((buf[1] << 4) | (buf[2] >> 2));
        }
        _ => {}
    }
    Some(out)
}

/// 按 magic bytes 判断图片 MIME（缺省 octet-stream）。
pub fn detect_content_type(body: &[u8]) -> String {
    if body.len() >= 8 && &body[..8] == b"\x89PNG\r\n\x1a\n" {
        "image/png"
    } else if body.len() >= 3 && body[..3] == [0xff, 0xd8, 0xff] {
        "image/jpeg"
    } else if body.len() >= 6 && (&body[..6] == b"GIF87a" || &body[..6] == b"GIF89a") {
        "image/gif"
    } else if body.len() >= 12 && &body[8..12] == b"WEBP" {
        "image/webp"
    } else {
        "application/octet-stream"
    }
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_port_is_9978() {
        let p = Proxy::default();
        assert_eq!(p.port, 9978);
        assert_eq!(p.local_url(), "http://127.0.0.1:9978/proxy");
    }

    #[test]
    fn detect_mime_from_magic() {
        assert_eq!(detect_content_type(b"\x89PNG\r\n\x1a\nxxxxx"), "image/png");
        assert_eq!(detect_content_type(b"\xff\xd8\xff\xe0"), "image/jpeg");
        assert_eq!(detect_content_type(b"GIF89a...."), "image/gif");
        assert_eq!(detect_content_type(b"RIFF1234WEBPVP8 "), "image/webp");
        assert_eq!(detect_content_type(b"plain"), "application/octet-stream");
    }

    #[test]
    fn parse_proxy_array_text_body() {
        let r = parse_proxy_array(r##"[200,"application/vnd.apple.mpegurl","#EXTM3U\n#EXT-X-VERSION:3"]"##).unwrap();
        assert_eq!(r.status, 200);
        assert_eq!(r.content_type, "application/vnd.apple.mpegurl");
        assert_eq!(r.body, b"#EXTM3U\n#EXT-X-VERSION:3".to_vec());
    }

    #[test]
    fn parse_proxy_array_with_headers_and_base64() {
        let r = parse_proxy_array(r##"[200,"image/png","aGVsbG8=",{"Referer":"https://a.com"},1]"##).unwrap();
        assert_eq!(r.status, 200);
        assert_eq!(r.body, b"hello".to_vec());
        assert_eq!(r.headers.get("Referer").map(|s| s.as_str()), Some("https://a.com"));
    }

    #[test]
    fn parse_proxy_array_not_found() {
        let r = parse_proxy_array(r##"[404,"text/plain","Not Found"]"##).unwrap();
        assert_eq!(r.status, 404);
        assert_eq!(r.content_type, "text/plain");
    }

    #[test]
    fn image_proxy_fetches_bytes() {
        // 本地临时服务返回 PNG magic 字节
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        std::thread::spawn(move || {
            if let Ok((mut sock, _)) = listener.accept() {
                let resp = b"HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\n\x89PNG\r\n\x1a\n";
                let _ = sock.write_all(resp);
            }
        });
        use std::io::Write;
        let net = Net::new();
        let mut params = HashMap::new();
        params.insert("url".to_string(), format!("http://{addr}/img"));
        let r = image_proxy(&net, &params).expect("should fetch");
        assert_eq!(r.content_type, "image/png");
        assert_eq!(r.body, b"\x89PNG\r\n\x1a\n");
    }
}
