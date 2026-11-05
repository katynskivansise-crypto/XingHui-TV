//! DrpySpider —— 把 `DrpyEngine`（原样执行真实 drpy 规则的执行器）适配进 Spider 契约。
//!
//! 与 fongmi 原版一致：quickjs 的 JS context 非线程安全，原版用 ThreadLocal 每线程一份引擎。
//! 这里同样**不要求 Send/Sync**，由调度方（JNI thread_local）保证单线程访问，
//! 引擎在首次调用时惰性初始化（init 等价于构造）。

use std::cell::{Ref, RefCell};
use std::collections::HashMap;
use std::sync::Arc;

use crate::drpy_engine::{DrpyEngine, ReqImpl};
use crate::net::Net;
use crate::spider::{ProxyResp, Spider};

/// 将 drpy 规则接入 Spider 契约的适配器。
pub struct DrpySpider {
    cell: RefCell<Option<DrpyEngine>>,
    net: Net,
    dir: String,
    rule: String,
    engine_script: String,
}

impl DrpySpider {
    /// `dir`：引擎 import 依赖所在目录（如 drpy-core-lite.min.js）；
    /// `rule`：drpy 规则脚本内容（如 drpy.js 的规则段，或完整 drpy.js）；
    /// `engine_script`：drpy 引擎脚本内容（如 drpy2.min.js）；
    /// `ua`：可选 UA（空则用引擎默认）；
    /// `proxy`：可选代理地址 scheme://user:pass@host:port（空则关闭代理）；
    /// `headers`：站点级 header JSON（对齐原项目 getHeaders，站点 header 优先覆盖全局）。
    pub fn new(
        dir: String,
        rule: String,
        engine_script: String,
        ua: &str,
        proxy: &str,
        headers: &str,
    ) -> Self {
        let mut net = Net::new();
        net.set_ua(ua);
        net.set_proxy(proxy);
        let site_headers: HashMap<String, String> = if headers.is_empty() {
            HashMap::new()
        } else {
            serde_json::from_str(headers).unwrap_or_default()
        };
        net.set_headers(&site_headers);
        Self {
            cell: RefCell::new(None),
            net,
            dir,
            rule,
            engine_script,
        }
    }

    /// 惰性初始化引擎（首次调用时用 Net 作为请求后端）。
    fn ensure(&self) -> Result<Ref<'_, DrpyEngine>, String> {
        if self.cell.borrow().is_none() {
            let net = Arc::new(self.net.clone());
            // 完整请求透传：url + options（method/headers/body），Latin-1 字节串传输，
            // JS 侧按规则"编码"字段解码（支持 gb2312/gbk），对齐原 quickjs `req(url, options)`
            let req: ReqImpl = Arc::new(move |url: &str, opts: &str| net.request_latin1_full(url, opts));
            let engine = DrpyEngine::new(&self.dir, &self.rule, &self.engine_script, req)?;
            *self.cell.borrow_mut() = Some(engine);
        }
        Ok(Ref::map(self.cell.borrow(), |o| o.as_ref().unwrap()))
    }
}

impl Spider for DrpySpider {
    fn init(&self, _extend: Option<&str>) -> Result<(), String> {
        self.ensure()?;
        Ok(())
    }

    fn home_content(&self, _filter: bool) -> Result<String, String> {
        self.ensure()?.home()
    }

    fn category_content(
        &self,
        tid: &str,
        pg: &str,
        _filter: bool,
        extend: &HashMap<String, String>,
    ) -> Result<String, String> {
        // Spider 契约的 extend 是扁平 map；DrpyEngine 期望 filter 值 JSON（如 {"sort":"recommend"}）。
        // 这里把 map 序列化成对象 JSON 传给引擎。
        let ext = if extend.is_empty() {
            String::new()
        } else {
            serde_json::json!(extend).to_string()
        };
        self.ensure()?.category(tid, pg, &ext)
    }

    fn detail_content(&self, ids: &[String]) -> Result<String, String> {
        self.ensure()?.detail(&ids.join(","))
    }

    fn search_content(&self, key: &str, quick: bool, _pg: &str) -> Result<String, String> {
        self.ensure()?.search(key, quick)
    }

    fn player_content(&self, flag: &str, id: &str, _vip_flags: &[String]) -> Result<String, String> {
        self.ensure()?.play(flag, id)
    }

    fn proxy_local(&self, params: &HashMap<String, String>) -> Option<ProxyResp> {
        // 对齐原 JsLoader.proxyInvoke → Spider.proxy1：调用 drpy 规则的 `proxy(params)`，
        // 返回 [status, contentType, body, headers?, base64Flag?]
        // 规则未 init 时不重建引擎：proxy 依赖 rule.proxy_rule，空 rule 只会退化成 404
        if self.cell.borrow().is_none() {
            return None;
        }
        let engine = self.ensure().ok()?;
        let args = serde_json::to_string(params).ok()?;
        let out = engine.call("proxy", vec![args]).ok()?;
        super::proxy::parse_proxy_array(&out)
    }

    fn destroy(&self) {
        self.cell.borrow_mut().take();
    }
}
