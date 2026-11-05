package okhttp3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTP 客户端。
 *
 * 对外只保留 okhttp 的公开 API 形态；真正的网络传输由 {@code internal.Bridge}
 * 转到 Rust 引擎的 {@code http} 动作执行（壳侧不再依赖官方 okhttp）。
 */
public class OkHttpClient {

    final List<Interceptor> interceptors = new ArrayList<>();
    final List<Interceptor> networkInterceptors = new ArrayList<>();
    final Dispatcher dispatcher = new Dispatcher();
    long connectTimeoutMillis = 30000L;
    long readTimeoutMillis = 30000L;
    long writeTimeoutMillis = 30000L;
    CookieJar cookieJar = CookieJar.NO_COOKIES;
    Dns dns = Dns.SYSTEM;
    boolean followRedirects = true;
    boolean followSslRedirects = true;
    boolean retryOnConnectionFailure = true;
    HostnameVerifier hostnameVerifier;
    SSLSocketFactory sslSocketFactory;
    X509TrustManager trustManager;

    public OkHttpClient() {
    }

    /** 供 internal.RealCall 读取拦截器链 */
    public java.util.List<Interceptor> interceptors() {
        return interceptors;
    }

    public java.util.List<Interceptor> networkInterceptors() {
        return networkInterceptors;
    }

    /** 供 internal.Bridge 取超时 */
    public long readTimeoutMillis() {
        return readTimeoutMillis;
    }

    public boolean followRedirects() {
        return followRedirects;
    }

    public long connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public Call newCall(Request request) {
        return new okhttp3.internal.RealCall(this, request);
    }

    public Builder newBuilder() {
        Builder b = new Builder();
        b.interceptors.addAll(interceptors);
        b.networkInterceptors.addAll(networkInterceptors);
        b.connectTimeoutMillis = connectTimeoutMillis;
        b.readTimeoutMillis = readTimeoutMillis;
        b.writeTimeoutMillis = writeTimeoutMillis;
        b.cookieJar = cookieJar;
        b.dns = dns;
        b.followRedirects = followRedirects;
        b.followSslRedirects = followSslRedirects;
        b.retryOnConnectionFailure = retryOnConnectionFailure;
        b.hostnameVerifier = hostnameVerifier;
        b.sslSocketFactory = sslSocketFactory;
        b.trustManager = trustManager;
        return b;
    }

    public static final class Builder {

        private final List<Interceptor> interceptors = new ArrayList<>();
        private final List<Interceptor> networkInterceptors = new ArrayList<>();
        private java.net.ProxySelector proxySelector;
        private long connectTimeoutMillis = 30000L;
        private long readTimeoutMillis = 30000L;
        private long writeTimeoutMillis = 30000L;
        private CookieJar cookieJar = CookieJar.NO_COOKIES;
        private Dns dns = Dns.SYSTEM;
        private boolean followRedirects = true;
        private boolean followSslRedirects = true;
        private boolean retryOnConnectionFailure = true;
        private HostnameVerifier hostnameVerifier;
        private SSLSocketFactory sslSocketFactory;
        private X509TrustManager trustManager;

        public Builder addInterceptor(Interceptor interceptor) {
            if (interceptor != null) interceptors.add(interceptor);
            return this;
        }

        public Builder connectTimeout(long timeout, TimeUnit unit) {
            connectTimeoutMillis = unit == null ? timeout : unit.toMillis(timeout);
            return this;
        }

        public Builder readTimeout(long timeout, TimeUnit unit) {
            readTimeoutMillis = unit == null ? timeout : unit.toMillis(timeout);
            return this;
        }

        public Builder writeTimeout(long timeout, TimeUnit unit) {
            writeTimeoutMillis = unit == null ? timeout : unit.toMillis(timeout);
            return this;
        }

        public Builder cookieJar(CookieJar cookieJar) {
            if (cookieJar != null) this.cookieJar = cookieJar;
            return this;
        }

        public Builder dns(Dns dns) {
            if (dns != null) this.dns = dns;
            return this;
        }

        public Builder followRedirects() {
            followRedirects = true;
            return this;
        }

        public Builder followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        public Builder followSslRedirects() {
            followSslRedirects = true;
            return this;
        }

        public Builder followSslRedirects(boolean followRedirects) {
            this.followSslRedirects = followRedirects;
            return this;
        }

        public Builder cache(Cache cache) {
            return this;
        }

        public Builder addNetworkInterceptor(Interceptor interceptor) {
            if (interceptor != null) networkInterceptors.add(interceptor);
            return this;
        }

        public Builder proxySelector(java.net.ProxySelector selector) {
            this.proxySelector = selector;
            return this;
        }

        public Builder retryOnConnectionFailure(boolean retry) {
            retryOnConnectionFailure = retry;
            return this;
        }

        public Builder retryOnConnectionFailure() {
            retryOnConnectionFailure = true;
            return this;
        }

        public Builder hostnameVerifier(HostnameVerifier verifier) {
            hostnameVerifier = verifier;
            return this;
        }

        public Builder sslSocketFactory(SSLSocketFactory factory, X509TrustManager manager) {
            sslSocketFactory = factory;
            trustManager = manager;
            return this;
        }

        public OkHttpClient build() {
            OkHttpClient c = new OkHttpClient();
            c.interceptors.addAll(interceptors);
            c.networkInterceptors.addAll(networkInterceptors);
            c.connectTimeoutMillis = connectTimeoutMillis;
            c.readTimeoutMillis = readTimeoutMillis;
            c.writeTimeoutMillis = writeTimeoutMillis;
            c.cookieJar = cookieJar;
            c.dns = dns;
            c.followRedirects = followRedirects;
            c.followSslRedirects = followSslRedirects;
            c.retryOnConnectionFailure = retryOnConnectionFailure;
            c.hostnameVerifier = hostnameVerifier;
            c.sslSocketFactory = sslSocketFactory;
            c.trustManager = trustManager;
            return c;
        }
    }
}
