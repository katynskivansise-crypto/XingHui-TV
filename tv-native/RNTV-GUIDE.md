# RNTV UI 层 —— 集成指引（方案A）

> 本目录规划 React Native for TV 的 UI 层。云机无法产出 APK，这里给出
> 工程生成方式、关键文件样例与 JNI 调用封装，供在本地有条件的机器落地。

## 1. 生成 RNTV 工程

```bash
# 官方 TV 支持入口（React Native 0.7x 起官方支持 TV）
npx react-native@latest init TvNative --pm yarn
cd TvNative
# 安装 TV 平台支持（焦点导航 / Android TV 平台代码）
yarn add react-native-tvos @react-native-community/slider
```

> 实际 TV 焦点与遥控器导航，社区成熟方案是 **React Native TV**（`react-native-tvos` 分支）
> 或 `@flexn/renative`。选型需结合目标机型（leanback 焦点规范）。

## 2. 目录规划（与 UI 屏幕对应）

```
tv-native/
├── App.tsx                  # 入口，注册导航
├── index.js
├── src/
│   ├── screens/
│   │   ├── HomeScreen.tsx      # 首页（对应原 app/ui/activity/HomeActivity）
│   │   ├── CategoryScreen.tsx  # 分类（CategoryActivity）
│   │   ├── DetailScreen.tsx    # 详情（DetailActivity）
│   │   ├── SearchScreen.tsx    # 搜索（SearchActivity）
│   │   ├── LiveScreen.tsx      # 直播（LiveActivity）
│   │   └── PlayerScreen.tsx    # 播放页（PlayActivity，桥接原生播放器）
│   ├── components/             # TV 焦点卡片/列表组件
│   ├── engine/                 # JNI 调用封装（调 Rust 引擎）
│   │   └── Engine.ts
│   └── native/EngineModule.java # TurboModule：转发到 Rust .so
└── android/  …                # RN 标准安卓工程
```

## 3. JNI 调用链（RN → Rust）

```
RNTV JS (Engine.ts)
   │  NativeModules.Engine.execute(action, key, params)
   ▼
Java/Kotlin EngineModule.java
   │  System.loadLibrary("tv_engine")
   │  Engine.execute(action, key, params)  → native
   ▼
Rust  libtv_engine.so  Java_com_fongmi_tv_engine_Engine_execute
   │  路由到 spider 方法，返回 JSON 字符串
```

### 3.1 Java 侧封装样例（`src/native/EngineModule.java`）

```java
package com.fongmi.tv.engine;

import androidx.annotation.NonNull;
import com.facebook.react.bridge.*;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.uimanager.BaseViewManager; // 若为 TurboModule 则不同

public class EngineModule extends ReactContextBaseJavaModule {
    static { System.loadLibrary("tv_engine"); }

    public EngineModule(ReactApplicationContext context) { super(context); }

    @Override @NonNull public String getName() { return "Engine"; }

    // 原生 JNI 声明：对应 Rust 导出的 execute
    public static native String execute(String action, String key, String params);

    @ReactMethod
    public void execute(String action, String key, String params, Promise p) {
        try {
            p.resolve(execute(action, key, params));
        } catch (Throwable t) {
            p.reject("ENGINE_ERR", t.getMessage(), t);
        }
    }
}
```

### 3.2 RN 侧调用（`src/engine/Engine.ts`）

```ts
import { NativeModules } from 'react-native';
const Engine = NativeModules.Engine;

// 对应原 Spider.homeContent()
export async function homeContent(): Promise<string> {
  return Engine.execute('home', 'default', '');
}
export async function categoryContent(tid: string, pg: string): Promise<string> {
  return Engine.execute('category', 'default', JSON.stringify({ tid, pg }));
}
export async function playerContent(flag: string, id: string): Promise<string> {
  return Engine.execute('player', 'default', JSON.stringify({ flag, id }));
}
```

## 4. 播放内核对接

播放页不直接用 RN 播放组件，而是通过原生模块把 URL 交给 **ExoPlayer/IJK**
（保留原 `app/.../player/exo` 与 `ijkplayer`），方法与解析结果照旧走 Rust 引擎。

## 5. 本目录当前状态

- [x] 目录结构规划（本文件）
- [x] Rust 引擎 + JNI 桥（见 `../tv-engine`）
- [ ] RN 工程生成（需本地 Node/Android 环境）
- [ ] 各屏幕实现（渐进替换原 Java UI）
