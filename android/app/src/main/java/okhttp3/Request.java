package okhttp3;

/** HTTP 请求。 */
public class Request {

    private HttpUrl url;
    private String method = "GET";
    private Headers headers;
    private RequestBody body;
    private Object tag;

    public HttpUrl url() {
        return url == null ? HttpUrl.get("") : url;
    }

    public String method() {
        return method == null ? "GET" : method;
    }

    public Headers headers() {
        return headers == null ? Headers.of() : headers;
    }

    public RequestBody body() {
        return body;
    }

    public Object tag() {
        return tag;
    }

    public Builder newBuilder() {
        Builder b = new Builder();
        b.url = url;
        b.method = method;
        b.headers = headers;
        b.body = body;
        b.tag = tag;
        return b;
    }

    public static final class Builder {

        private HttpUrl url;
        private String method = "GET";
        private Headers headers;
        private RequestBody body;
        private Object tag;

        public Builder url(String url) {
            this.url = HttpUrl.get(url);
            return this;
        }

        public Builder url(HttpUrl url) {
            this.url = url;
            return this;
        }

        public Builder header(String name, String value) {
            Headers.Builder b = headers == null ? new Headers.Builder() : headers.newBuilder();
            b.set(name, value);
            headers = b.build();
            return this;
        }

        public Builder addHeader(String name, String value) {
            Headers.Builder b = headers == null ? new Headers.Builder() : headers.newBuilder();
            b.add(name, value);
            headers = b.build();
            return this;
        }

        public Builder headers(Headers headers) {
            this.headers = headers;
            return this;
        }

        public Builder get() {
            this.method = "GET";
            this.body = null;
            return this;
        }

        public Builder head() {
            this.method = "HEAD";
            this.body = null;
            return this;
        }

        public Builder post(RequestBody body) {
            this.method = "POST";
            this.body = body;
            return this;
        }

        public Builder method(String method, RequestBody body) {
            this.method = method == null ? "GET" : method.toUpperCase();
            this.body = body;
            return this;
        }

        public Builder tag(Object tag) {
            this.tag = tag;
            return this;
        }

        public Request build() {
            Request r = new Request();
            r.url = url == null ? HttpUrl.get("") : url;
            r.method = method;
            r.headers = headers == null ? Headers.of() : headers;
            r.body = body;
            r.tag = tag;
            return r;
        }
    }
}
