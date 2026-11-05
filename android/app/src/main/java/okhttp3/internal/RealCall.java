package okhttp3.internal;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Call 实现：先走拦截器链，链尾落到 Bridge 的 Rust 网络。 */
public final class RealCall implements Call {

    private final OkHttpClient client;
    private final Request originalRequest;
    private volatile boolean canceled;

    public RealCall(OkHttpClient client, Request request) {
        this.client = client;
        this.originalRequest = request;
    }

    @Override
    public Request request() {
        return originalRequest;
    }

    @Override
    public void cancel() {
        canceled = true;
    }

    @Override
    public Response execute() throws IOException {
        if (canceled) throw new IOException("Canceled");
        client.dispatcher().started(this);
        try {
            return new RealChain(client, 0, originalRequest, this).proceed(originalRequest);
        } finally {
            client.dispatcher().finished(this);
        }
    }

    private static final class RealChain implements Interceptor.Chain {

        private final OkHttpClient client;
        private final int index;
        private final Request request;
        private final Call call;

        RealChain(OkHttpClient client, int index, Request request, Call call) {
            this.client = client;
            this.index = index;
            this.request = request;
            this.call = call;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public Response proceed(Request req) throws IOException {
            java.util.List<Interceptor> list = client.interceptors();
            if (index < list.size()) {
                return list.get(index).intercept(new RealChain(client, index + 1, req, call));
            }
            // 应用拦截器走完，接着走网络拦截器，最后落到 Rust
            java.util.List<Interceptor> nets = client.networkInterceptors();
            int netIndex = index - list.size();
            if (netIndex < nets.size()) {
                return nets.get(netIndex).intercept(new RealChain(client, index + 1, req, call));
            }
            return Bridge.execute(req, client);
        }
    }
}
