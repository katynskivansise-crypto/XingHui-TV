//! 爬虫执行器 —— 对应原 `catvod/src/main/java/com/github/catvod/crawler/Spider.java`
//!
//! 原 Spider 是抽象基类，各站点爬虫继承并实现各 Content 方法，返回 JSON 字符串。
//! 这里用 Rust trait 表达同一契约，并提供 SpiderManager 做注册/按 key 调度。

use std::collections::HashMap;

/// Spider 契约，与 CatVod 生态一一对应。
/// 方法返回 String 的均为 JSON 字符串（与原实现一致，便于上层 app 直接消费）。
///
/// 注意：不要求 Send + Sync —— quickjs 的 Context 非 Send，必须在创建线程内使用，
/// 由 SpiderManager 在单一调度线程上调用。
pub trait Spider {
    fn init(&self, _extend: Option<&str>) -> Result<(), String> {
        Ok(())
    }

    /// 首页内容（原 `homeContent(filter)`）
    fn home_content(&self, _filter: bool) -> Result<String, String> {
        Ok(String::new())
    }

    /// 分类内容（原 `categoryContent(tid, pg, filter, extend)`）
    fn category_content(
        &self,
        _tid: &str,
        _pg: &str,
        _filter: bool,
        _extend: &HashMap<String, String>,
    ) -> Result<String, String> {
        Ok(String::new())
    }

    /// 详情内容（原 `detailContent(ids)`）
    fn detail_content(&self, _ids: &[String]) -> Result<String, String> {
        Ok(String::new())
    }

    /// 搜索内容（原 `searchContent(key, quick, pg)`）
    fn search_content(&self, _key: &str, _quick: bool, _pg: &str) -> Result<String, String> {
        Ok(String::new())
    }

    /// 播放内容（原 `playerContent(flag, id, vipFlags)`）
    fn player_content(&self, _flag: &str, _id: &str, _vip_flags: &[String]) -> Result<String, String> {
        Ok(String::new())
    }

    /// 直播内容（原 `liveContent()`）
    fn live_content(&self) -> Result<String, String> {
        Ok(String::new())
    }

    /// 本地代理（原 `proxyLocal(params)`）
    fn proxy_local(&self, _params: &HashMap<String, String>) -> Option<ProxyResp> {
        None
    }

    /// 自定义 action（原 `action(action)`）
    fn action(&self, _action: &str) -> Option<String> {
        None
    }

    /// 释放资源（原 `destroy()`）
    fn destroy(&self) {}
}

/// `proxyLocal` 的返回体：原实现返回 `Object[]`（首项为类型标记，其后为数据）。
/// 这里用结构化表示，便于 JNI 转换。
#[derive(Debug, Clone)]
pub struct ProxyResp {
    /// 原 Object[0]，如 "application/vnd.apple.mpegurl"
    pub content_type: String,
    /// 原 Object[1..]，字节数据
    pub body: Vec<u8>,
}

/// 爬虫注册表：按 key（如站点名/配置 key）调度到具体 Spider。
#[derive(Default)]
pub struct SpiderManager {
    spiders: HashMap<String, Box<dyn Spider>>,
}

impl SpiderManager {
    pub fn new() -> Self {
        SpiderManager::default()
    }

    pub fn register(&mut self, key: &str, spider: Box<dyn Spider>) {
        self.spiders.insert(key.to_string(), spider);
    }

    pub fn get(&self, key: &str) -> Option<&dyn Spider> {
        self.spiders.get(key).map(|b| b.as_ref())
    }

    /// 已注册的所有源 key（聚合搜索/源列表遍历用）
    pub fn keys(&self) -> Vec<String> {
        self.spiders.keys().cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Stub;
    impl Spider for Stub {
        fn home_content(&self, _filter: bool) -> Result<String, String> {
            Ok(r#"{"code":0,"msg":"","list":[]}"#.to_string())
        }
    }

    #[test]
    fn manager_registers_and_dispatches() {
        let mut m = SpiderManager::new();
        m.register("demo", Box::new(Stub));
        let s = m.get("demo").expect("demo registered");
        let out = s.home_content(false).unwrap();
        assert!(out.contains(r#""code":0"#));
        assert!(m.get("missing").is_none());
    }
}
