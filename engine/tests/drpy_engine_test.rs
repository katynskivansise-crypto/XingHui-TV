//! DrpyEngine 集成测试：加载真实 drpy 订阅源素材，验证输出 CatVod JSON。
//! 素材不存在时跳过（沙盒/本地需先下载 qist-rule/ 下 drpy.js 与 drpy2.min.js）。

use std::path::Path;
use std::sync::Arc;

use tv_engine::drpy_engine::{DrpyEngine, ReqImpl};
use tv_engine::net::Net;

const DIR: &str = "../samples/rules";

fn have_materials() -> bool {
    Path::new(&format!("{DIR}/drpy.js")).exists() && Path::new(&format!("{DIR}/drpy2.min.js")).exists()
}

fn load() -> (String, String) {
    let rule = std::fs::read_to_string(format!("{DIR}/drpy.js")).unwrap();
    let engine = std::fs::read_to_string(format!("{DIR}/drpy2.min.js")).unwrap();
    (rule, engine)
}

fn net_req() -> ReqImpl {
    let net = Arc::new(Net::new());
    Arc::new(move |url: &str| net.string(url))
}

#[test]
fn real_rule_home_returns_catvod_json() {
    if !have_materials() {
        eprintln!("跳过：真实 drpy 素材不存在");
        return;
    }
    let (rule, engine) = load();
    let e = DrpyEngine::new(DIR, &rule, &engine, net_req()).unwrap();

    let home = e.home().unwrap();
    assert!(home.contains("\"class\""), "home 应含 class");
    assert!(home.contains("\"filters\""), "home 应含 filters");
    assert!(home.contains("豆瓣"), "home 应含规则域名特征(豆瓣)");
}

#[test]
fn real_rule_category_builds_list_with_mock_req() {
    if !have_materials() {
        return;
    }
    let (rule, engine) = load();
    // mock 豆瓣 API 返回 items，验证真实规则 js: 表达式能构造列表
    let mock = r#"{"total":2,"items":[
      {"id":"a1","type":"movie","title":"沙丘","pic":{"normal":"http://p1/s.jpg"},"rating":{"value":8.3},"honor_infos":[{"title":"榜"}]},
      {"id":"b2","type":"tv","title":"狂飙","pic":{"normal":"http://p2/k.jpg"},"rating":{"value":9.0},"honor_infos":[]}
    ]}"#.to_string();
    let req: ReqImpl = Arc::new(move |_url: &str| mock.clone());
    let e = DrpyEngine::new(DIR, &rule, &engine, req).unwrap();

    let cat = e.category("hot_gaia", "1", "").unwrap();
    assert!(cat.contains("沙丘"), "应含沙丘");
    assert!(cat.contains("狂飙"), "应含狂飙");
    assert!(cat.contains("\"list\""), "应含 list");
    assert!(cat.contains("vod_name"), "应含 vod_name");
}

#[test]
fn engine_exports_have_core_methods() {
    if !have_materials() {
        return;
    }
    let (rule, engine) = load();
    let e = DrpyEngine::new(DIR, &rule, &engine, net_req()).unwrap();
    let home = e.home().unwrap();
    assert!(!home.is_empty());
}
