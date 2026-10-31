//! LiveSpider —— 通用直播源执行器（CatVod 生态 type=2 直播规则，无 JS 脚本）。
//!
//! 直播规则把频道以 JSON 静态写在配置里，两种常见结构：
//!   1. `ext` 为频道扁平数组：`[{"group":"央视","name":"CCTV1","url":"..."}]`
//!   2. `ext` 为分组嵌套：`[{"group":"央视","channels":[{"name":"CCTV1","url":"..."}]}]`
//!
//! `live_content()` 返回 fongmi App 期望的频道 JSON：
//! ```json
//! {
//!   "groups": [{ "group_name": "央视", "url": ["http://..."] }],
//!   "list":   [{ "group": "央视", "name": "CCTV1", "url": "http://..." }]
//! }
//! ```

use crate::spider::Spider;

pub struct LiveSpider {
    groups: Vec<(String, Vec<String>)>, // (group_name, [urls])
    list: Vec<(String, String, String)>, // (group, name, url)
}

impl LiveSpider {
    pub fn new(json: &str) -> Result<Self, String> {
        let v: serde_json::Value =
            serde_json::from_str(json).map_err(|e| format!("直播规则解析失败: {e}"))?;
        let mut groups: Vec<(String, Vec<String>)> = Vec::new();
        let mut list: Vec<(String, String, String)> = Vec::new();

        if let Some(ext) = v.get("ext").and_then(|e| e.as_array()) {
            for c in ext {
                let group = c.get("group").and_then(|g| g.as_str()).unwrap_or("默认").to_string();
                // 分组嵌套：channels[...]
                if let Some(chs) = c.get("channels").and_then(|x| x.as_array()) {
                    let mut urls = Vec::new();
                    for ch in chs {
                        let name = ch.get("name").and_then(|x| x.as_str()).unwrap_or("").to_string();
                        let url = ch.get("url").and_then(|x| x.as_str()).unwrap_or("").to_string();
                        urls.push(url.clone());
                        list.push((group.clone(), name, url));
                    }
                    if !urls.is_empty() {
                        groups.push((group, urls));
                    }
                } else {
                    // 扁平：name/url 直接在分类项上
                    let name = c.get("name").and_then(|x| x.as_str()).unwrap_or("").to_string();
                    let url = c.get("url").and_then(|x| x.as_str()).unwrap_or("").to_string();
                    if !name.is_empty() && !url.is_empty() {
                        list.push((group.clone(), name, url.clone()));
                    }
                }
            }
        }
        Ok(Self { groups, list })
    }
}

impl Spider for LiveSpider {
    fn live_content(&self) -> Result<String, String> {
        let groups: Vec<serde_json::Value> = self
            .groups
            .iter()
            .map(|(name, urls)| serde_json::json!({"group_name": name, "url": urls}))
            .collect();
        let list: Vec<serde_json::Value> = self
            .list
            .iter()
            .map(|(g, n, u)| serde_json::json!({"group": g, "name": n, "url": u}))
            .collect();
        Ok(serde_json::json!({"groups": groups, "list": list}).to_string())
    }

    fn home_content(&self, _filter: bool) -> Result<String, String> {
        // 直播源无点播首页，返回空分类
        Ok(r#"{"class":[],"list":[]}"#.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nested_groups() {
        let json = r#"{"type":2,"ext":[
            {"group":"央视","channels":[{"name":"CCTV1","url":"http://a/1"},{"name":"CCTV5","url":"http://a/5"}]},
            {"group":"卫视","channels":[{"name":"湖南卫视","url":"http://b/hn"}]}
        ]}"#;
        let s = LiveSpider::new(json).unwrap();
        let out = s.live_content().unwrap();
        assert!(out.contains("央视"));
        assert!(out.contains("CCTV1"));
        assert!(out.contains("湖南卫视"));
        assert!(out.contains("\"group_name\""));
        assert!(out.contains("\"http://a/5\""));
    }

    #[test]
    fn flat_list() {
        let json = r#"{"type":2,"ext":[
            {"group":"综合","name":"中央一套","url":"http://c/1"},
            {"group":"综合","name":"中央二套","url":"http://c/2"}
        ]}"#;
        let s = LiveSpider::new(json).unwrap();
        let out = s.live_content().unwrap();
        assert!(out.contains("中央一套"));
        assert!(out.contains("http://c/2"));
    }

    #[test]
    fn empty_rule() {
        let s = LiveSpider::new(r#"{"type":2,"ext":[]}"#).unwrap();
        let out = s.live_content().unwrap();
        assert!(out.contains("\"groups\":[]"));
    }
}
