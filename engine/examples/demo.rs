//! 端到端演示：验证引擎 net + json 层能真实工作。
//! 运行：cargo run --example demo

use tv_engine::{json, net};

fn main() {
    let net = net::Net::new();
    let url = "https://api.github.com/zen";
    let headers = {
        let mut h = std::collections::HashMap::new();
        // GitHub API 要求 User-Agent
        h.insert("User-Agent".into(), "tv-engine-demo".into());
        h
    };
    let body = net.string_with_headers(url, &headers);
    if body.is_empty() {
        println!("[net] 请求失败或返回空（云环境可能无外网）");
    } else {
        println!("[net] GET {} -> 成功，响应首行: {}", url, body.lines().next().unwrap_or("(空)"));
    }

    // json 层真实解析一段类 CatVod 响应
    let sample = r#"{"code":0,"msg":"","list":[{"vod_name":"示例片","vod_id":"1"}]}"#;
    let v = json::parse(sample);
    let list = v
        .as_object()
        .and_then(|o| o.get("list"))
        .and_then(|l| l.as_array())
        .map(|a| a.len())
        .unwrap_or(0);
    println!("[json] 解析 CatVod 响应 -> list 数量 = {}", list);
    println!("[demo] OK");
}
