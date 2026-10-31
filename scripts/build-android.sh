#!/usr/bin/env bash
# 交叉编译 Rust 引擎 → libtv_engine.so，放置到 android/app/src/main/jniLibs。
# 前置：Android NDK + Rust 工具链 + cargo-ndk（cargo install cargo-ndk）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"

cd "$ROOT/engine"
echo "==> cargo ndk build (arm64-v8a, --features jni)"
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release --features jni

echo "==> 产物："
ls -la ../android/app/src/main/jniLibs/arm64-v8a/
echo "OK: libtv_engine.so 已就位，可执行 cd android && gradle :app:assembleDebug"
