//! JS 规则执行器 —— 原样执行 CatVod 的 JS 爬虫规则（对应原 quickjs 模块）。
//!
//! 这是"功能 100% 移植"的决定性环节：CatVod 各站点的解析逻辑以 JS 脚本（订阅源）
//! 下发，App 用内嵌 quickjs 执行。这里用 rquickjs（quickjs-ng，与原项目同源）嵌入
//! JS 引擎，把 JS 顶层函数映射为 `Spider` trait 的方法。
//!
//! 关键设计：
//!   - `rquickjs::Context` 非 Send/Sync，必须在创建线程内使用 —— `Spider` trait
//!     因此不要求 Send+Sync，由 SpiderManager 在单一调度线程调用。
//!   - 运行时用 `Box::leak` 借给 Context 以获得 `'static` 生命周期，可存入结构体；
//!     代价是内存随进程存活（规则数量有限，可接受），`destroy()` 通过 drop Context 释放 JS 侧。
//!   - 注入宿主网络函数 `_cat_http(method,url,headersJson)`，JS 规则的网络请求
//!     实际走 Rust 引擎的 `net::Net`。

use std::sync::Arc;

use rquickjs::{Context, Function, Runtime, Value};

use crate::net::Net;
use crate::spider::Spider;

/// 一个 CatVod JS 规则对应的执行器。
pub struct JsSpider {
    ctx: Context,
}

impl JsSpider {
    /// 创建并加载规则脚本。
    /// `script` 是订阅源里的 JS 规则文本；`net` 用于宿主网络函数。
    pub fn new(script: &str, net: Arc<Net>) -> Result<Self, String> {
        let rt = Box::leak(Box::new(Runtime::new().map_err(|e| format!("runtime: {e}"))?));
        let ctx = Context::full(rt).map_err(|e| format!("context: {e}"))?;

        ctx.with(|ctx| -> Result<(), String> {
            // 注入宿主网络函数：_cat_http(method, url, headersJson) -> body
            let net = net.clone();
            let f = Function::new(ctx.clone(), move |_method: String, url: String, headers: String| -> String {
                let headers: std::collections::HashMap<String, String> =
                    serde_json::from_str(&headers).unwrap_or_default();
                net.string_with_headers(&url, &headers)
            })
            .map_err(|e| format!("host fn: {e}"))?;
            ctx.globals()
                .set("_cat_http", f)
                .map_err(|e| format!("set host fn: {e}"))?;

            // 求值规则脚本
            let _: Value = ctx.eval(script).map_err(|e| format!("eval script: {e}"))?;
            Ok(())
        })?;

        Ok(JsSpider { ctx })
    }

    /// 调用 JS 顶层函数。`json_args` 为每个参数的 JSON 表达式文本（bool 传 "true"/
    /// "false"，字符串传带引号 JSON，数组/对象按 JSON 传）。返回 CatVod JSON 字符串。
    fn invoke(&self, name: &str, json_args: &[&str]) -> Result<String, String> {
        self.ctx.with(|ctx| {
            let globals = ctx.globals();
            let f: Function = globals
                .get(name)
                .map_err(|e| format!("规则缺少方法 {name}: {e}"))?;

            // 把参数逐个求值为 JS 值（JSON 表达式求值即得值）
            let args: Vec<Value> = json_args
                .iter()
                .map(|s| ctx.eval(*s).map_err(|e| format!("参数 {s}: {e}")))
                .collect::<Result<_, String>>()?;

            let ret: Value = match args.len() {
                0 => f.call(()),
                1 => f.call((args[0].clone(),)),
                2 => f.call((args[0].clone(), args[1].clone())),
                3 => f.call((args[0].clone(), args[1].clone(), args[2].clone())),
                4 => f.call((args[0].clone(), args[1].clone(), args[2].clone(), args[3].clone())),
                _ => return Err(format!("不支持 {} 个参数", args.len())),
            }
            .map_err(|e| format!("JS 方法 {name} 调用失败: {e}"))?;

            // 返回字符串；若为对象则用宿主 JSON.stringify 序列化
            if let Some(s) = ret.as_string() {
                s.to_string().map_err(|e| e.to_string())
            } else {
                let str: Function = globals
                    .get("JSON.stringify")
                    .map_err(|e| format!("no JSON.stringify: {e}"))?;
                let out: String = str.call((ret,)).map_err(|e| format!("stringify: {e}"))?;
                Ok(out)
            }
        })
    }
}

impl Spider for JsSpider {
    fn home_content(&self, filter: bool) -> Result<String, String> {
        self.invoke("homeContent", &[if filter { "true" } else { "false" }])
    }

    fn category_content(&self, tid: &str, pg: &str, filter: bool, extend: &std::collections::HashMap<String, String>) -> Result<String, String> {
        let extend_json = serde_json::to_string(extend).unwrap_or_else(|_| "{}".into());
        self.invoke(
            "categoryContent",
            &[
                &format!(r#""{tid}""#),
                &format!(r#""{pg}""#),
                if filter { "true" } else { "false" },
                &extend_json,
            ],
        )
    }

    fn detail_content(&self, ids: &[String]) -> Result<String, String> {
        let ids_json = serde_json::to_string(ids).unwrap_or_else(|_| "[]".into());
        self.invoke("detailContent", &[&ids_json])
    }

    fn search_content(&self, key: &str, quick: bool, _pg: &str) -> Result<String, String> {
        self.invoke(
            "searchContent",
            &[
                &format!(r#""{key}""#),
                if quick { "true" } else { "false" },
            ],
        )
    }

    fn player_content(&self, flag: &str, id: &str, vip_flags: &[String]) -> Result<String, String> {
        let vip_json = serde_json::to_string(vip_flags).unwrap_or_else(|_| "[]".into());
        self.invoke(
            "playerContent",
            &[
                &format!(r#""{flag}""#),
                &format!(r#""{id}""#),
                &vip_json,
            ],
        )
    }

    fn live_content(&self) -> Result<String, String> {
        self.invoke("liveContent", &[])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    /// 模拟一段 okcaptain 风格的 JS 规则。
    const SAMPLE_RULE: &str = r#"
var rule = { host: "demo.example.com" };
function homeContent(filter) {
  return JSON.stringify({
    class: [{ type_id: "1", type_name: "电影" }],
    list: [{ vod_id: "1001", vod_name: "来自JS规则", vod_year: "2026", vod_area: "示例", vod_remarks: "JS执行成功" }]
  });
}
function categoryContent(tid, pg, filter, extend) {
  return JSON.stringify({ code: 0, list: [{ vod_id: tid + "_" + pg, vod_name: "分类" + tid + "第" + pg + "页" }] });
}
function detailContent(ids) {
  return JSON.stringify({ vod_id: ids[0], vod_name: "详情_" + ids[0] });
}
function searchContent(key, quick) {
  return JSON.stringify({ list: [{ vod_name: "搜到_" + key }] });
}
function playerContent(flag, id, vipFlags) {
  return JSON.stringify({ playUrl: flag + "://" + id, parse: 1 });
}
function liveContent() {
  return JSON.stringify({ code: 0, list: [] });
}
"#;

    fn make() -> JsSpider {
        JsSpider::new(SAMPLE_RULE, Arc::new(Net::new())).expect("load rule")
    }

    #[test]
    fn js_home_content() {
        let out = make().home_content(false).unwrap();
        assert!(out.contains("来自JS规则"), "got: {out}");
        assert!(out.contains(r#""type_name":"电影""#));
    }

    #[test]
    fn js_category_content() {
        let ext = std::collections::HashMap::new();
        let out = make().category_content("2", "3", false, &ext).unwrap();
        assert!(out.contains(r#""vod_id":"2_3""#), "got: {out}");
    }

    #[test]
    fn js_detail_content() {
        let out = make().detail_content(&["abc".into()]).unwrap();
        assert!(out.contains("详情_abc"), "got: {out}");
    }

    #[test]
    fn js_search_content() {
        let out = make().search_content("流浪地球", false, "1").unwrap();
        assert!(out.contains("搜到_流浪地球"), "got: {out}");
    }

    #[test]
    fn js_player_content() {
        let out = make().player_content("flag", "id1", &[]).unwrap();
        assert!(out.contains(r#""playUrl":"flag://id1""#), "got: {out}");
    }

    #[test]
    fn js_live_content() {
        let out = make().live_content().unwrap();
        assert!(out.contains(r#""code":0"#), "got: {out}");
    }
}
