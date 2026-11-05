package com.github.catvod.net;

import android.text.TextUtils;

import androidx.collection.ArrayMap;

import com.github.catvod.bean.Doh;
import com.github.catvod.net.interceptor.RequestInterceptor;
import com.github.catvod.net.interceptor.ResponseInterceptor;
import com.github.catvod.utils.Path;

import java.net.ProxySelector;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.internal.Bridge;

public class OkHttp {

    private static final int TIMEOUT = 30 * 1000;
    private static final int CACHE = 100 * 1024 * 1024;
    private static final ProxySelector defaultSelector;

    private boolean proxy;
    private Object dns;
    private OkHttpClient client;
    private OkProxySelector selector;

    static {
        defaultSelector = ProxySelector.getDefault();
        Bridge.globalUa = com.github.catvod.utils.Util.CHROME;
    }

    private static class Loader {
        static volatile OkHttp INSTANCE = new OkHttp();
    }

    public static OkHttp get() {
        return Loader.INSTANCE;
    }

    public static Dns dns() {
        return Dns.SYSTEM;
    }

    /**
     * 自研 okhttp3 兼容层已去掉 dnsoverhttps（它属于官方 okhttp 扩展，
     * 不在 spider.jar 的符号面内）。DNS 解析统一交给 Rust 引擎，
     * 这里保留方法签名只为兼容原调用点，不再改变解析策略。
     */
    public void setDoh(Doh doh) {
        dns = null;
        client = null;
    }

    public void setProxy(String proxy) {
        ProxySelector.setDefault(TextUtils.isEmpty(proxy) ? defaultSelector : selector());
        if (!TextUtils.isEmpty(proxy)) selector().setProxy(proxy);
        this.proxy = !TextUtils.isEmpty(proxy);
        // 真正发请求的是 Rust 引擎，代理地址要同步过去
        Bridge.globalProxy = this.proxy ? proxy : "";
        client = null;
    }

    public static OkProxySelector selector() {
        if (get().selector != null) return get().selector;
        return get().selector = new OkProxySelector();
    }

    public static OkHttpClient client() {
        if (get().client != null) return get().client;
        return get().client = getBuilder().build();
    }

    public static OkHttpClient client(int timeout) {
        return client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).build();
    }

    public static OkHttpClient noRedirect(int timeout) {
        return client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build();
    }

    public static OkHttpClient client(boolean redirect, int timeout) {
        return redirect ? client(timeout) : noRedirect(timeout);
    }

    public static String string(String url) {
        try {
            return url.startsWith("http") ? newCall(url).execute().body().string() : "";
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static String string(String url, Map<String, String> headers) {
        try {
            return newCall(url, Headers.of(headers)).execute().body().string();
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static Call newCall(String url) {
        return client().newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(OkHttpClient client, String url) {
        return client.newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(String url, Headers headers) {
        return client().newCall(new Request.Builder().url(url).headers(headers).build());
    }

    public static Call newCall(String url, Headers headers, ArrayMap<String, String> params) {
        return client().newCall(new Request.Builder().url(buildUrl(url, params)).headers(headers).build());
    }

    public static Call newCall(String url, Headers headers, RequestBody body) {
        return client().newCall(new Request.Builder().url(url).headers(headers).post(body).build());
    }

    public static Call newCall(OkHttpClient client, String url, RequestBody body) {
        return client.newCall(new Request.Builder().url(url).post(body).build());
    }

    public static FormBody toBody(ArrayMap<String, String> params) {
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) body.add(entry.getKey(), entry.getValue());
        return body.build();
    }

    private static HttpUrl buildUrl(String url, ArrayMap<String, String> params) {
        HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(url)).newBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) builder.addQueryParameter(entry.getKey(), entry.getValue());
        return builder.build();
    }

    private static OkHttpClient.Builder getBuilder() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().addInterceptor(new RequestInterceptor()).addNetworkInterceptor(new ResponseInterceptor()).connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS).readTimeout(TIMEOUT, TimeUnit.MILLISECONDS).writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS).dns(dns()).hostnameVerifier((hostname, session) -> true).followRedirects(true).sslSocketFactory(new SSLCompat(), SSLCompat.TM);
        builder.proxySelector(get().proxy ? selector() : defaultSelector);
        return builder;
    }
}
