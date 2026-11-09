//! pdfh/pdfa/pd —— drpy HTML 解析宿主，对齐 fongmi 原版 Parser.java 语义。
//!
//! 表达式语法（drpy 生态约定）：
//!   - `正则:...`           → 正则提取（宽松扩展，原版 pdfh 为纯 jsoup）
//!   - `json:$...` / `$...`  → JsonPath 提取（JSON API 规则）
//!   - 其余                  → jsoup CSS / hiker 语法（原版 pdfh/pd/pdfa 的真实语义）
//!       - hiker 自动补 `:eq(0)`（无 `:eq/:lt/...` 等伪类时）
//!       - `:eq(N)` 索引（可为负）、`&&` 分段、多级空格选择器
//!       - `--sel` 排除（部分支持）
//!       - 末段 option：`Text` / `Html` / 属性名（`||` 分隔）
//!       - `pd(html, rule, urlKey)` 对属性做 joinUrl（相对地址拼基础 URL）
//!
//! 分发原则：带 `正则:` 走正则，JsonPath 走 JsonPath，其余走 jsoup（对齐原版）。

use regex::Regex;
use scraper::{ElementRef, Html, Selector};
use url::Url;

/// pdfh：从 html 按 rule 取第一个值（原版 parseDomForUrl，addUrl=""）。
pub fn pdfh(html: &str, rule: &str) -> String {
    let r = rule.trim();
    if r.is_empty() || html.is_empty() {
        return String::new();
    }
    if let Some(path) = strip_jsonpath(r) {
        return jsonpath_first(html, &path).unwrap_or_default();
    }
    if let Some(pat) = r.strip_prefix("正则:") {
        return regex_first(html, pat).unwrap_or_default();
    }
    parse_dom_for_url(html, r, "")
}

/// pd：同 pdfh，但 addUrl 参与 joinUrl（原版 parseDomForUrl(html, rule, urlKey)）。
pub fn pd(html: &str, rule: &str, url_key: &str) -> String {
    let r = rule.trim();
    if r.is_empty() || html.is_empty() {
        return String::new();
    }
    if let Some(path) = strip_jsonpath(r) {
        return jsonpath_first(html, &path).unwrap_or_default();
    }
    if let Some(pat) = r.strip_prefix("正则:") {
        return regex_first(html, pat).unwrap_or_default();
    }
    parse_dom_for_url(html, r, url_key)
}

/// pdfa：从 html 按 rule 取全部匹配值（原版 parseDomForArray，返回各元素 outerHtml）。
pub fn pdfa(html: &str, rule: &str) -> Vec<String> {
    let r = rule.trim();
    if r.is_empty() || html.is_empty() {
        return Vec::new();
    }
    if let Some(path) = strip_jsonpath(r) {
        return jsonpath_all(html, &path);
    }
    if let Some(pat) = r.strip_prefix("正则:") {
        return regex_all(html, pat);
    }
    parse_dom_for_array(html, r)
}

// ── jsoup（对齐 Parser.java）────────────────────────────────────

/// parseDomForUrl：jsoup 取单个值。
fn parse_dom_for_url(html: &str, rule: &str, add_url: &str) -> String {
    let doc = Html::parse_document(html);
    let r = rule.trim();
    if r == "body&&Text" || r == "Text" {
        return doc.root_element().text().collect::<String>();
    }
    if r == "body&&Html" || r == "Html" {
        return doc.root_element().html();
    }

    let (mut sel_rule, option) = split_option(r);
    sel_rule = parse_hiker_to_jq(&sel_rule, true);
    let Some(elements) = select_chain(&doc, &sel_rule) else { return String::new(); };
    let Some(first) = elements.first() else { return String::new() };

    if option.is_empty() {
        return first.html();
    }
    let o = option.as_str();
    if o == "Text" {
        return first.text().collect::<String>().trim().to_string();
    }
    if o == "Html" {
        return first.inner_html();
    }
    // 属性（`||` 分隔，逐个尝试，style 内 url() 提取 + joinUrl）
    for key in o.split("||") {
        let mut val = first.value().attr(key).unwrap_or("").to_string();
        if key.to_lowercase().contains("style") && val.contains("url(") {
            if let Some(m) = regex_first(&val, r"url\((.*?)\)") {
                val = m;
            }
            val = val.trim_matches(|c| c == '\'' || c == '"').to_string();
        }
        if !val.is_empty() && !add_url.is_empty() {
            if join_key(key) && !spec_url(&val) {
                val = join_url(add_url, &val);
            }
        }
        if !val.is_empty() {
            return val;
        }
    }
    String::new()
}

/// parseDomForArray：jsoup 取元素列表（outerHtml）。
fn parse_dom_for_array(html: &str, rule: &str) -> Vec<String> {
    let doc = Html::parse_document(html);
    let sel = parse_hiker_to_jq(rule.trim(), false);
    match select_chain(&doc, &sel) {
        Some(elements) => elements.iter().map(|e| e.html()).collect(),
        None => Vec::new(),
    }
}

/// `&&` 拆出末段 option；余下选择器以 `&&` 重连（对齐 Parser.parseDomForUrl）。
fn split_option(rule: &str) -> (String, String) {
    if rule.contains("&&") {
        let parts: Vec<&str> = rule.split("&&").collect();
        let option = parts.last().unwrap_or(&"").to_string();
        let sel = parts[..parts.len() - 1].join("&&");
        (sel, option)
    } else {
        (rule.to_string(), String::new())
    }
}

/// hiker → jq：非伪类末尾自动补 `:eq(0)`（对齐 parseHikerToJq）。
fn no_add(last: &str) -> bool {
    let t = last.trim();
    if t == "body" || t.starts_with('#') {
        return true;
    }
    // 原版 Parser 用 Pattern.find()：只要段里含伪类即不再自动补 :eq(0)
    const PSEUDO: [&str; 12] =
        [":eq", ":lt", ":gt", ":first", ":last", ":not", ":even", ":odd", ":has", ":contains", ":matches", ":empty"];
    PSEUDO.iter().any(|p| t.contains(p))
}

fn parse_hiker_to_jq(parse: &str, first: bool) -> String {
    if !parse.contains("&&") {
        let parts: Vec<&str> = parse.split_whitespace().collect();
        if let Some(last) = parts.last() {
            if !no_add(last) && first {
                return format!("{parse}:eq(0)");
            }
        }
        return parse.to_string();
    }
    let parses: Vec<&str> = parse.split("&&").collect();
    let mut items: Vec<String> = Vec::new();
    for (i, p) in parses.iter().enumerate() {
        let parts: Vec<&str> = p.split_whitespace().collect();
        let last = parts.last().copied().unwrap_or("");
        if no_add(last) {
            items.push(p.to_string());
        } else if !first && i >= parses.len() - 1 {
            items.push(p.to_string());
        } else {
            items.push(format!("{p}:eq(0)"));
        }
    }
    items.join(" ")
}

/// 逐级选择（空格链）；`:eq(N)` 从末尾提取为索引。返回最终元素集合。
fn select_chain<'a>(doc: &'a Html, sel_rule: &str) -> Option<Vec<ElementRef<'a>>> {
    let mut elems: Option<Vec<ElementRef<'a>>> = None;
    for parse in sel_rule.split_whitespace() {
        let (css, eq) = split_eq(parse);
        if css.is_empty() {
            continue;
        }
        let selector = Selector::parse(&css).ok()?;
        let selected: Vec<ElementRef<'a>> = match &elems {
            None => doc.select(&selector).collect(),
            Some(prev) => prev.iter().flat_map(|e| e.select(&selector)).collect(),
        };
        let selected = match eq {
            Some(idx) => eq_index(selected, idx),
            None => selected,
        };
        elems = Some(selected);
    }
    elems
}

/// 拆 `css:eq(N)` → (css, 索引)。
fn split_eq(parse: &str) -> (String, Option<i32>) {
    if let Some(pos) = parse.find(":eq(") {
        let base = parse[..pos].to_string();
        let inner = parse[pos..].trim_start_matches(":eq(").trim_end_matches(')');
        let idx: i32 = inner.parse().unwrap_or(0);
        (base, Some(idx))
    } else {
        (parse.to_string(), None)
    }
}

/// 取第 N（负为从尾数）个元素。
fn eq_index(mut elems: Vec<ElementRef<'_>>, idx: i32) -> Vec<ElementRef<'_>> {
    if elems.is_empty() {
        return elems;
    }
    let n = elems.len() as i32;
    let i = if idx < 0 { n + idx } else { idx };
    if i < 0 || i >= n {
        return Vec::new();
    }
    vec![elems.remove(i as usize)]
}

// ── joinUrl（对齐 Parser.JOIN_URL / SPEC_URL / UriUtil.resolve）──

fn join_key(key: &str) -> bool {
    let k = key.to_lowercase();
    k.ends_with("url") || k.ends_with("src") || k.ends_with("href")
        || k.ends_with("-original") || k.ends_with("-src") || k.ends_with("-play")
        || k.ends_with("-url") || k.ends_with("style")
        || k.starts_with("data-") || k.starts_with("url-") || k.starts_with("src-")
}

fn spec_url(val: &str) -> bool {
    let v = val.to_lowercase();
    ["ftp:", "magnet:", "thunder:", "ws:"].iter().any(|p| v.starts_with(p))
}

fn join_url(base: &str, val: &str) -> String {
    if val.contains("http") {
        if let Some(i) = val.find("http") {
            return val[i..].to_string();
        }
    }
    match Url::parse(base).and_then(|u| u.join(val)) {
        Ok(u) => u.to_string(),
        Err(_) => {
            // 宽松回退：相对路径直接拼
            if val.starts_with('/') {
                format!("{}{}", base.trim_end_matches('/'), val)
            } else {
                format!("{}/{}", base.trim_end_matches('/'), val)
            }
        }
    }
}

// ── JsonPath（原实现保留）───────────────────────────────────────

/// 提取 JsonPath 路径（`json:$...` 或 `$...`）。
fn strip_jsonpath(rule: &str) -> Option<String> {
    let r = rule.trim();
    let path = if let Some(p) = r.strip_prefix("json:") {
        p.trim()
    } else if r.starts_with('$') {
        r
    } else {
        return None;
    };
    let path = path.strip_prefix("$").unwrap_or(path).trim_start_matches('.');
    if path.is_empty() {
        None
    } else {
        Some(path.to_string())
    }
}

fn jsonpath_first(html: &str, path: &str) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(html).ok()?;
    let nodes = walk(&v, path);
    nodes.first().and_then(value_to_str)
}

fn jsonpath_all(html: &str, path: &str) -> Vec<String> {
    let v: serde_json::Value = match serde_json::from_str(html) {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    walk(&v, path).iter().filter_map(value_to_str).collect()
}

/// 按点路径遍历 JSON。
fn walk(v: &serde_json::Value, path: &str) -> Vec<serde_json::Value> {
    let mut cur = vec![v.clone()];
    for seg in path.split('.') {
        let seg = seg.trim();
        if seg.is_empty() {
            continue;
        }
        if let Some(bracket) = seg.find('[') {
            let key = seg[..bracket].trim();
            let mut next = Vec::new();
            for item in &cur {
                let mut pool = if key.is_empty() {
                    vec![item.clone()]
                } else {
                    match item.get(key) {
                        Some(x) => vec![x.clone()],
                        None => vec![],
                    }
                };
                for part in seg[bracket..].split('[').filter(|s| !s.is_empty()) {
                    let idx = part.trim().trim_end_matches(']');
                    if idx == "*" {
                        pool = pool
                            .into_iter()
                            .flat_map(|x| if let serde_json::Value::Array(a) = x { a } else { Vec::new() })
                            .collect();
                    } else if let Ok(i) = idx.parse::<usize>() {
                        pool = pool.into_iter().filter_map(|x| x.get(i).cloned()).collect();
                    } else {
                        return Vec::new();
                    }
                }
                next.extend(pool);
            }
            cur = next;
        } else if seg.is_empty() {
            continue;
        } else {
            let mut next = Vec::new();
            for item in &cur {
                if let Some(x) = item.get(seg) {
                    next.push(x.clone());
                }
            }
            cur = next;
        }
    }
    cur
}

fn value_to_str(v: &serde_json::Value) -> Option<String> {
    match v {
        serde_json::Value::String(s) => Some(s.clone()),
        serde_json::Value::Number(n) => Some(n.to_string()),
        serde_json::Value::Bool(b) => Some(b.to_string()),
        serde_json::Value::Null => None,
        _ => Some(v.to_string()),
    }
}

// ── 正则（宽松扩展，保留）────────────────────────────────────────

// 已编译正则的线程缓存。
// pdfh/pdfa 是按「每条规则 × 每个条目」调用的：一个列表页 100 条、每条约 4 个字段，
// 就是 400 次 `Regex::new`。编译一次模式在弱 CPU 上是几十微秒，400 次约几毫秒，
// 而且模式串就那几十个，重复编译完全是白烧。
// 规则集规模远小于 128，超了直接整体清空（不做 LRU，实现越简单越不会出错）。
thread_local! {
    static RE_CACHE: std::cell::RefCell<std::collections::HashMap<String, Regex>> =
        std::cell::RefCell::new(std::collections::HashMap::new());
}

fn with_regex<T>(pat: &str, f: impl FnOnce(&Regex) -> T) -> Option<T> {
    RE_CACHE.with(|c| {
        let mut m = c.borrow_mut();
        if !m.contains_key(pat) {
            if m.len() >= 128 {
                m.clear();
            }
            let re = Regex::new(pat).ok()?;
            m.insert(pat.to_string(), re);
        }
        let re = m.get(pat)?;
        Some(f(re))
    })
}

fn regex_first(html: &str, pat: &str) -> Option<String> {
    with_regex(pat, |re| -> Option<String> {
        let c = re.captures(html)?;
        Some(c.get(1).map_or(c.get(0)?.as_str(), |m| m.as_str()).to_string())
    })
    .flatten()
}

fn regex_all(html: &str, pat: &str) -> Vec<String> {
    with_regex(pat, |re| {
        re.captures_iter(html)
            .map(|c| c.get(1).map_or(c.get(0).map_or("", |m| m.as_str()), |m| m.as_str()).to_string())
            .collect::<Vec<String>>()
    })
    .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn regex_first_group() {
        assert_eq!(pdfh("a<b>hi</b>c", "正则:<b>(.*?)</b>"), "hi");
    }

    #[test]
    fn regex_all_groups() {
        assert_eq!(pdfa("a1 b2 a3", "正则:(a\\d)"), vec!["a1", "a3"]);
    }

    #[test]
    fn jsonpath_scalar() {
        let html = r#"{"data":{"list":[{"title":"x"},{"title":"y"}]}}"#;
        assert_eq!(pdfh(html, "json:$.data.list[0].title"), "x");
        assert_eq!(pdfa(html, "json:$.data.list[*].title"), vec!["x", "y"]);
    }

    #[test]
    fn jsoup_text_and_attr() {
        let html = r#"<ul><li><a href="/a" data-name="甲">one</a></li><li><a href="/b">two</a></li></ul>"#;
        // Text option（首个元素文本）
        assert_eq!(pdfh(html, "ul li a&&Text"), "one");
        // attr
        assert_eq!(pdfh(html, "ul li a&&href"), "/a");
        // hiker :eq 取第 2 个
        assert_eq!(pdfh(html, "ul li:eq(1) a&&Text"), "two");
        // Html option（inner html；属性顺序由解析器规范化，不做精确匹配）
        let html_inner = pdfh(html, "ul li:eq(0)&&Html");
        assert!(html_inner.contains("href=\"/a\"") && html_inner.contains(">one</a>"));
    }

    #[test]
    fn jsoup_pdfa_list() {
        let html = r#"<ul><li><a>x1</a></li><li><a>x2</a></li><li><a>x3</a></li></ul>"#;
        let items = pdfa(html, "ul li");
        assert_eq!(items.len(), 3);
        assert!(items[0].contains("x1"));
    }

    #[test]
    fn pd_join_url() {
        let html = r#"<img src="pic/1.jpg"><img src="https://cdn.example.com/2.jpg">"#;
        // 相对地址拼接页面 URL（视为文件，归到上级目录）
        assert_eq!(pd(html, "img&&src", "https://site.com/page.html"), "https://site.com/pic/1.jpg");
        // 已是完整 URL 且含 http → 原样取 http 起
        assert_eq!(pd(html, "img:eq(1)&&src", "https://site.com/"), "https://cdn.example.com/2.jpg");
    }

    #[test]
    fn pd_spec_url_not_joined() {
        let html = r#"<a href="magnet:?xt=abc">m</a>"#;
        // magnet 开头不 join
        assert_eq!(pd(html, "a&&href", "https://site.com/"), "magnet:?xt=abc");
    }
}
