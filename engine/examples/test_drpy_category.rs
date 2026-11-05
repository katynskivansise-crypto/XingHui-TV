//! DrpyEngine category 端到端验证（mock req）：
//! 注入返回豆瓣格式 JSON 的 req 宿主，验证真实 drpy 规则的 `js:` 一级表达式
//! 能构造出 CatVod 列表（vod_name/vod_pic/vod_remarks）。
//!
//! 运行：cargo run --example test_drpy_category

use std::fs;
use std::sync::Arc;

use tv_engine::drpy_engine::{DrpyEngine, ReqImpl};

const DIR: &str = "../samples/rules";

fn main() {
    let rule = fs::read_to_string(format!("{DIR}/drpy.js")).expect("drpy.js");
    let engine = fs::read_to_string(format!("{DIR}/drpy2.min.js")).expect("drpy2.min.js");

    // mock 豆瓣 API 返回（hot_gaia 走 res.items）
    let mock = r#"{"total":2,"items":[
      {"id":"a1","type":"movie","title":"沙丘","pic":{"normal":"http://p1/shaqiu.jpg"},"rating":{"value":8.3},"honor_infos":[{"title":"一周口碑榜"}]},
      {"id":"b2","type":"tv","title":"狂飙","pic":{"normal":"http://p2/kuangbiao.jpg"},"rating":{"value":9.0},"honor_infos":[]}
    ]}"#;
    let mock = mock.to_string();
    let req: ReqImpl = Arc::new(move |_url: &str, _opts: &str| (mock.clone(), "{}".to_string(), 200u16));

    let e = DrpyEngine::new(DIR, &rule, &engine, req).expect("创建 DrpyEngine");

    let cat = e.category("hot_gaia", "1", "{}").expect("category");
    println!("[category] 输出 {} 字节", cat.len());
    println!("{cat}");
    assert!(cat.contains("沙丘"), "应含沙丘");
    assert!(cat.contains("狂飙"), "应含狂飙");
    assert!(cat.contains("\"list\""), "应含 list");
    assert!(cat.contains("vod_name"), "应含 vod_name");
    println!("[PASS] category 用真实规则构造出列表");
}
