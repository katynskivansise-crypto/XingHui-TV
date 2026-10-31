//! DrpyEngine 端到端验证：加载真实 drpy 规则（qist/tvbox 的 drpy.js + drpy2.min.js），
//! 调用 home() 输出 CatVod JSON。
//!
//! 运行：cargo run --example test_drpy_engine

use std::fs;
use std::sync::Arc;

use tv_engine::drpy_engine::{DrpyEngine, ReqImpl};
use tv_engine::net::Net;

fn main() {
    let dir = "../samples/rules";
    let rule = fs::read_to_string(format!("{dir}/drpy.js")).expect("读 drpy.js");
    let engine = fs::read_to_string(format!("{dir}/drpy2.min.js")).expect("读 drpy2.min.js");

    let net = Arc::new(Net::new());
    let req: ReqImpl = Arc::new(move |url: &str| net.string(url));
    let e = DrpyEngine::new(dir, &rule, &engine, req).expect("创建 DrpyEngine");

    let home = e.home().expect("调用 home");
    println!("[OK] home() 输出 {} 字节", home.len());
    let preview: String = home.chars().take(200).collect();
    println!("[home] 前 200 字符:");
    println!("{preview}");

    // 校验是 CatVod JSON：含 class
    assert!(home.contains("\"class\""), "home 应含 class");
    assert!(home.contains("\"filters\""), "home 应含 filters");
    println!("[PASS] home 输出为有效 CatVod 首页 JSON（class+filters）");
}
