//! Spike：用 rquickjs 的 **module 模式**（非 script eval）加载真实 drpy 引擎。
//! 运行：cargo run --example module_drpy

use std::fs;

use rquickjs::loader::{Resolver, ScriptLoader};
use rquickjs::{CaughtError, Context, Function, Module, Object, Runtime, Value};

/// 自定义 Resolver：处理 drpy 的 `./` 相对路径，解析为 base 目录下的绝对路径。
struct Res {
    dir: String,
}
impl Resolver for Res {
    fn resolve<'js>(&mut self, _ctx: &rquickjs::Ctx<'js>, _base: &str, name: &str) -> rquickjs::Result<String> {
        let clean = name.trim_start_matches("./");
        Ok(format!("{}/{}", self.dir, clean))
    }
}

fn dump(ctx: &rquickjs::Ctx, e: rquickjs::Error, tag: &str) {
    match CaughtError::from_error(ctx, e) {
        CaughtError::Exception(ex) => println!("{tag} JS异常 msg={:?}", ex.message()),
        CaughtError::Value(v) => println!("{tag} 非Error异常 value={v:?}"),
        CaughtError::Error(err) => println!("{tag} 宿主错误 {err:?}"),
    }
}

fn main() {
    let base = "../samples/rules";

    let resolver = Res { dir: base.to_string() };
    let loader = ScriptLoader::default();

    let rt = Runtime::new().expect("runtime");
    rt.set_loader(resolver, loader);
    let ctx = Context::full(&rt).expect("context");

    ctx.with(|ctx| {
        let _ = ctx.globals().set("print", Function::new(ctx.clone(), |s: String| println!("[JS print] {s}")));
        let _ = ctx.globals().set("fetch", Function::new(ctx.clone(), |u: String| -> String { format!("FETCH:{u}") }));
        let g = ctx.globals().clone();
        for k in ["self", "window", "global", "globalThis"] {
            let _ = ctx.globals().set(k, g.clone());
        }
        let con = Object::new(ctx.clone()).unwrap();
        let _ = con.set("log", Function::new(ctx.clone(), |s: String| println!("[console] {s}")));
        let _ = con.set("error", Function::new(ctx.clone(), |s: String| println!("[console.err] {s}")));
        let _ = ctx.globals().set("console", con);

        // 1) core-lite：drpy2 用命名导入 {cheerio, 模板}
        let core = fs::read(format!("{base}/drpy-core-lite.min.js")).expect("core");
        match Module::declare(ctx.clone(), "drpy-core-lite.min.js", core) {
            Ok(m) => match m.eval() {
                Ok((m, _p)) => {
                    for name in ["cheerio", "模板", "default"] {
                        let r: rquickjs::Result<Value> = m.get(name);
                        match r {
                            Ok(v) => println!("[1] core-lite 命名导出 {name}: {} ({})", !v.is_undefined(), v.type_name()),
                            Err(e) => println!("[1] core-lite 导出 {name} 不存在: {e}"),
                        }
                    }
                }
                Err(e) => dump(&ctx, e, "[1] core-lite eval"),
            },
            Err(e) => dump(&ctx, e, "[1] core-lite declare"),
        }

        // 2) drpy2：declare + eval
        let drpy2 = fs::read(format!("{base}/drpy2.min.js")).expect("drpy2");
        match Module::declare(ctx.clone(), "drpy2.min.js", drpy2) {
            Ok(m) => match m.eval() {
                Ok((_m, _p)) => println!("[2] drpy2 module 加载: OK"),
                Err(e) => dump(&ctx, e, "[2] drpy2 eval"),
            },
            Err(e) => dump(&ctx, e, "[2] drpy2 declare"),
        }
    });
    println!("[done] spike 完成");
}
