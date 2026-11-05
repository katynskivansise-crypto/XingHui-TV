//! 网络层 —— 对应原 `catvod/src/main/java/com/github/catvod/net/OkHttp.java`
//!
//! 原实现基于 OkHttp：30s 超时、100MB 缓存、自定义 header 注入（RequestInterceptor /
//! ResponseInterceptor）、DOH（DnsOverHttps）、代理（ProxySelector）、绕过证书校验
//! （SSLCompat）。
//!
//! Rust 侧用 ureq 对齐核心语义；Android 集成时可通过 JNI 把本结构实例化后注入。

use std::collections::HashMap;
use std::io::Read;
use std::sync::Arc;
use std::time::Duration;

/// 默认读写超时（原 TIMEOUT = 30_000ms）
pub const TIMEOUT: Duration = Duration::from_secs(30);

/// 网络客户端封装，对应原 `OkHttpClient` + `OkProxySelector`。
///
/// 字段全部用 Arc 包裹以保证 Send + Sync，可被 JNI 桥安全持有。
#[derive(Clone, Default)]
pub struct Net {
    /// 单请求注入的自定义 header（原 RequestInterceptor 逻辑）
    pub headers: Arc<HashMap<String, String>>,
    /// 是否启用代理；启用后走 `proxy_url`
    pub proxy: bool,
    /// 代理地址，scheme://user:pass@host:port（对应原 OkProxySelector）
    pub proxy_url: Arc<Option<String>>,
    /// 是否绕过 TLS 证书校验（对应原 SSLCompat 的 hostnameVerifier -> true）
    pub insecure: bool,
    /// 超时，默认 30s
    pub timeout: Duration,
}

impl Net {
    /// 构造默认配置的客户端。
    pub fn new() -> Self {
        Net {
            headers: Arc::new(HashMap::new()),
            proxy: false,
            proxy_url: Arc::new(None),
            insecure: true, // 对齐原实现：默认绕过证书校验以兼容大量直播源
            timeout: TIMEOUT,
        }
    }

    /// 设置全局 UA（对齐原 RequestInterceptor 注入 User-Agent）。空值忽略。
    pub fn set_ua(&mut self, ua: &str) {
        if ua.is_empty() {
            return;
        }
        let mut m = (*self.headers).clone();
        m.insert("User-Agent".to_string(), ua.to_string());
        self.headers = Arc::new(m);
    }

    /// 设置代理（对齐原 OkProxySelector）。空值关闭代理。
    /// 格式：scheme://user:pass@host:port，如 http://user:pass@127.0.0.1:7890
    pub fn set_proxy(&mut self, proxy_url: &str) {
        if proxy_url.is_empty() {
            self.proxy = false;
            self.proxy_url = Arc::new(None);
        } else {
            self.proxy = true;
            self.proxy_url = Arc::new(Some(proxy_url.to_string()));
        }
    }

    /// 合并站点级 header（对齐原项目 getHeaders：站点 header 优先，覆盖全局 UA 等）。
    /// 空 map 忽略。
    pub fn set_headers(&mut self, headers: &HashMap<String, String>) {
        if headers.is_empty() {
            return;
        }
        let mut m = (*self.headers).clone();
        for (k, v) in headers {
            m.insert(k.clone(), v.clone());
        }
        self.headers = Arc::new(m);
    }

    /// GET 请求并返回字符串 body（原 `OkHttp.string(url)`）。失败返回空串。
    pub fn string(&self, url: &str) -> String {
        String::from_utf8_lossy(&self.fetch("GET", url, None, None)).into_owned()
    }

    /// GET + 自定义 header（原 `OkHttp.string(url, headers)`）。
    pub fn string_with_headers(&self, url: &str, headers: &HashMap<String, String>) -> String {
        let mut merged = (*self.headers).clone();
        for (k, v) in headers {
            merged.insert(k.clone(), v.clone());
        }
        String::from_utf8_lossy(&self.fetch("GET", url, Some(&merged), None)).into_owned()
    }

    /// GET 返回字节 body（图片/资源代理用）。
    pub fn bytes(&self, url: &str) -> Vec<u8> {
        self.fetch("GET", url, None, None)
    }

    /// GET 返回原始字节按 Latin-1 映射成 String（每个字节 → U+00xx 字符）。
    /// 用于 drpy 规则按"编码"字段解码：Rust 侧不先做 UTF-8 lossy，保留原始字节，
    /// JS 侧 `__decodeBytes` 再按 gb2312/gbk/utf-8 等还原文本（对齐原 drpy 编码处理）。
    pub fn string_latin1(&self, url: &str) -> String {
        self.bytes(url).iter().map(|&b| b as char).collect()
    }

    /// GET + 自定义 header 返回字节 body。
    pub fn bytes_with_headers(&self, url: &str, headers: &HashMap<String, String>) -> Vec<u8> {
        let mut merged = (*self.headers).clone();
        for (k, v) in headers {
            merged.insert(k.clone(), v.clone());
        }
        self.fetch("GET", url, Some(&merged), None)
    }

    /// 完整请求（对齐原 quickjs `req(url, options)`）：支持 method / headers / body。
    /// options 为 JSON 字符串：{"method":"POST","headers":{...},"body":"..."}。
    /// 返回 Latin-1 字节串（JS 侧按规则"编码"字段解码）。
    pub fn request_latin1(&self, url: &str, options_json: &str) -> String {
        let (method, body, extra) = Self::parse_options(options_json);
        let mut merged = (*self.headers).clone();
        for (k, v) in extra {
            merged.insert(k, v);
        }
        let bytes = self.fetch(&method, url, Some(&merged), body.as_deref());
        bytes.iter().map(|&b| b as char).collect()
    }

    /// 完整请求 + 真实响应头 + 真实状态码（对齐原 `Connect.success` 的 `{code, headers, content}`）。
    /// 返回 (Latin-1 body, headers JSON 字符串, HTTP 状态码)，供 JS 侧组装 res.code / res.headers。
    pub fn request_latin1_full(&self, url: &str, options_json: &str) -> (String, String, u16) {
        let (method, body, extra) = Self::parse_options(options_json);
        let mut merged = (*self.headers).clone();
        for (k, v) in extra {
            merged.insert(k, v);
        }
        let (bytes, hdrs, code) = self.fetch_full(&method, url, Some(&merged), body.as_deref());
        let latin1: String = bytes.iter().map(|&b| b as char).collect();
        let hjson = serde_json::to_string(&hdrs).unwrap_or_else(|_| "{}".to_string());
        (latin1, hjson, code)
    }

    /// 解析 options JSON：method / body / headers（两个请求入口共用）
    fn parse_options(options_json: &str) -> (String, Option<Vec<u8>>, HashMap<String, String>) {
        let mut method = "GET".to_string();
        let mut body: Option<Vec<u8>> = None;
        let mut extra: HashMap<String, String> = HashMap::new();
        let v = crate::json::parse(options_json);
        if let Some(m) = v.get("method").and_then(|x| x.as_str()) {
            if !m.trim().is_empty() {
                method = m.trim().to_uppercase();
            }
        }
        if let Some(b) = v.get("body").and_then(|x| x.as_str()) {
            if !b.is_empty() {
                body = Some(b.as_bytes().to_vec());
            }
        }
        if let Some(h) = v.get("headers").and_then(|x| x.as_object()) {
            for (k, val) in h {
                if let Some(s) = val.as_str() {
                    extra.insert(k.clone(), s.to_string());
                }
            }
        }
        (method, body, extra)
    }

    fn fetch(&self, method: &str, url: &str, headers: Option<&HashMap<String, String>>, body: Option<&[u8]>) -> Vec<u8> {
        self.fetch_full(method, url, headers, body).0
    }

    /// 与 fetch 相同，但额外返回真实响应头与状态码
    /// （drpy 规则 `obj.withHeaders` 读 res.headers，`res.code` 读状态码，对齐原 Connect.success）
    fn fetch_full(&self, method: &str, url: &str, headers: Option<&HashMap<String, String>>, body: Option<&[u8]>) -> (Vec<u8>, HashMap<String, String>, u16) {
        let mut builder = ureq::AgentBuilder::new()
            .timeout(self.timeout)
            .timeout_connect(self.timeout);

        if self.proxy {
            if let Some(p) = self.proxy_url.as_ref() {
                if let Ok(proxy) = ureq::Proxy::new(p.clone()) {
                    builder = builder.proxy(proxy);
                }
            }
        }

        // ureq 2.x 的 TLS 配置通过 tls_config 注入；证书校验绕过需 rustls custom config。
        // 这里保留接口占位，Android 集成版再接入自定义 TLS 配置。
        let agent = builder.build();

        let mut req = agent.request(method, url);
        // 全局 header 注入（对齐原 RequestInterceptor）：所有请求默认携带 self.headers
        for (k, v) in (*self.headers).iter() {
            req = req.set(k, v);
        }
        if let Some(hdrs) = headers {
            for (k, v) in hdrs {
                req = req.set(k, v);
            }
        }

        let read_all = |resp: ureq::Response| -> (Vec<u8>, HashMap<String, String>, u16) {
            let code = resp.status();
            let mut out: HashMap<String, String> = HashMap::new();
            for name in resp.headers_names() {
                if let Some(v) = resp.header(&name) {
                    out.insert(name, v.to_string());
                }
            }
            let mut buf = Vec::new();
            let _ = resp.into_reader().read_to_end(&mut buf);
            (buf, out, code)
        };

        // 有 body 走 send_bytes（POST 等），否则走 call（GET）
        let result = match body {
            Some(b) => req.send_bytes(b),
            None => req.call(),
        };

        match result {
            Ok(resp) => read_all(resp),
            // 对齐原 OkHttp：非 2xx（如 403/404）也返回 body + 真实状态码，而非视为失败
            Err(ureq::Error::Status(code, resp)) => {
                let (buf, hdrs, _) = read_all(resp);
                (buf, hdrs, code)
            }
            Err(_e) => {
                #[cfg(debug_assertions)]
                eprintln!("[net] request failed for {url}: {_e}");
                (Vec::new(), HashMap::new(), 0)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_has_sane_defaults() {
        let n = Net::new();
        assert!(n.insecure);
        assert_eq!(n.timeout, TIMEOUT);
        assert!(!n.proxy);
        assert!(n.headers.is_empty());
    }
}
