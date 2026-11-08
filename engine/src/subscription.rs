//! 订阅解析（原 Kotlin `SubscriptionParser` 的下沉版）。
//!
//! 对应原项目 `com.github.catvod.api.Decoder` + `VodConfig.initSite`：
//! 订阅文本可能是三种形态，都要能吃：
//!   1. 多站点聚合 `{"sites":[...]}`（允许 `{"video":{"sites":[...]}}` 嵌套写法）
//!   2. 单站点配置 一个 JSON 对象
//!   3. 逐行 txt 每行一个站点 JSON
//!
//! 顺带把订阅里的相对地址（`./xxx`、`../xxx`）按订阅目录补全 ——
//! 这一步覆盖的不只是 spider/jar，还有站点 ext、lives、parses 里的相对地址
//! （原版 `Decoder.fix(url, data)` 是在 Json.parse 之前对整段文本做替换）。

use serde_json::{json, Value};

/// 订阅解析结果（一次性返回，壳侧不再各自解析）。
pub struct Subscription {
    pub sites: Vec<Value>,
    pub lives: Vec<Value>,
    pub spider: String,
    /// 嗅探规则（原版 bean/Rule：name/hosts/regex/script/exclude）
    pub rules: Vec<Value>,
    /// 广告域名/正则列表（原版 VodConfig.getAds）
    pub ads: Vec<String>,
    /// 解析源（原版 Parse 列表，ParseJob 按 type 分流）
    pub parses: Vec<Value>,
}

/// 把相对地址补成绝对地址。递归遍历所有字符串值，
/// 只处理以 `./` / `../` 开头的，避免误伤普通文本。
fn absolutize(v: &mut Value, dir: &str, parent: &str) {
    match v {
        Value::String(s) => {
            if s.starts_with("../") {
                *s = format!("{}{}", parent, &s[3..]);
            } else if s.starts_with("./") {
                *s = format!("{}{}", dir, &s[2..]);
            }
        }
        Value::Array(a) => {
            for x in a.iter_mut() {
                absolutize(x, dir, parent);
            }
        }
        Value::Object(o) => {
            for (_, x) in o.iter_mut() {
                absolutize(x, dir, parent);
            }
        }
        _ => {}
    }
}

fn dirs_of(subscribe_url: &str) -> (String, String) {
    if !subscribe_url.contains("://") {
        return (String::new(), String::new());
    }
    let dir = match subscribe_url.rfind('/') {
        Some(i) => format!("{}/", &subscribe_url[..i]),
        None => subscribe_url.to_string(),
    };
    let trimmed = dir.trim_end_matches('/');
    let parent = match trimmed.rfind('/') {
        Some(i) => format!("{}/", &trimmed[..i]),
        None => dir.clone(),
    };
    (dir, parent)
}

/// ext / header 归一化：对齐原版 gson 的 ExtAdapter ——
/// 字符串原样返回；对象/数组序列化成 JSON 文本；其余空串。
fn as_text_or_serialized(o: &Value, key: &str) -> String {
    match o.get(key) {
        Some(Value::String(s)) => s.clone(),
        Some(v @ Value::Object(_)) | Some(v @ Value::Array(_)) => v.to_string(),
        _ => String::new(),
    }
}

fn style_of(o: &Value) -> (String, f64) {
    let st = match o.get("style") {
        Some(Value::Object(m)) => m,
        _ => return (String::new(), 0.0),
    };
    let ty = match st.get("type") {
        Some(Value::String(s)) if !s.is_empty() => s.clone(),
        _ => "rect".to_string(),
    };
    let ratio = st.get("ratio").and_then(|x| x.as_f64()).unwrap_or(0.0);
    (ty, ratio)
}

/// 单站点 → 标准 Site 结构（字段名与壳侧 `Site` 一一对应）。
fn site_from(o: &Value, index: usize, default_jar: &str) -> Option<Value> {
    let obj = o.as_object()?;
    let key = obj
        .get("key")
        .and_then(|x| x.as_str())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("site{}", index));
    let name = obj
        .get("name")
        .and_then(|x| x.as_str())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .unwrap_or_else(|| key.clone());
    let api = obj.get("api").and_then(|x| x.as_str()).unwrap_or("").to_string();

    // ext：字符串原样 / 对象数组序列化。对象型 ext（苹果站等加密站）若被 optString 成空串，
    // spider.init 拿不到 appName/publicKey/dataKey，表现为首页有内容、分类页缺字段（海报空白）。
    let ext = as_text_or_serialized(o, "ext");

    // csp_ 站点自带 jar（订阅内若有 site.jar 优先，否则用订阅顶层 spider）
    let own_jar = obj.get("jar").and_then(|x| x.as_str()).unwrap_or("");
    let jar = if own_jar.is_empty() {
        if api.starts_with("csp_") { default_jar.to_string() } else { String::new() }
    } else {
        own_jar.to_string()
    };

    let (style_type, style_ratio) = style_of(o);

    // type：显式优先；缺省按 api 形态推断，避免被当成 0（XML）解析导致整站空。
    let site_type = match obj.get("type").and_then(|x| x.as_i64()) {
        Some(t) => t,
        None => {
            if api.starts_with("csp_") || api.contains(".js") { 3 } else { 1 }
        }
    };

    let searchable = obj.get("searchable").and_then(|x| x.as_i64()).unwrap_or(1);
    let changeable = obj.get("changeable").and_then(|x| x.as_i64()).unwrap_or(1);

    Some(json!({
        "key": key,
        "name": name,
        "api": api,
        "ext": ext,
        "siteJson": o.to_string(),
        "jar": jar,
        "styleType": style_type,
        "styleRatio": style_ratio,
        "headerJson": as_text_or_serialized(o, "header"),
        "siteType": site_type,
        "searchable": searchable,
        "changeable": changeable,
        "categoriesJson": match o.get("categories") {
            Some(v @ Value::Array(_)) => v.to_string(),
            _ => String::new(),
        },
    }))
}

/// 解析订阅文本。`default_jar` 是订阅顶层 spider 解析出的 jar 完整 URL。
pub fn parse(text: &str, default_jar: &str, subscribe_url: &str) -> Subscription {
    let mut result = Subscription {
        sites: Vec::new(),
        lives: Vec::new(),
        spider: String::new(),
        rules: Vec::new(),
        ads: Vec::new(),
        parses: Vec::new(),
    };
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return result;
    }

    let (dir, parent) = dirs_of(subscribe_url);

    // 整体 JSON 路径
    if let Ok(mut v) = serde_json::from_str::<Value>(trimmed) {
        absolutize(&mut v, &dir, &parent);

        // 顶层 spider（csp_ 源需要）：顶层优先，其次 video 内
        result.spider = v
            .get("spider")
            .and_then(|x| x.as_str())
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string())
            .or_else(|| {
                v.get("video")
                    .and_then(|x| x.get("spider"))
                    .and_then(|x| x.as_str())
                    .filter(|s| !s.is_empty())
                    .map(|s| s.to_string())
            })
            .unwrap_or_default();

        // lives：直播主页源（原版 LiveConfig 的数据来源）
        if let Some(Value::Array(arr)) = v.get("lives") {
            for item in arr {
                let name = item.get("name").and_then(|x| x.as_str()).unwrap_or("");
                let url = item.get("url").and_then(|x| x.as_str()).unwrap_or("");
                if name.is_empty() || url.is_empty() {
                    continue;
                }
                result.lives.push(json!({
                    "name": name,
                    "url": url,
                    "ua": item.get("ua").and_then(|x| x.as_str()).unwrap_or(""),
                    // 原版 Live.epg：直播源自带的 XMLTV 地址，是「节目单」功能的数据源。
                    // 之前这里只留 name/url/ua，把 epg 丢了 —— 壳侧节目单按钮永远提示未配置。
                    "epg": item.get("epg").and_then(|x| x.as_str()).unwrap_or(""),
                }));
            }
        } else if let Some(Value::Array(arr)) = v.get("video").and_then(|x| x.get("lives")) {
            for item in arr {
                let name = item.get("name").and_then(|x| x.as_str()).unwrap_or("");
                let url = item.get("url").and_then(|x| x.as_str()).unwrap_or("");
                if name.is_empty() || url.is_empty() {
                    continue;
                }
                result.lives.push(json!({
                    "name": name,
                    "url": url,
                    "ua": item.get("ua").and_then(|x| x.as_str()).unwrap_or(""),
                    // 原版 Live.epg：直播源自带的 XMLTV 地址，是「节目单」功能的数据源。
                    // 之前这里只留 name/url/ua，把 epg 丢了 —— 壳侧节目单按钮永远提示未配置。
                    "epg": item.get("epg").and_then(|x| x.as_str()).unwrap_or(""),
                }));
            }
        }

        // rules / ads：嗅探判定依赖（原版 VodConfig.getRules/getAds）。
        // holder 的取法与 Kotlin 版一致：root.video 存在且含 sites 时用它，否则用顶层。
        {
            let holder = if v.get("video").map(|x| x.get("sites").is_some()).unwrap_or(false) {
                v.get("video").unwrap()
            } else {
                &v
            };
            if let Some(Value::Array(arr)) = holder.get("rules") {
                for item in arr {
                    let name = item.get("name").and_then(|x| x.as_str()).unwrap_or("").to_string();
                    let list = |k: &str| -> Vec<String> {
                        match item.get(k) {
                            Some(Value::Array(a)) => a
                                .iter()
                                .filter_map(|x| x.as_str())
                                .filter(|s| !s.is_empty())
                                .map(|s| s.to_string())
                                .collect(),
                            _ => Vec::new(),
                        }
                    };
                    let hosts = list("hosts");
                    // 原版逻辑：没有 hosts 的规则不参与匹配，直接丢弃
                    if hosts.is_empty() {
                        continue;
                    }
                    result.rules.push(json!({
                        "name": name,
                        "hosts": hosts,
                        "regex": list("regex"),
                        "script": list("script"),
                        "exclude": list("exclude"),
                    }));
                }
            }
            if let Some(Value::Array(arr)) = holder.get("ads") {
                for item in arr {
                    if let Some(s) = item.as_str() {
                        if !s.is_empty() {
                            result.ads.push(s.to_string());
                        }
                    }
                }
            }
        }

        // parses：解析源。holder 取法与 Kotlin 版一致（video 含 parses 时用它）。
        {
            let holder = if v.get("video").map(|x| x.get("parses").is_some()).unwrap_or(false) {
                v.get("video").unwrap()
            } else {
                &v
            };
            if let Some(Value::Array(arr)) = holder.get("parses") {
                for item in arr {
                    let ty = item.get("type").and_then(|x| x.as_i64()).unwrap_or(0);
                    let name = item.get("name").and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
                    let url = item.get("url").and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
                    if name.is_empty() || url.is_empty() {
                        continue;
                    }
                    // type0/1 必须是 http 前缀；type2/3 的 url 是 jar 内类名后缀，不要求 http
                    if (ty == 0 || ty == 1) && !url.starts_with("http") {
                        continue;
                    }
                    // ext 里可能带 {"header":{...}}，也可能整体是字符串
                    let mut headers = serde_json::Map::new();
                    if let Some(Value::Object(ext)) = item.get("ext") {
                        if let Some(Value::Object(h)) = ext.get("header") {
                            for (k, val) in h {
                                if let Some(sv) = val.as_str() {
                                    if !sv.is_empty() {
                                        headers.insert(k.clone(), json!(sv));
                                    }
                                }
                            }
                        }
                    }
                    let ext_json = match item.get("ext") {
                        Some(v2 @ Value::Object(_)) => v2.to_string(),
                        Some(Value::String(s2)) => s2.clone(),
                        _ => String::new(),
                    };
                    result.parses.push(json!({
                        "name": name,
                        "type": ty,
                        "url": url,
                        "click": item.get("click").and_then(|x| x.as_str()).unwrap_or(""),
                        "headers": headers,
                        "extJson": ext_json,
                    }));
                }
            }
        }

        // 顶层已解析出 spider 时，csp_ 站点直接继承它 —— 壳侧不用再单独调一次 parseJar。
        let default_jar: String = if default_jar.is_empty() { result.spider.clone() } else { default_jar.to_string() };

        // sites：允许 {"video":{"sites":[...]}} 嵌套，否则用顶层
        let holder = if v.get("video").map(|x| x.get("sites").is_some()).unwrap_or(false) {
            v.get("video").unwrap()
        } else {
            &v
        };
        if let Some(Value::Array(arr)) = holder.get("sites") {
            for (i, item) in arr.iter().enumerate() {
                if let Some(s) = site_from(item, i, &default_jar) {
                    result.sites.push(s);
                }
            }
            return result;
        }
        // 单站点配置
        if v.get("key").is_some() || v.get("name").is_some() {
            if let Some(s) = site_from(&v, 0, &default_jar) {
                result.sites.push(s);
            }
        }
        return result;
    }

    // 逐行 txt 兜底
    let mut idx = 0usize;
    for line in trimmed.lines() {
        let l = line.trim();
        if !l.starts_with('{') {
            continue;
        }
        if let Ok(mut v) = serde_json::from_str::<Value>(l) {
            absolutize(&mut v, &dir, &parent);
            if let Some(s) = site_from(&v, idx, default_jar) {
                result.sites.push(s);
                idx += 1;
            }
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn real_subscription() {
        let text = std::fs::read_to_string("/tmp/jsm.json").expect("读不到 /tmp/jsm.json");
        let url = "https://raw.githubusercontent.com/qist/tvbox/master/jsm.json";
        let r = parse(&text, "", url);
        println!("sites={} lives={} spider={:?}", r.sites.len(), r.lives.len(), r.spider);
        for l in &r.lives {
            println!("  live {} -> {}", l["name"], l["url"]);
        }
        // 抽查一个 csp 站的字段是否齐全
        if let Some(s) = r.sites.iter().find(|x| x["api"].as_str().unwrap_or("").starts_with("csp_")) {
            println!("csp sample: {}", serde_json::to_string(s).unwrap());
        }
        assert!(r.sites.len() > 100, "站点数异常: {}", r.sites.len());
        assert_eq!(r.lives.len(), 3, "lives 条数异常");
        println!("rules={} ads={} parses={}", r.rules.len(), r.ads.len(), r.parses.len());
        if let Some(x) = r.rules.first() {
            println!("rule0: {}", x);
        }
        assert!(r.rules.len() >= 15, "rules 太少: {}", r.rules.len());
        assert!(r.ads.len() >= 30, "ads 太少: {}", r.ads.len());
        assert!(r.parses.len() >= 25, "parses 太少: {}", r.parses.len());
        let t0 = r.parses.iter().filter(|x| x["type"] == 0).count();
        println!("parses type0={}", t0);
    }

    #[test]
    fn absolutize_relative() {
        let text = r#"{"sites":[],"lives":[{"name":"ipv6","url":"./tvboxtv.txt"}],"spider":"./jar/spider.jar;md5;abc"}"#;
        let url = "https://example.com/dir/sub/tvbox.json";
        let r = parse(text, "", url);
        assert_eq!(r.lives[0]["url"], "https://example.com/dir/sub/tvboxtv.txt");
        assert_eq!(r.spider, "https://example.com/dir/sub/jar/spider.jar;md5;abc");
    }

    #[test]
    fn nested_video_and_txt() {
        let nested = r#"{"video":{"sites":[{"key":"a","name":"A","api":"csp_A"}],"spider":"./s.jar"}}"#;
        let r = parse(nested, "", "https://x.com/a/b.json");
        assert_eq!(r.sites.len(), 1);
        assert_eq!(r.sites[0]["siteType"], 3, "csp_ 站应推断为 type=3");
        assert_eq!(r.sites[0]["jar"], "https://x.com/a/s.jar", "csp_ 站应自动继承顶层 spider（无需壳再传 default_jar）");

        let txt = "{\"key\":\"b\",\"name\":\"B\",\"api\":\"http://y/api\"}\n{\"key\":\"c\",\"name\":\"C\",\"api\":\"z.js\"}\n";
        let r2 = parse(&txt, "", "https://x.com/a/b.json");
        assert_eq!(r2.sites.len(), 2, "逐行 txt 应解析出 2 站");
        assert_eq!(r2.sites[1]["siteType"], 3, "含 .js 应推断 type=3");
    }
}
