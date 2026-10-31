# 星汇 TV（XingHui TV）· CatVod 重构（Rust 引擎 + 现代前端）

对 `okcaptain/TV`（fongmi TVBox，已停止维护）的重构：**Rust 重写爬虫/规则执行引擎层 + React 现代前端 + 保留原生播放内核（ExoPlayer/mpv）**。核心目标是让真实 drpy 订阅源**原样执行**，使原版生态可继续跟进、且不依赖停更的原 App。

> 状态：开发中。Rust 引擎已能原样执行真实 drpy 规则（`home`/`category` 列表跑通）；安卓壳为可编译工程骨架，由 CI 持续构建验证。

## 架构

```
XingHui-TV
├── engine/     Rust 引擎层（对应原 catvod：net / json / spider / drpy_engine / pdfh / jni）
├── frontend/   React + Vite 前端（TV 焦点导航，可被 RNTV / Tauri 复用）
├── android/    安卓壳工程（Gradle + JNI 桥 + Kotlin UI + ExoPlayer）
├── samples/    真实规则素材（drpy 订阅源示例，测试用）
└── docs/       方案与设计文档
```

引擎与上层通过 CatVod 生态的 Spider 契约对接，替换原引擎时上层调用方式保持不变。

## 快速开始

### 引擎（Rust）

```bash
cd engine
cargo test            # 运行全部测试（31 项）
cargo run --example test_drpy_engine      # 用真实 drpy 规则跑 home
cargo run --example test_drpy_category    # 用真实规则跑 category 列表（mock req）
```

### 前端（React）

```bash
cd frontend
npm install
npm run dev           # 开发（vite :5173，proxy /api → 引擎 :9898）
npm run build         # 生产构建
```

### 安卓壳（Gradle + JNI）

需要 Android SDK/NDK + Rust + `cargo-ndk`。编译脚本见 `scripts/build-android.sh`，或直接用 GitHub Actions（见下）。

```bash
./scripts/build-android.sh     # 交叉编译 libtv_engine.so → android/app/src/main/jniLibs
cd android && ./gradlew :app:assembleDebug
```

## CI / 自动编译

`.github/workflows/build.yml` 会在每次 push 时于 GitHub 云端自动安装 Android SDK/NDK + Rust，分别编译验证：引擎（cargo test）、前端（npm build）、安卓 APK（gradle）。上传仓库后无需本地环境即可持续构建。

## 文档

- [`docs/PLAN.md`](docs/PLAN.md) —— 迁移方案
- [`docs/SPIDER-DESIGN.md`](docs/SPIDER-DESIGN.md) —— JS 规则执行设计
- [`docs/DRPY-ENGINE.md`](docs/DRPY-ENGINE.md) —— drpy 执行器设计与实测结论

## 边界与说明

- **播放内核**：保留原  ExoPlayer，未重写（属移植保留项）新增mpv。
- **规则素材**：`samples/rules/` 内的 drpy 引擎/规则脚本仅用于开发测试；运行时请使用自己的订阅源配置（api/ext URL），不随 App 分发。
- 本项目为技术重构/学习用途，请遵守所接入数据源的相关条款。
