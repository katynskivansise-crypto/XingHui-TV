# Rust 引擎 — Spider/JS 规则执行 完整方案

> 目标：让 Rust 引擎**原样执行 CatVod 的 JS 爬虫规则**，做到 100% 兼容原生态。
> 这是整个重构能否"功能 100% 移植"的决定性环节。

---

## 1. 为什么必须执行 JS 规则

原 `okcaptain/TV`（CatVod 系）的核心机制：**各站点的解析逻辑不是写死在 App 里，
而是以 JS 脚本（订阅源/规则）形式下发**，App 用内嵌 quickjs 引擎加载执行。

也就是说，网站的更新/破解/源维护全部靠 JS 规则完成。若不执行 JS 规则，
就等于丢掉整个内容生态——这就是必须用 quickjs-rs 嵌入 JS 引擎的原因。

## 2. JS 规则的标准形态（okcaptain 风格）

一个规则是一个 JS 脚本，顶层定义 `rule` 配置对象 + 若干内容函数：

```js
var rule = {
  host: "example.com",
  headers: { "User-Agent": "Mozilla/5.0" },
};

function homeContent(filter) {
  // 返回 JSON 字符串：{class:[...], list:[...]}
  return JSON.stringify({ class: [], list: [] });
}
function categoryContent(tid, pg, filter, extend) { ... }
function detailContent(ids) { ... }            // ids: 数组
function searchContent(key, quick) { ... }
function playerContent(flag, id, vipFlags) { ... }
function liveContent() { ... }
```

> 不同源版本字段名略有差异（有的用 `var homeContent = function(){}`），
> 执行层按"顶层同名函数"取用，兼容两种写法。

## 3. 技术选型：rquickjs

| 候选 | 说明 | 结论 |
|---|---|---|
| `rquickjs` | 基于 **quickjs-ng**（与原项目 quickjs 同源），最活跃，API 现代 | ✅ 选用 |
| `quickjs`(theduke) | 老牌绑定，活跃度低 | 备选 |
| `boa_engine` | 纯 Rust 引擎，不依赖 C | 与原 quickjs 语义差异大，不选 |

quickjs-ng 与原项目使用的 quickjs 同源，JS 语义一致，可保证规则行为不偏差。
编译需要 `cc` + `cmake`（沙盒已具备）。

## 4. 架构：`JsSpider` 实现 `Spider` trait

```
                     ┌────────────────────────────────────┐
  配置(订阅源) ────► │  JsSpider                          │
  JS 规则脚本         │  · Runtime + Context (quickjs-ng) │
                     │  · eval 规则脚本                   │
                     │  · 注入宿主网络 _cat_http          │
                     └──────────┬─────────────────────────┘
                                │ 实现 Spider trait
                                ▼
              home_content / category / detail / search / player / live
                                │  每个方法调用对应 JS 顶层函数
                                ▼
                     返回 CatVod JSON 字符串 → 上层 app 直接消费
```

**方法映射**（一一对应，契约不变）：

| Spider trait (Rust) | JS 顶层函数 | 入参 |
|---|---|---|
| `home_content(filter)` | `homeContent(filter)` | bool |
| `category_content(tid,pg,filter,extend)` | `categoryContent(...)` | string×2, bool, object |
| `detail_content(ids)` | `detailContent(ids)` | array |
| `search_content(key,quick,pg)` | `searchContent(key,quick)` | string, bool |
| `player_content(flag,id,vip)` | `playerContent(flag,id,vipFlags)` | string×2, array |
| `live_content()` | `liveContent()` | — |

## 5. 宿主网络注入（关键）

JS 规则需要发 HTTP 请求（拉列表、解析详情）。原 quickjs 通过宿主对象提供网络能力。
Rust 侧用 `rquickjs::Function` 注册宿主函数到 JS 全局：

```
_cat_http(method, url, headersJson) -> body字符串
```

该函数内部走 Rust 引擎的 `net::Net`（带自定义 header / UA / referer / 绕证书），
即 JS 规则的网络请求实际由 Rust 高性能网络层完成。可扩展注入：
`_cat_proxy(url)`（走 9978 代理）、`_cat_cache(key,value)` 等。

## 6. 线程与生命周期约束

- `rquickjs::Context` **不是 Send**：必须在创建它的线程内使用。
- 实现：`JsSpider` 持有 `Runtime + Context`，所有调用在 `ctx.with()` 作用域内完成；
  上层用 `SpiderManager` 单线程调度（或每个规则一个专用线程），不跨线程传 Context。
- `destroy()` 时释放 Runtime，回收 JS 引擎资源。

## 7. 错误处理

- 脚本加载失败 / JS 抛异常 → 返回 `Err(String)`，上层记为"该源暂不可用"。
- JS 返回非字符串 → 通过宿主 `JSON.stringify` 序列化后返回（兼容两种返回风格）。
- 单个源失败不影响其它源（SpiderManager 隔离）。

## 8. 落地清单（本仓库已实现）

- [x] `Spider` trait + `SpiderManager`（`src/spider.rs`）
- [x] 网络层 `Net`（`src/net.rs`）供宿主函数复用
- [x] `JsSpider`（`src/js_spider.rs`）：quickjs 运行时 + 加载规则 + 注入宿主网络 + 方法调用
- [x] 样例 JS 规则 + 6 个单元测试（home/category/detail/search/player/live 全部执行通过）
- [ ] 安卓集成：cargo-ndk 交叉编译（.so），Java 侧传规则文本给引擎
- [ ] 订阅源配置解析（多规则注册进 SpiderManager）

## 9. 兼容性边界

- 若个别规则用到原 App 注入的私有宿主对象（非标准 `_cat_http`），需按其源码逐一对齐注入；
  绝大多数公开规则只依赖标准 JS + 网络，可直接运行。
- 规则里引用外部依赖（如 `jsdelivr` 拉包）需规则本身支持，与引擎无关。
