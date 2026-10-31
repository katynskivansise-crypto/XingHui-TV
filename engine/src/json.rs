//! JSON 工具 —— 对应原 `catvod/src/main/java/com/github/catvod/utils/Json.java`
//!
//! 原 Java 基于 Gson，提供"取不到就返回空/安全值"的容错语义。
//! 这里用 serde_json 实现等价 API。

use serde_json::{Map, Value};

/// 解析字符串为 JSON 值；失败时返回 Null（原 Java：解析失败返回空对象/异常兜底）。
pub fn parse(text: &str) -> Value {
    serde_json::from_str(text).unwrap_or(Value::Null)
}

/// 是否是合法 JSON 对象/数组（原 `valid`）。
pub fn valid(text: &str) -> bool {
    serde_json::from_str::<Value>(text).is_ok()
}

/// 是否非法 JSON（原 `invalid`）。
pub fn invalid(text: &str) -> bool {
    !valid(text)
}

/// 取字符串字段，取不到或非字符串返回空串（原 `safeString`）。
pub fn safe_string(obj: &Map<String, Value>, key: &str) -> String {
    obj.get(key)
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_string())
        .unwrap_or_default()
}

/// 取字符串数组字段：对象转为单元素、数组逐元素取字符串（原 `safeListString`）。
pub fn safe_list_string(obj: &Map<String, Value>, key: &str) -> Vec<String> {
    let Some(v) = obj.get(key) else {
        return Vec::new();
    };
    match v {
        Value::Array(arr) => arr.iter().filter_map(|e| e.as_str().map(String::from)).collect(),
        Value::Object(_) => vec![safe_string(obj, key)],
        Value::String(s) => vec![s.clone()],
        _ => Vec::new(),
    }
}

/// 取元素数组字段：对象转单元素、数组逐元素取（原 `safeListElement`）。
pub fn safe_list_element(obj: &Map<String, Value>, key: &str) -> Vec<Value> {
    let Some(v) = obj.get(key) else {
        return Vec::new();
    };
    match v {
        Value::Array(arr) => arr.clone(),
        Value::Object(_) => vec![v.clone()],
        _ => Vec::new(),
    }
}

/// 把 Value 归一化为对象；原 Java 中当 element 是 JSON 字符串时会二次解析。
/// 这里对 Value::String 再尝试解析为对象。
pub fn safe_object(element: &Value) -> Value {
    match element {
        Value::Object(_) => element.clone(),
        Value::String(s) => serde_json::from_str(s).unwrap_or(Value::Object(Map::new())),
        _ => Value::Object(Map::new()),
    }
}

/// 把 JSON 对象拍平为 String->String 的 map（原 `toMap`，用于 header/extend 参数）。
pub fn to_map(element: &Value) -> std::collections::HashMap<String, String> {
    let mut map = std::collections::HashMap::new();
    if let Some(obj) = safe_object(element).as_object() {
        for (k, v) in obj {
            let s = match v {
                Value::String(x) => x.clone(),
                Value::Number(n) => n.to_string(),
                Value::Bool(b) => b.to_string(),
                _ => String::new(),
            };
            map.insert(k.clone(), s);
        }
    }
    map
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_string_defaults_to_empty() {
        let v: Map<String, Value> = serde_json::from_str(r#"{"a":" x ","b":3,"c":null}"#).unwrap();
        assert_eq!(safe_string(&v, "a"), "x");
        assert_eq!(safe_string(&v, "missing"), "");
        assert_eq!(safe_string(&v, "b"), "");
    }

    #[test]
    fn safe_list_string_handles_single_and_array() {
        let single: Map<String, Value> = serde_json::from_str(r#"{"ids":"abc"}"#).unwrap();
        let arr: Map<String, Value> = serde_json::from_str(r#"{"ids":["a","b","c"]}"#).unwrap();
        assert_eq!(safe_list_string(&single, "ids"), vec!["abc"]);
        assert_eq!(safe_list_string(&arr, "ids"), vec!["a", "b", "c"]);
        assert_eq!(safe_list_string(&arr, "nope"), Vec::<String>::new());
    }

    #[test]
    fn valid_and_invalid() {
        assert!(valid(r#"{"a":1}"#));
        assert!(!valid("not json"));
    }
}
