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
    Some(ProxyResp { content_type: detect_content_type(&body), body })
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
