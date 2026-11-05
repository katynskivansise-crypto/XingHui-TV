//! TV 重构方案A — Rust 引擎层
//!
//! 对应原仓库 `catvod` 模块，职责：
//!   - net     : 网络层（原 OkHttp.java，30s 超时 / 自定义 header / 绕过证书 / 代理）
//!   - json    : JSON 工具（原 Json.java，容错读取）
//!   - spider  : 爬虫执行器抽象（原 Spider.java）
//!   - js_spider : 原样执行 CatVod JS 规则的执行器（原 quickjs 模块）
//!   - drpy_engine : 原样执行 drpy 格式订阅源（drpy2 引擎 + rule）的执行器
//!   - proxy   : 本地代理配置（原 Proxy.java，9978）
//!   - jni     : 到安卓侧（Java/Kotlin）的桥，feature "jni" 启用
//!
//! 设计原则：与 CatVod 生态的 Spider 接口一一对应，保证将来可直接替换原
//! 引擎，而不必改动上层（app 的 api/loader）调用契约。

pub mod api_spider;
pub mod drpy_engine;
pub mod drpy_spider;
pub mod json;
pub mod json_spider;
pub mod js_spider;
pub mod kv;
pub mod live_parse;
pub mod live_spider;
pub mod net;
pub mod pdfh;
pub mod proxy;
pub mod spider;
pub mod epg;

#[cfg(feature = "jni")]
pub mod jni;
