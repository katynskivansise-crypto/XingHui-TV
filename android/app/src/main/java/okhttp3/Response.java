package okhttp3;

import java.util.ArrayList;
import java.util.List;

/** HTTP 响应。 */
public class Response {

    private int code;
    private Headers headers;
    private ResponseBody body;

    public ResponseBody body() {
        if (body == null) body = new ResponseBody();
        return body;
    }

    public void close() {
        if (body != null) body.close();
    }

    public int code() {
        return code;
    }

    public String header(String name) {
        return headers == null ? null : headers.get(name);
    }

    public String header(String name, String defaultValue) {
        String v = header(name);
        return v == null ? defaultValue : v;
    }

    public List<String> headers(String name) {
        return headers == null ? new ArrayList<String>() : headers.values(name);
    }

    public Headers headers() {
        return headers == null ? Headers.of() : headers;
    }

    public boolean isRedirect() {
        return code >= 300 && code < 400 && header("Location") != null;
    }

    public boolean isSuccessful() {
        return code >= 200 && code < 300;
    }

    public Builder newBuilder() {
        Builder b = new Builder();
        b.code = code;
        b.headers = headers;
        b.body = body;
        return b;
    }

    public static final class Builder {

        private int code = 200;
        private Headers headers;
        private ResponseBody body;

        public Builder code(int code) {
            this.code = code;
            return this;
        }

        public Builder headers(Headers headers) {
            this.headers = headers;
            return this;
        }

        public Builder body(ResponseBody body) {
            this.body = body;
            return this;
        }

        public Response build() {
            Response r = new Response();
            r.code = code;
            r.headers = headers == null ? Headers.of() : headers;
            r.body = body;
            return r;
        }
    }
}
