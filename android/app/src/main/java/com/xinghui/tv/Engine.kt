package com.xinghui.tv

/**
 * 加载 Rust 引擎 so（libtv_engine.so），暴露 JNI 入口。
 * 对应 Rust 侧 `#[no_mangle] Java_com_xinghui_tv_Engine_execute(...)`。
 */
object Engine {
    init {
        System.loadLibrary("tv_engine")
    }

    /**
     * @param action init / home / category / detail / search / player / live
     * @param key    规则 key（init 时注册）
     * @param params JSON 参数串（init 时含 dir/rule/engine；其余为 tid/pg/ids/key/flag/id）
     */
    external fun execute(action: String, key: String, params: String): String
}
