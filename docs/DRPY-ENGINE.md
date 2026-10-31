# drpy 规则执行器 —— 完整设计（接真实订阅源）

> 用你提供的真实订阅源 `t3.json` 实测后，确认了 CatVod 生态当前主流规则的
> 真实形态是 **drpy 格式**。本文档是让 Rust 引擎原样执行真实订阅源的完整方案。

---

## 1. 实测发现（基于 t3.json 真实链路）

```
t3.json (聚合列表)
  └─ t.json (线路配置, 宽松JSON含//注释, 20KB)
       ├─ sites[] 每条: {key, name, type:3, api, ext}
       ├─ 例: api=./lib/apiv43Api.js  +  ext=./lib/Doubanrebo.js
       └─ lib/ 下有:
            ├─ apiv43Api.js  (drpy2 引擎, 39KB, ES module)
            ├─ qyg0.js       (cheerio 打包, 356KB, UMD)
            ├─ qyg1.js       (CommonJS/UMD 工具)
            ├─ qyg2.js       (模板)
            ├─ qyg3.js       (gbk 转码)
            └─ Doubanrebo.js (具体站点 rule, 13KB, 纯数据对象)
```

**关键结论**：真实规则不是"函数式"（直接定义 `homeContent` 等），而是：
- `Doubanrebo.js` 只定义 `rule` **数据对象**（host/headers/url 模板/分类/filter）
- `apiv43Api.js` 是**解释引擎**，读 `rule` 生成 `init/home/category/detail/play/search`
- 依赖 cheerio（`qyg0.js`）做 DOM 解析，且用 **ES module `import`** 加载

## 2. 两种规则格式对照

| | 函数式规则（当前 JsSpider 已支持） | drpy 规则（真实订阅源） |
|---|---|---|
| 形态 | 顶层定义 `homeContent` 等函数 | 定义 `rule` 数据对象 + 由引擎解释 |
| 依赖 | 仅宿主 fetch | cheerio + 多模块 + ES module |
| 已验证 | ✅ 6 测试通过 | ❌ 需新增执行器 |
| 生态占比 | 少量旧源 | **主流** |

## 3. drpy 执行器架构

```
                         ┌──────────────────────────────────────────┐
  订阅源(规则文本) ───►  │  DrpyEngine                             │
                         │                                          │
                         │  ① 模块加载器(rquickjs Loader)           │
                         │     import "./qyg0.js" → cheerio 等      │
                         │                                          │
                         │  ② 宿主 API 注入                         │
                         │     fetch / print / log / Base64         │
                         │                                          │
                         │  ③ 加载 rule (Doubanrebo.js) → 全局 rule │
                         │                                          │
                         │  ④ 加载 apiv43Api.js → 引擎对象          │
                         │     {init,home,category,detail,play,search}│
                         └──────────────┬───────────────────────────┘
                                        │ 接口映射
                                        ▼
              Spider trait: home_content/category_content/detail_content/
                            search_content/player_content
                            （映射到 engine.home/category/detail/play/search）
```

## 4. 关键技术点

### 4.1 模块加载器（rquickjs Loader）
rquickjs 0.7 提供 `Loader` / module 支持。需实现一个加载器：遇到
`import cheerio from "./qyg0.js"` 时，从文件系统/内存取对应模块求值。
难点：**qyg0.js 是 UMD bundle，apiv43Api 用 `import default`** —— 需处理
"默认导出取 bundle 暴露的对象"（e.g. cheerio）的互操作。

### 4.2 宿主 API 注入
从 apiv43Api.js 统计：`print`(31) / `fetch`(3) / `Base64`(3) / `require`(1)，另有
`log`、`console`。注入清单：
- `print(str)` → 映射 Rust 侧日志（原 CatVod 的 print）
- `fetch(url, options)` → 走 Rust `net::Net`；CatVod 语义返回 `{status, headers, body}`；
  需确认同步 or Promise（drpy 用同步式 fetch 居多）
- `Base64.encode/decode` → JS 或注入实现
- `console.*` → 映射到 print
- `require` → 与模块加载器配合

### 4.3 rule 合并
顺序：先 eval `Doubanrebo.js`（设置全局 `rule`）→ 再 eval `apiv43Api.js`
（引擎内 `let rule={}` 会重置？需确认 —— CatVod 实际是先加载 rule 再加载引擎，
或引擎内 `rule` 是 `var` 指向全局）。需按 CatVod 实际加载顺序对齐。

### 4.4 接口映射
```
Spider::home_content     → engine.home()
Spider::category_content → engine.category(tid,pg,filter,extend)
Spider::detail_content   → engine.detail(ids[0])
Spider::search_content   → engine.search(key,quick)
Spider::player_content   → engine.play(flag,id,vip)
```
注意 drpy 函数名与 CatVod 标准接口不同，需 wrapper 层映射。

## 5. 实施步骤

1. **rquickjs module loader 验证**：加载 `qyg0.js`(cheerio)，确认 `import default` 拿到对象
2. **宿主注入层**：`fetch/print/log/Base64`，用真实豆瓣规则跑通 `home()`
3. **rule 加载顺序** 对齐 CatVod
4. **接口映射** 到 Spider trait
5. **用真实规则端到端验证**（Doubanrebo 豆瓣热播）

## 6. 风险与备选

- **模块互操作**（UMD/CommonJS/ES module 混合）是主要风险；备选：改写 apiv43Api
  的 import 为直接 eval（不依赖 ES module loader），先跑通引擎
- **cheerio 兼容**：bundle 的 cheerio 依赖浏览器全局（window/document）；CatVod 宿主
  会垫一些环境。需注入最小 DOM 垫片或验证 qyg0 自包含
- **备选方案**：直接复用原 `quickjs` 安卓 so + 原 CatVod 的 JS 宿主层（Java 那套），
  Rust 只做结果转发 —— 工作量大减但 Rust 层"执行引擎"价值降低

## 7. 当前状态

- [x] 下载并分析真实订阅源链路（t3.json → t.json → lib/*）
- [x] 确认规则格式为 drpy，统计宿主 API 依赖
- [x] 本仓库已支持"函数式规则"（JsSpider，6 测试通过）
- [x] **核心技术赌注验证通过（spike）**：rquickjs 以 **module 模式**成功加载真实
      `drpy-core-lite.min.js`（webpack/cheerio bundle，导出 `cheerio`/`模板` 对象）和
      `drpy2.min.js`（含 `import {cheerio} from "./..."`）。关键点：必须用
      `Module::declare/eval`（module 模式）而非 `eval`（script 模式不支持 import/export），
      并用自定义 `Resolver` 处理 `./` 相对路径 + 绝对路径拼接
- [x] **DrpyEngine 主链路落地（Rust）**：`src/drpy_engine.rs` 原样执行真实 drpy 规则——
      宿主经 `globalThis` 注入 → 规则脚本设全局 `rule` → 引擎以 module 模式加载 →
      `init(rule)` → `home()/category()/detail()/play()/search()`。
- [x] **pdfh/pdfa/pd HTML 解析宿主**：`src/pdfh.rs` 实现正则 + JsonPath（含 `[*]` 通配）。
- [x] **category 真实列表构造跑通**：真实 drpy 规则（drpy.js）的 `js:` 一级表达式，
      经 `req` 宿主（返回 `{content,headers}`）完整构造出 CatVod 列表（vod_name/vod_pic/
      vod_remarks，含图片来源拼接）。集成测试 `tests/drpy_engine_test.rs`（3 例，home/
      category-mock/导出）与 `src/pdfh.rs`（3 例）全绿，lib+集成共 18 测试通过。
- [x] **req 宿主协议确认**（实测）：引擎 `request` 调宿主 `req(url,obj)`，取 `res.content`
      为 body 字符串；`fetch = request`（引擎内部）；`oheaders/fetch_params` 由引擎自行处理。
- [ ] 用真实网络（非 mock）端到端验证 category（沙盒豆瓣不可达，需换可达源或留待本地/安卓）
- [ ] XPath / jsoup CSS 解析补充（当前 pdfh 仅正则 + JsonPath）

> 素材已存 `real-rule/`（jihulab 源）与 `qist-rule/`（qist/tvbox 源）
> spike 代码：`tv-engine/examples/module_drpy.rs`
