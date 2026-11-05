# ---- 星汇TV keep 规则 ----
# spider.jar 由 DexClassLoader 动态加载，R8 静态分析看不到它引用的任何符号，
# 下面的类如果被裁掉就会在运行时 NoSuchMethodError / ClassNotFoundException。

# 1. 自研 okhttp3 兼容层：spider.jar 里的 83 个站点类直接引用
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# 2. catvod 基础类：反射 + JNI 入口
-keep class com.github.catvod.** { *; }
-dontwarn com.github.catvod.**

# 3. 引擎 JNI 入口
-keep class com.xinghui.tv.Engine { *; }
-keepclasseswithmembernames class * { native <methods>; }

# 4. spider.jar 内部实现类（运行时 loadClass，R8 完全看不见）
-keep class com.github.catvod.spider.** { *; }
