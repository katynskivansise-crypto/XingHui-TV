//! DrpyEngine —— 原样执行 CatVod drpy 格式订阅源的 Rust 执行器。
//!
//! 真实订阅源（如 qist/tvbox）的规则格式：
//!   - 引擎脚本 `drpy2.min.js`：ES module，import cheerio，export {init,home,category,detail,play,search,...}
//!   - 规则脚本 `drpy.js`：定义 `rule` 数据对象
//!
//! 加载协议（已用 node + rquickjs 实测验证）：
//!   1. module 模式（Module::declare/eval）加载引擎，支持 ES module import
//!   2. 宿主经 `globalThis` 注入未声明全局：
//!        - `_net(url, obj)`   Rust 原生网络，返回 body 字符串
//!        - `req(url, obj)`    JS 包装，返回 `{content: body, headers: {...}}`（引擎 `request` 取 `res.content`）
//!        - `http/fetch`       JS 包装
//!        - `print/log/console/pdfh/pdfa/pd/joinUrl/buildUrl/stringify`
//!   3. 规则脚本以 script 模式 eval，设置全局 `rule`
//!   4. `init(rule)` → 引擎持有规则 → `home()/category()/...` 返回 CatVod JSON 字符串

use std::sync::Arc;

use rquickjs::loader::{Resolver, ScriptLoader};
use rquickjs::{CaughtError, Context, Function, Module, Runtime, Value};

/// drpy 的 `./` 相对 import 解析为规则目录下的绝对路径。
struct DirResolver {
    dir: String,
}
impl Resolver for DirResolver {
    fn resolve<'js>(&mut self, _ctx: &rquickjs::Ctx<'js>, _base: &str, name: &str) -> rquickjs::Result<String> {
        Ok(format!("{}/{}", self.dir, name.trim_start_matches("./")))
    }
}

/// 请求实现：输入 URL 返回 body 字符串（生产走 Rust net，测试可注入 mock）。
pub type ReqImpl = Arc<dyn Fn(&str) -> String + Send + Sync>;

/// drpy 格式规则的执行器。
pub struct DrpyEngine {
    ctx: Context,
}

impl DrpyEngine {
    /// 创建并加载规则。
    /// `dir`：引擎脚本所在目录（用于解析 `./` import 依赖）
    /// `rule_script`：规则脚本内容（定义 `var rule`）
    /// `engine_script`：引擎脚本内容（drpy2.min.js）
    /// `req`：请求实现（走 Rust net）
    pub fn new(dir: &str, rule_script: &str, engine_script: &str, req: ReqImpl) -> Result<Self, String> {
        let rt = Box::leak(Box::new(Runtime::new().map_err(|e| format!("runtime: {e}"))?));
        rt.set_loader(DirResolver { dir: dir.to_string() }, ScriptLoader::default());
        let ctx = Context::full(rt).map_err(|e| format!("context: {e}"))?;

        ctx.with(|ctx| -> Result<(), String> {
            Self::inject_host(&ctx, req)?;

            // 规则脚本（script 模式）→ 设置全局 rule
            let _: Value = ctx.eval(rule_script).map_err(|e| format!("eval rule: {e}"))?;

            // 引擎脚本（module 模式，支持 import）→ 导出存到 globalThis.__drpy
            let engine = Module::declare(ctx.clone(), "drpy2.min.js", engine_script)
                .map_err(|e| format!("declare engine: {e}"))?;
            let (m, _p) = engine.eval().map_err(|e| format!("eval engine: {e}"))?;
            let export: Value = m.get("default").map_err(|e| format!("get engine default export: {e}"))?;
            ctx.globals().set("__drpy", export).map_err(|e| format!("set __drpy: {e}"))?;

            Ok(())
        })?;

        Ok(DrpyEngine { ctx })
    }

    /// 注入 drpy 宿主全局。
    fn inject_host(ctx: &rquickjs::Ctx, req: ReqImpl) -> Result<(), String> {
        let ctx = (*ctx).clone();
        let _ = ctx.globals().set("print", Function::new(ctx.clone(), |s: String| eprintln!("[drpy.print] {s}")));
        let _ = ctx.globals().set("log", Function::new(ctx.clone(), |s: String| eprintln!("[drpy.log] {s}")));

        // 浏览器/webpack 全局垫片
        let g = ctx.globals().clone();
        for k in ["self", "window", "global"] {
            let _ = ctx.globals().set(k, g.clone());
        }

        // 原生 `_net(url, obj)` → body 字符串
        let net_fn = Function::new(ctx.clone(), move |url: String| req(&url)).map_err(|e| format!("_net: {e}"))?;
        let _ = ctx.globals().set("_net", net_fn);

        // pdfh/pdfa/pd：HTML 解析宿主（完整实现另见 pdfh.rs；当前基础实现）
        let pdfh = Function::new(ctx.clone(), |html: String, rule: String| super::pdfh::pdfh(&html, &rule))
            .map_err(|e| format!("pdfh: {e}"))?;
        let _ = ctx.globals().set("pdfh", pdfh);
        let pdfa = Function::new(ctx.clone(), |html: String, rule: String| super::pdfh::pdfa(&html, &rule))
            .map_err(|e| format!("pdfa: {e}"))?;
        let _ = ctx.globals().set("pdfa", pdfa);
        let pd = Function::new(ctx.clone(), |html: String, rule: String, _key: String| super::pdfh::pdfh(&html, &rule))
            .map_err(|e| format!("pd: {e}"))?;
        let _ = ctx.globals().set("pd", pd);
        for (alias, from) in [("_pdfh", "pdfh"), ("_pdfa", "pdfa"), ("_pd", "pd")] {
            let v: Value = ctx.globals().get(from).map_err(|e| format!("get {from}: {e}"))?;
            let _ = ctx.globals().set(alias, v);
        }

        // JS 包装层：req 返回 {content, headers}，buildUrl/joinUrl 等
        let js = r#"
globalThis.console = { log: globalThis.print, error: globalThis.log, warn: globalThis.log };
globalThis.oheaders = {};
globalThis.fetch_params = {};
globalThis.stringify = JSON.stringify;
globalThis.buildUrl = function(base, query) { var q = []; for (var k in query) q.push(k + "=" + encodeURIComponent(query[k])); return base + "?" + q.join("&"); };
globalThis.joinUrl = function(parent, child) { if (!child) return parent; if (child.indexOf("http") >= 0) return child; return parent.replace(/\/+$/, "") + "/" + child.replace(/^\/+/, ""); };
globalThis.req = function(url, obj) { return { content: _net(url, obj), headers: {} }; };
globalThis.http = function(url, obj) { return _net(url, obj); };
globalThis.fetch = function(url, obj) { return _net(url, obj); };
"#;
        let _: Value = ctx.eval(js).map_err(|e| format!("eval host js: {e}"))?;

        Ok(())
    }

    /// 调用引擎导出的方法，参数为 JSON 字面量（在 ctx 里 eval 成 JS 值）。
    /// 注意：对象字面量必须用 `({...})` 包裹，避免 `{}` 被解析为空语句块。
    fn call(&self, name: &str, json_args: Vec<String>) -> Result<String, String> {
        self.ctx.with(|ctx| -> Result<String, String> {
            let globals = ctx.globals();
            let engine: Value = globals.get("__drpy").map_err(|e| format!("__drpy: {e}"))?;
            let engine = engine.as_object().ok_or("__drpy 不是对象")?.clone();
            let f: Function = engine.get(name).map_err(|e| format!("引擎无方法 {name}: {e}"))?;

            let args: Vec<Value> = json_args
                .iter()
                .map(|s| ctx.eval(s.as_str()).map_err(|e| format!("参数 {s}: {e}")))
                .collect::<Result<_, String>>()?;

            let ret: Value = match args.len() {
                0 => f.call(()),
                1 => f.call((args[0].clone(),)),
                2 => f.call((args[0].clone(), args[1].clone())),
                3 => f.call((args[0].clone(), args[1].clone(), args[2].clone())),
                4 => f.call((args[0].clone(), args[1].clone(), args[2].clone(), args[3].clone())),
                _ => return Err(format!("不支持 {} 个参数", args.len())),
            }
            .map_err(|e| format!("调用 {name} 失败: {e}"))?;

            if let Some(s) = ret.as_string() {
                s.to_string().map_err(|e| e.to_string())
            } else {
                let str: Function = globals.get("JSON.stringify").map_err(|e| format!("stringify: {e}"))?;
                let out: String = str.call((ret,)).map_err(|e| format!("stringify: {e}"))?;
                Ok(out)
            }
        })
    }

    fn ensure_init(&self) -> Result<(), String> {
        self.ctx.with(|ctx| -> Result<(), String> {
            let globals = ctx.globals();
            if let Ok(true) = globals.get::<_, bool>("__drpy_inited") {
                return Ok(());
            }
            let engine: Value = globals.get("__drpy").map_err(|e| format!("__drpy: {e}"))?;
            let engine = engine.as_object().ok_or("__drpy 不是对象")?.clone();
            let init: Function = engine.get("init").map_err(|e| format!("无 init: {e}"))?;
            let rule: Value = globals.get("rule").map_err(|e| format!("无全局 rule: {e}"))?;
            let init_res: rquickjs::Result<Value> = init.call((rule,));
            if let Err(e) = init_res {
                let msg = match CaughtError::from_error(&ctx, e) {
                    CaughtError::Exception(ex) => ex.message().unwrap_or_default().to_string(),
                    CaughtError::Error(err) => format!("{err:?}"),
                    _ => "unknown".to_string(),
                };
                return Err(format!("init 失败: {msg}"));
            }
            let _ = globals.set("__drpy_inited", true);
            Ok(())
        })
    }

    /// 首页分类（真实规则：init 已持有 rule，home 返回 class+filters）
    pub fn home(&self) -> Result<String, String> {
        self.ensure_init()?;
        self.call("home", vec![])
    }

    /// 分类列表。
    /// `tid`：type_id；`pg`：页码；`extend`：filter 值对象 JSON（如 `{"sort":"recommend"}`）。
    pub fn category(&self, tid: &str, pg: &str, extend: &str) -> Result<String, String> {
        self.ensure_init()?;
        let ext = if extend.trim().is_empty() {
            "{}".to_string()
        } else {
            extend.trim().to_string()
        };
        self.call(
            "category",
            vec![format!("{tid:?}"), format!("{pg:?}"), "false".into(), format!("({ext})")],
        )
    }

    /// 详情。
    pub fn detail(&self, ids: &str) -> Result<String, String> {
        self.ensure_init()?;
        self.call("detail", vec![format!("{ids:?}")])
    }

    /// 播放。
    pub fn play(&self, flag: &str, vod_id: &str) -> Result<String, String> {
        self.ensure_init()?;
        self.call("play", vec![format!("{flag:?}"), format!("{vod_id:?}")])
    }

    /// 搜索。
    pub fn search(&self, key: &str, quick: bool) -> Result<String, String> {
        self.ensure_init()?;
        let quick = if quick { "true" } else { "false" };
        self.call("search", vec![format!("{key:?}"), quick.into()])
    }
}
