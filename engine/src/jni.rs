use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use tv_engine::spider::SpiderManager;

#[no_mangle]
pub unsafe extern "system" fn Java_com_xinghui_tv_Engine_execute(
    env: JNIEnv,
    _class: JClass,
    key: JString,
    params: JString,
    extra: JString,
) -> jstring {
    // 读取三个入参，安全处理，出错返回空字符串
    let key_str = match env.get_string(&key) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let params_str = match env.get_string(&params) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let extra_str = match env.get_string(&extra) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    // 调用引擎逻辑
    let result = SpiderManager::get(&key_str)
        .and_then(|spider| spider.execute(&params_str, &extra_str))
        .unwrap_or_else(|err| format!("{{\"error\":\"{}\"}}", err));

    // 返回给Java层
    let output = env.new_string(result).unwrap_or_else(|_| {
        env.new_string(r#"{"error":"jni return fail"}"#).unwrap()
    });
    output.into_raw()
}
