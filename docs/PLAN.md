# okcaptain/TV 重构方案A — 迁移方案

> 目标：在不改产品形态（仍是安卓 TV App）、保留原生播放内核的前提下，
> 用 **Rust 重写 catvod 引擎层**、用 **React Native for TV (RNTV)** 重写 UI 层。
> 播放内核（ijkplayer / ExoPlayer）保持原生不动。

---

## 1. 目标架构

```
┌────────────────────────────────────────────────────────────┐
│  Android TV App（最终 .apk）                                 │
│                                                             │
│  ┌────────────────────┐   ┌──────────────────────────────┐ │
│  │ RNTV UI 层          │   │ 原生播放内核（不动）          │ │
│  │ (React/TS)          │   │  · ExoPlayer / IJKPlayer     │ │
│  │ 首页/分类/详情/搜索/ │   │  · 本地 9978 服务 (app/server)│ │
│  │ 直播/播放页          │   └──────────────────────────────┘ │
│  └─────────┬──────────┘                                      │
│            │ JNI 调用                                         │
│  ┌─────────▼──────────┐   ┌──────────────────────────────┐  │
│  │ Rust 引擎层         │   │                              │  │
│  │ (libtv_engine.so)   │   │  app 其余部分（DB/设置/工具） │  │
│  │  · spider 执行器    │   │  可渐进保留或后续替换          │  │
│  │  · 网络层(net)      │   │                              │  │
│  │  · json 工具        │   └──────────────────────────────┘  │
│  └────────────────────┘                                      │
└────────────────────────────────────────────────────────────┘
```

## 2. 模块对应关系

| 原仓库模块 | 原实现 | 重构目标 | 说明 |
|---|---|---|---|
| `catvod/.../crawler/Spider.java` | Java 抽象基类 | `tv-engine/src/spider.rs` trait | 接口一一对应，已落地 |
| `catvod/.../net/OkHttp.java` | OkHttp (30s/缓存/DoH/代理/绕证书) | `tv-engine/src/net.rs` | ureq 实现，已落地 |
| `catvod/.../utils/Json.java` | Gson 容错工具 | `tv-engine/src/json.rs` | 已落地 |
| `catvod/.../Proxy.java` | 9978 端口配置 | `tv-engine/src/proxy.rs` | 已落地 |
| `catvod/.../Proxy.java` + `app/server` | 本地代理服务 | 后续：Rust 独立代理服务 或 保留 app/server | 待定 |
| `app/src/main/java/.../ui/**` | Android View UI (355 Java) | **RNTV** (React/TS) | 最大工作量，可渐进 |
| `app/.../player/exo` `ijkplayer` | 播放内核 | **保留原生** | 不迁移 |
| `quickjs` `pyramid` | JS 引擎跑 spider 脚本 | Rust 内嵌 `quickjs-rs`（可选） | 或保留原 so |

## 3. 已落地：Rust 引擎骨架（可编译、已测试）

目录：`tv-engine/`

```
tv-engine/
├── Cargo.toml            # crate-type = cdylib+rlib；jni 为 feature
├── examples/demo.rs      # 端到端演示（真实 HTTPS + JSON 解析）
└── src/
    ├── lib.rs
    ├── json.rs           # 对应 Json.java，含单元测试
    ├── net.rs            # 对应 OkHttp.java，含单元测试
    ├── proxy.rs          # 对应 Proxy.java，含单元测试
    ├── spider.rs         # 对应 Spider.java trait + SpiderManager，含单元测试
    └── jni.rs            # JNI 桥（feature "jni" 才编译）
```

**验证结果**（在云机执行 `cargo test` / `cargo run --example demo`）：
- 6 个单元测试全部通过（json 容错、net 默认值、proxy 端口、spider 注册调度）
- 真实 HTTPS 请求成功返回响应体（net 层对非 2xx 也返回 body，对齐 OkHttp 语义）
- JSON 解析 CatVod 响应正常

## 4. 实施步骤（建议顺序）

1. **引擎落地**（已完成骨架）→ 逐个站点 Spider 用 Rust 实现，跑通 `home/category/detail/search/player/live`
2. **JNI 桥打通** → `cargo ndk -t arm64-v8a -t armeabi-v7a build --release --features jni`，Java 侧 `Engine.execute()` 调通
3. **保留 9978 代理** → 先用原 `app/server`，稳定后再迁 Rust 代理
4. **RNTV 工程** → `npx react-native@latest init` 生成工程，加入 TV 焦点导航库，逐屏替换 UI
5. **播放内核对接** → RNTV 播放页通过原生模块 (TurboModule) 桥接 ExoPlayer/IJK

## 5. 关键决策与风险

| 决策 | 理由 |
|---|---|
| UI 用 **RNTV** 而非 Next.js | Next.js 是 Web 框架，安卓 TV 原生 UI 需 RNTV；Next.js 仅适合配套的 Web 管理端 |
| 播放内核**保留原生** | ijk/FFmpeg 硬解无法在 RN/Web 复现，是产品核心，不该动 |
| 引擎用 **ureq** 而非 reqwest | 同步 API、依赖轻、编译快，适配低配云机与 TV 盒 |
| Rust 编译目标 | 通过 `cargo-ndk` 交叉编译 `.so`，JNI 集成，不需要替换整个 Gradle 链路 |

**风险**：
- RNTV 重写 355 个 Java 的 UI 是最大工作量（数周级），建议渐进替换而非一次性重写
- spider 生态规则多为 JS 脚本（quickjs/pyramid），若全面迁移需 Rust 侧嵌入 JS 引擎
- 云机无 Android SDK/NDK/内存不足以产出 APK，需在本地有条件的机器编译集成

## 6. 环境与构建要求（本地机器）

- Android SDK + NDK（arm64-v8a / armeabi-v7a）
- JDK 11+、Gradle 8+
- Rust stable + `cargo install cargo-ndk` + rustup target add aarch64-linux-android armv7-linux-androideabi
- Node 18+（RNTV）
- 建议 ≥8GB 内存
