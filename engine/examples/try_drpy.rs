//! 诊断：提取 rquickjs 加载真实 drpy 引擎时的具体 JS 异常消息/堆栈。
//! 运行：cargo run --example try_drpy

use std::fs;

use rquickjs::{CaughtError, Context, Function, Object, Runtime, Value};

fn dump(ctx: &rquickjs::Ctx, e: rquickjs::Error, tag: &str) {
    let ce = CaughtError::from_error(ctx, e);
    match ce {
        CaughtError::Exception(ex) => println!(
            "{tag} JS异常 msg={:?}\n     stack={:?}",
            ex.message(),
            ex.stack().map(|s| s.lines().take(6).collect::<Vec<_>>().join(" | "))
        ),
        CaughtError::Value(v) => println!("{tag} 非Error异常 value={v:?}"),
        CaughtError::Error(err) => println!("{tag} 宿主错误 {err:?}"),
    }
}

fn main() {
    let base = "../samples/rules";
    let rt = Runtime::new().expect("runtime");
    let ctx = Context::full(&rt).expect("context");

    ctx.with(|ctx| {
        let _ = ctx.globals().set(
            "print",
            Function::new(ctx.clone(), |s: String| println!("[JS print] {s}")),
        );
        let _ = ctx.globals().set(
            "fetch",
            Function::new(ctx.clone(), |u: String| -> String { format!("FETCH:{}", u) }),
        );
        let g = ctx.globals().clone();
        for k in ["self", "window", "global", "globalThis"] {
            let _ = ctx.globals().set(k, g.clone());
        }
        let con = Object::new(ctx.clone()).unwrap();
        let _ = con.set("log", Function::new(ctx.clone(), |s: String| println!("[console] {s}")));
        let _ = con.set("error", Function::new(ctx.clone(), |s: String| println!("[console.err] {s}")));
        let _ = ctx.globals().set("console", con);

        // 逐段诊断 core-lite：看异常发生在 bundle 的哪一步
        let core = fs::read_to_string(format!("{base}/drpy-core-lite.min.js")).expect("core file");
        println!("[core-lite] 长度 {} bytes, 开始 eval", core.len());
        let r: rquickjs::Result<Value> = ctx.eval(core.as_str());
        if let Err(e) = r {
            dump(&ctx, e, "[core-lite]");
        } else {
            println!("[core-lite] eval OK");
        }

        // drpy2（含 import）
        let drpy2 = fs::read_to_string(format!("{base}/drpy2.min.js")).expect("drpy2 file");
        let r: rquickjs::Result<Value> = ctx.eval(drpy2.as_str());
        if let Err(e) = r {
            dump(&ctx, e, "[drpy2]");
        } else {
            println!("[drpy2] eval OK");
        }
    });
    println!("[done]");
}
