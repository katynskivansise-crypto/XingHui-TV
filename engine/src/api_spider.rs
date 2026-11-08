//! ApiSpider —— TVBox 标准 api 接口源（type 0/1/2，对齐原项目 SiteViewModel.call 语义）。
//!
//! 站点配置 JSON 含 `api` 字段（HTTP 接口，如 `https://xxx/api.php/provide/vod/`）：
//!   - home     GET api                        → {class, list}（首页推荐+分类）
//!   - category GET api?ac=detail&t=&pg=       （type=0 → ac=videolist）
//!   - detail   GET api?ac=detail&ids=
//!   - search   GET api?wd=&quick=&pg=         （无 ac，对齐原版 searchContent）
//!   - player   不请求 api，id 即播放地址      → {url, flag, parse}
//!
//! ext 处理（对齐原版 fetchExt）：ext 以 http 开头则先抓取内容作为 extend；
//! extend 非空时附带 `extend=` 参数，limit 时 >1000 截断。
//! 站点 header（原版 site.getHeaders()）合并进请求头；筛选(f=json)保留扩展点。

use std::collections::HashMap;

use crate::net::Net;
use crate::proxy::image_proxy;
use crate::spider::{ProxyResp, Spider};

pub struct ApiSpider {
    net: Net,
    key: String,
    _name: String,
    api: String,
    ext: String,
    r#type: i64,
    _play_url: String,
    header: HashMap<String, String>, // 站点 header（对齐原版 Site.getHeaders）
}

impl ApiSpider {
    /// 构造：站点 JSON + 全局 UA（对齐原项目 Site.getHeaders 语义——
    /// 站点 header 字段优先，全局 UA 兜底，空值不覆盖）。
    /// 代理已下线：路由器侧统一接管流量，壳不再传代理参数。
    pub fn new(json: &str, ua: &str) -> Result<Self, String> {
        let v: serde_json::Value =
            serde_json::from_str(json).map_err(|e| format!("站点 JSON 解析失败: {e}"))?;
        let key = v.get("key").and_then(|k| k.as_str()).unwrap_or("").to_string();
        let name = v.get("name").and_then(|k| k.as_str()).unwrap_or("").to_string();
        let api = v.get("api").and_then(|k| k.as_str()).unwrap_or("").to_string();
        if api.is_empty() {
            return Err("站点缺少 api 字段".to_string());
        }
        let ext = v.get("ext").and_then(|k| k.as_str()).unwrap_or("").to_string();
        let r#type = v.get("type").and_then(|k| k.as_i64()).unwrap_or(1);
        let play_url = v.get("playUrl").and_then(|k| k.as_str()).unwrap_or("").to_string();
        // 站点 header：JSON 对象 {User-Agent:..., Referer:...}（站点优先）
        let header = v
            .get("header")
            .and_then(|h| h.as_object())
            .map(|o| {
                o.iter()
                    .map(|(k, val)| (k.clone(), val.as_str().unwrap_or("").to_string()))
                    .collect()
            })
            .unwrap_or_default();
        // 网络层：注入全局 UA（站点 header 在 get() 合并时优先）
        let mut net = Net::new();
        if !ua.is_empty() {
            net.set_ua(ua);
        }
        Ok(Self { net, key, _name: name, api, ext, r#type, _play_url: play_url, header })
    }

    /// GET（合并全局 headers + 站点 header，站点 header 优先覆盖）。
    fn get(&self, url: &str) -> String {
        if self.header.is_empty() {
            self.net.string(url)
        } else {
            self.net.string_with_headers(url, &self.header)
        }
    }

    /// 对齐原项目 fetchExt：ext 以 http 开头先抓取内容（带站点 header）；limit 时 >1000 截断。
    fn fetch_ext(&self, limit: bool) -> String {
        let mut extend = self.ext.clone();
        if extend.starts_with("http") {
            extend = self.get(&extend);
        }
        if limit && extend.len() > 1000 {
            extend = extend.chars().take(1000).collect();
        }
        extend
    }

    /// 拼 GET URL：api + 查询参数 + extend。
    fn build_url(&self, query: &[(String, String)], limit: bool) -> String {
        let mut url = self.api.clone();
        let mut sep = if url.contains('?') { '&' } else { '?' };
        for (k, v) in query {
            url.push(sep);
            url.push_str(k);
            url.push('=');
            url.push_str(&enc(v));
            sep = '&';
        }
        let extend = self.fetch_ext(limit);
        if !extend.is_empty() {
            url.push(sep);
            url.push_str("extend=");
            url.push_str(&enc(&extend));
        }
        url
    }

    /// ac 参数：type=0（内置采集）用 videolist，其余用 detail（对齐原版）。
    fn ac(&self) -> &'static str {
        if self.r#type == 0 { "videolist" } else { "detail" }
    }
}

/// 简单 query 编码（对齐 OkHttp params 语义，覆盖 & ? #）。
fn enc(s: &str) -> String {
    s.replace('&', "%26").replace('?', "%3F").replace('#', "%23")
}

impl Spider for ApiSpider {
    fn home_content(&self, _filter: bool) -> Result<String, String> {
        // 对齐原项目 homeContent：直接 GET api（无参数）返回 {class, list}
        Ok(self.get(&self.api))
    }

    fn category_content(
        &self,
        tid: &str,
        pg: &str,
        _filter: bool,
        extend: &HashMap<String, String>,
    ) -> Result<String, String> {
        let mut q: Vec<(String, String)> = vec![
            ("ac".to_string(), self.ac().to_string()),
            ("t".to_string(), tid.to_string()),
            ("pg".to_string(), pg.to_string()),
        ];
        // 对齐原版：type=1 且带筛选参数 → f=json（星汇壳暂未传筛选，保留扩展点）
        if self.r#type == 1 && !extend.is_empty() {
            let f = serde_json::json!(extend).to_string();
            q.push(("f".to_string(), f));
        }
        Ok(self.get(&self.build_url(&q, true)))
    }

    fn detail_content(&self, ids: &[String]) -> Result<String, String> {
        if ids.is_empty() {
            return Ok(r#"{"list":[]}"#.to_string());
        }
        let q = vec![
            ("ac".to_string(), self.ac().to_string()),
            ("ids".to_string(), ids.join(",")),
        ];
        Ok(self.get(&self.build_url(&q, true)))
    }

    fn search_content(&self, key: &str, quick: bool, pg: &str) -> Result<String, String> {
        // 对齐原版 searchContent：wd/quick/pg，无 ac
        let q = vec![
            ("wd".to_string(), key.to_string()),
            ("quick".to_string(), quick.to_string()),
            ("pg".to_string(), pg.to_string()),
        ];
        Ok(self.get(&self.build_url(&q, true)))
    }

    fn player_content(&self, flag: &str, id: &str, _vip_flags: &[String]) -> Result<String, String> {
        // 对齐原项目 executePlayer：id 即播放地址，不请求 api；
        // 星汇壳直接以 url 播放（parse=0，不做嗅探）
        Ok(serde_json::json!({"url": id, "flag": flag, "parse": 0}).to_string())
    }

    fn proxy_local(&self, params: &HashMap<String, String>) -> Option<ProxyResp> {
        image_proxy(&self.net, params)
    }
}
