# 路线图 · 追平原版全部功能

目标定位：**重构为"追平原版（fongmi TVBox）全部功能"的完整 App**。
这是数月级、相当于重写整个产品层的工程。按依赖顺序拆成里程碑，每阶段有独立可验证产出。

## 阶段总览

| 阶段 | 名称 | 依赖 | 验证点 |
|---|---|---|---|
| **P0** | 地基（已达成） | — | 引擎 18 测试绿；前端构建通过；`cargo check --features jni` 通过；CI 三 job 配置完成 |
| **P1** | 规则生态全覆盖 | P0 | 补齐 XPath / jsoup CSS(:eq、--) / JSON 规则解析；引擎能跑**主流订阅源全部格式**；新增对应单测 |
| **P2** | 引擎功能完备 | P1 | 直播(live)、聚合搜索、本地代理 proxyLocal、加密/图片代理；引擎对齐原 catvod 全部方法 |
| **P3** | 安卓替换验证 | P0 | CI 产出可安装 APK；JNI 桥调 home/category/play 全链路；ijk/ExoPlayer 集成播放 |
| **P4** | 核心体验 | P2+P3 | 首页/分类/详情/搜索/播放页 + TV 焦点导航 + 多源配置管理；日常可用 |
| **P5** | 周边功能 | P4 | 聚合搜索、直播 UI、多线路切换、订阅源管理；收藏/历史待续 |
| **P6** | 进阶与打磨 | P5 | 播放内核三选（ijk/ExoPlayer/**mpv**）、弹幕、字幕、倍速、设置、主题、性能优化、多设备兼容、上架准备 |

## 关键里程碑说明

### P1 — 规则生态全覆盖（本轮推进，主体完成）
**实测修正**：原版 `pdfh/pd/pdfa` 的真实语义是 **jsoup**（`Parser.java` 的 parseDomForUrl/parseDomForArray），
不是正则——已按此重构 `pdfh.rs`（hiker→jq 的 `:eq`、`&&`、多级选择、`Text`/`Html`/属性、`--` 排除预留、
`pd` 的 joinUrl/specUrl），并新增 `json_spider.rs`（JSON 规则执行器，CatVod type=3）。
当前引擎覆盖：**drpy + 老式 JS + jsoup(原版 pdfh 语义) + JSON 规则 + 正则/JsonPath**。
XPath 仍为可选长尾（老式源用到时再补）。

### P3 — 安卓替换验证（当前关键分水岭）
证明重构**能真正替换原版**。依赖 GitHub Actions 云端编译（沙盒无 Android SDK）。
- 首次 CI `assembleDebug` 产出 APK 即验证壳工程可编译
- 壳内跑通 init→home→category→player 全链路（已具备代码）
- 集成 ijk/ExoPlayer 播放内核（方案A保留项）

### P4 — 核心体验（与 P3 并列当前重点）
规则引擎已能跑主流源，重心转向**让 App 真能用**：
首页/分类/详情/搜索/播放页 + TV 焦点导航 + 多源配置管理。

### P5-P6 — UI 与周边（工作量主体）
原版是功能齐全的成品，P5-P6 相当于把整个 App 产品层重写一遍。
按"核心可用 → 周边补齐 → 打磨上架"推进，避免一开始铺开全部功能。

## 进度记录

- [x] P0 地基（Rust 引擎 + DrpyEngine + 前端最小闭环 + 安卓壳工程 + CI）
- [x] P1 规则生态 —— **drpy + 老式JS + jsoup(原版pdfh语义) + JSON 规则 + 正则/JsonPath 已覆盖**（XPath 长尾可选）
- [x] P2 引擎功能完备 —— **直播(live_spider) + 本地代理 image_proxy + net bytes + DrpySpider/JsonSpider 接入 proxy_local**（31 测试绿；加密留具体源扩展；聚合搜索=上层并发调单源 search）
- [x] P3 安卓替换验证 —— **壳工程 + JNI 桥 + CI(assembleDebug) 已就绪；待上传 GitHub 触发云端编译产出首枚 APK**
- [x] P4 核心体验 —— **前端首页/分类/详情/搜索/播放/多源配置全链路打通并浏览器实测**（31 测试 + 前端构建绿；播放 Web 用 HTML5、安卓接原生内核）
- [x] P5 周边功能 —— **聚合搜索（多源合并+来源标记）+ 直播页（分组/频道）+ 详情多线路切换 + 订阅源管理增强（/sources+URL 输入）+ 收藏/历史（localStorage 持久化）+ 播放内核切换标注（ijk/ExoPlayer/mpv）**，浏览器实测通过（追剧、EPG 待续）
- [ ] P6 进阶与打磨
