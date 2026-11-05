package okhttp3.internal;

import okhttp3.Call;
import okhttp3.EventListener;

/**
 * okhttp3.internal.Util 的替身。
 *
 * spider.jar 编译时引用的是被混淆过的 okhttp，其中两个工具方法名被 R8 改成了
 * 单字符 U+037F / U+0528。这里按原签名 1:1 实现，保证 jar 里的调用不会
 * NoSuchMethodError：
 *   U+037F (EventListener, Call) -> EventListener   包装并返回监听器
 *   U+0528 (String, boolean, Runnable) -> Thread    按名字/daemon 构造线程
 */
public final class Util {

    private Util() {
    }

    /** 对应混淆名 Ϳ：返回传入的监听器（空则给 NONE）。 */
    public static EventListener Ϳ(EventListener listener, Call call) {
        return listener == null ? EventListener.NONE : listener;
    }

    /** 对应混淆名 Ԩ：构造线程并设置 daemon，不自动 start。 */
    public static Thread Ԩ(String name, boolean daemon, Runnable runnable) {
        Thread t = name == null ? new Thread(runnable) : new Thread(runnable, name);
        t.setDaemon(daemon);
        return t;
    }
}
