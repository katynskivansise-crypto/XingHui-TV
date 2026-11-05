package okhttp3.internal;

import android.util.Base64;

import com.xinghui.tv.Engine;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 网络出口：把 okhttp 形态的请求交给 Rust 引擎执行。
 *
 * 引擎 `http` 动作入参 {url, method, headers, body, ua, proxy, timeout}，
 * 出参 {code, headers, body(base64)}。URLError / 超时统一抛 IOException，
 * 与官方 okhttp 的 Call.execute 语义一致。
 */
public final class Bridge {

    /** 全局 UA / 代理，由 catvod 的 OkHttp.set_headers / setProxy 同步过来 */
    public static String globalUa = "";
    public static String globalProxy = "";

    private Bridge() {
    }

    public static Response execute(Request request, OkHttpClient client) throws IOException {
        String url = request.url().toString();
        if (url == null || url.isEmpty()) throw new IOException("empty url");

        JSONObject p = new JSONObject();
        try {
            p.put("url", url);
            p.put("method", request.method());

            JSONObject headers = new JSONObject();
            for (String name : request.headers().names()) {
                String v = request.headers().get(name);
                if (v != null) headers.put(name, v);
            }
            p.put("headers", headers);

            // 一律走 base64：表单是 ASCII 无所谓，但 multipart/文件上传里是二进制，
            // 用 text() 解码再编码会直接损坏内容。
            RequestBody body = request.body();
            if (body != null) {
                byte[] raw = body.bytes();
                if (raw != null && raw.length > 0) {
                    p.put("body_b64", Base64.encodeToString(raw, Base64.NO_WRAP));
                }
            }

            p.put("timeout", client.readTimeoutMillis());
            // 对齐 okhttp：noRedirect 的 client 必须真的不跟随 302，
            // 部分站点靠拿 Location 拼真实地址。
            p.put("follow_redirects", client.followRedirects());
            if (!globalUa.isEmpty()) p.put("ua", globalUa);
            if (!globalProxy.isEmpty()) p.put("proxy", globalProxy);
        } catch (JSONException e) {
            throw new IOException("build params failed: " + e);
        }

        String out;
        try {
            out = Engine.INSTANCE.execute("http", "", p.toString());
        } catch (Throwable t) {
            // so 没加载 / JNI 异常都在这里兜住，统一按网络错误处理，
            // 不能让 ExceptionInInitializerError 把 spider 整条链路打崩
            throw new IOException("engine error: " + t);
        }
        if (out == null || out.isEmpty()) throw new IOException("engine empty response");

        JSONObject r;
        try {
            r = new JSONObject(out);
        } catch (JSONException e) {
            throw new IOException("bad engine response: " + out);
        }
        int code = r.optInt("code", 0);
        if (code == 0) throw new IOException("http failed: " + r.optString("error", "network error"));

        String b64 = r.optString("body", "");
        byte[] data = b64.isEmpty() ? new byte[0] : Base64.decode(b64, Base64.DEFAULT);

        Headers.Builder hb = new Headers.Builder();
        JSONObject rh = r.optJSONObject("headers");
        if (rh != null) {
            Iterator<String> it = rh.keys();
            while (it.hasNext()) {
                String k = it.next();
                hb.add(k, rh.optString(k, ""));
            }
        }
        String ct = hb.get("Content-Type");
        MediaType mt = ct == null ? null : MediaType.get(ct);

        return new Response.Builder()
                .code(code)
                .headers(hb.build())
                .body(ResponseBody.create(mt, data))
                .build();
    }
}
