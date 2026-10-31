//! JsonSpider —— 纯 JSON 规则执行器（CatVod 生态 type=3，无 JS 脚本）。
//!
//! JSON 规则直接把分类与列表数据静态写在配置里，App 端读 ext 构造结果，
//! 不经过 quickjs。这里以 Spider 契约实现同一逻辑：
//!   - `home_content`    → 由 ext 各分类生成 class
//!   - `category_content` → 返回对应分类的 list（视频数据）
//!   - `detail_content`  → 在 list 中按 vod_id 匹配详情
//!   - `player_content`  → 用列表项自带播放地址（或 id 本身）返回 {url}
//!   - `search_content`  → 在本源 list 中按名称模糊匹配
//!
//! 说明：不同订阅源的 JSON 规则字段名略有变体，这里按 CatVod 生态最常见结构
//! （ext[].key/name/list，list[].vod_id/vod_name/...）实现，可据此扩展。

use std::collections::HashMap;

use crate::net::Net;
use crate::proxy::image_proxy;
use crate::spider::{ProxyResp, Spider};

pub struct JsonSpider {
    net: Net,
    categories: Vec<(String, String)>, // (type_id, type_name)
    lists: HashMap<String, serde_json::Value>, // key -> list 数组
}

impl JsonSpider {
    pub fn new(json: &str) -> Result<Self, String> {
        let v: serde_json::Value =
            serde_json::from_str(json).map_err(|e| format!("JSON 规则解析失败: {e}"))?;
        let mut categories = Vec::new();
        let mut lists = HashMap::new();
        if let Some(ext) = v.get("ext").and_then(|e| e.as_array()) {
            for c in ext {
                let key = c.get("key").and_then(|k| k.as_str()).unwrap_or("").to_string();
                let name = c.get("name").and_then(|k| k.as_str()).unwrap_or(key.as_str()).to_string();
                categories.push((key.clone(), name));
                if let Some(list) = c.get("list") {
                    lists.insert(key, list.clone());
                }
            }
        }
        Ok(Self { net: Net::new(), categories, lists })
    }

    fn find_item(&self, id: &str) -> Option<&serde_json::Value> {
        for list in self.lists.values() {
            if let Some(arr) = list.as_array() {
                for item in arr {
                    let vid = item.get("vod_id").and_then(|v| v.as_str()).unwrap_or("");
                    if vid == id {
                        return Some(item);
                    }
                }
            }
        }
        None
    }
}

impl Spider for JsonSpider {
    fn home_content(&self, _filter: bool) -> Result<String, String> {
        let class: Vec<serde_json::Value> = self
            .categories
            .iter()
            .map(|(id, name)| serde_json::json!({"type_id": id, "type_name": name}))
            .collect();
        Ok(serde_json::json!({"class": class, "list": []}).to_string())
    }

    fn category_content(
        &self,
        tid: &str,
        pg: &str,
        _filter: bool,
        _extend: &HashMap<String, String>,
    ) -> Result<String, String> {
        let list = self.lists.get(tid).cloned().unwrap_or(serde_json::json!([]));
        let len = list.as_array().map_or(0, |a| a.len());
        let pagecount = if len > 0 { 999 } else { 0 };
        Ok(serde_json::json!({
            "page": pg,
            "pagecount": pagecount,
            "limit": 20,
            "total": len,
            "list": list,
        })
        .to_string())
    }

    fn detail_content(&self, ids: &[String]) -> Result<String, String> {
        if ids.is_empty() {
            return Ok(r#"{"list":[]}"#.to_string());
        }
        let item = self
            .find_item(&ids[0])
            .cloned()
            .unwrap_or_else(|| serde_json::json!({"vod_id": ids[0]}));
        Ok(serde_json::json!({"list": [item]}).to_string())
    }

    fn search_content(&self, key: &str, _quick: bool, _pg: &str) -> Result<String, String> {
        let mut hits: Vec<serde_json::Value> = Vec::new();
        let k = key.to_lowercase();
        for list in self.lists.values() {
            if let Some(arr) = list.as_array() {
                for item in arr {
                    let name = item.get("vod_name").and_then(|v| v.as_str()).unwrap_or("");
                    if name.to_lowercase().contains(&k) {
                        hits.push(item.clone());
                    }
                }
            }
        }
        Ok(serde_json::json!({"list": hits}).to_string())
    }

    fn player_content(&self, _flag: &str, id: &str, _vip_flags: &[String]) -> Result<String, String> {
        // 优先用列表项自带播放地址；否则把 id 当 url（若含协议）
        let url = self
            .find_item(id)
            .and_then(|i| i.get("vod_play_url").and_then(|v| v.as_str()).map(String::from))
            .unwrap_or_else(|| id.to_string());
        Ok(serde_json::json!({"url": url, "parse": 0}).to_string())
    }

    fn proxy_local(&self, params: &HashMap<String, String>) -> Option<ProxyResp> {
        image_proxy(&self.net, params)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RULE: &str = r#"{
      "key":"demo","name":"演示JSON","type":3,"searchable":1,"quickSearch":0,"filterable":1,
      "ext":[
        {"key":"home","name":"首页","type":0,"url":"","list":[]},
        {"key":"movie","name":"电影","type":0,"url":"","list":[
          {"vod_id":"m1","vod_name":"沙丘","vod_pic":"http://p/1.jpg","vod_remarks":"HD","vod_play_url":"http://play/1.m3u8"},
          {"vod_id":"m2","vod_name":"狂飙","vod_pic":"http://p/2.jpg","vod_remarks":"4K","vod_play_url":"http://play/2.m3u8"}
        ]}
      ]
    }"#;

    #[test]
    fn home_returns_class() {
        let s = JsonSpider::new(RULE).unwrap();
        let home = s.home_content(false).unwrap();
        assert!(home.contains("\"type_id\":\"home\""));
        assert!(home.contains("\"type_name\":\"电影\""));
    }

    #[test]
    fn category_returns_list() {
        let s = JsonSpider::new(RULE).unwrap();
        let cat = s.category_content("movie", "1", false, &HashMap::new()).unwrap();
        assert!(cat.contains("沙丘"));
        assert!(cat.contains("狂飙"));
        assert!(cat.contains("\"vod_name\""));
    }

    #[test]
    fn player_uses_play_url() {
        let s = JsonSpider::new(RULE).unwrap();
        let play = s.player_content("play", "m1", &[]).unwrap();
        assert!(play.contains("\"http://play/1.m3u8\""));
    }

    #[test]
    fn search_matches_name() {
        let s = JsonSpider::new(RULE).unwrap();
        let res = s.search_content("狂飙", false, "1").unwrap();
        assert!(res.contains("狂飙"));
    }
}
