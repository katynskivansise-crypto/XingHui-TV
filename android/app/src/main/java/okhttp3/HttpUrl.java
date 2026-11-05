package okhttp3;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** URL（只做 spider 需要的拼接/取 host）。 */
public final class HttpUrl {

    private final String url;

    private HttpUrl(String url) {
        this.url = normalize(url);
    }

    /**
     * 最小规范化：把空格和非 ASCII 字符做 UTF-8 百分号编码。
     * 官方 okhttp 的 HttpUrl.get 会做同样的事；不编码的话，带中文参数或空格的
     * URL 传到 ureq 会解析失败，整条请求直接空返回。
     */
    private static String normalize(String url) {
        if (url == null) return "";
        boolean need = false;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c > 0x7F || c == ' ') {
                need = true;
                break;
            }
        }
        if (!need) return url;
        StringBuilder sb = new StringBuilder(url.length() + 16);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == ' ') {
                sb.append("%20");
            } else if (c > 0x7F) {
                byte[] bs = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : bs) sb.append('%').append(String.format("%02X", b & 0xFF));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static HttpUrl get(String url) {
        return new HttpUrl(url);
    }

    public static HttpUrl parse(String url) {
        return url == null ? null : new HttpUrl(url);
    }

    public HttpUrl resolve(String link) {
        return new HttpUrl(link);
    }

    /** 供 catvod RequestInterceptor 取 userInfo 用 */
    public java.net.URI uri() {
        try {
            return new java.net.URI(url);
        } catch (Exception e) {
            try {
                return java.net.URI.create("http://127.0.0.1/");
            } catch (Exception ignore) {
                return null;
            }
        }
    }

    public String queryParameter(String name) {
        String q = queryString();
        if (q.isEmpty()) return null;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(name)) {
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    return java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Exception ignore) {
                    return v;
                }
            }
        }
        return null;
    }

    public int querySize() {
        String q = queryString();
        return q.isEmpty() ? 0 : q.split("&").length;
    }

    private String queryString() {
        int q = url.indexOf('?');
        if (q < 0) return "";
        String s = url.substring(q + 1);
        int hash = s.indexOf('#');
        return hash < 0 ? s : s.substring(0, hash);
    }

    public String pathSegments() {
        return "";
    }

    public String host() {
        String s = url;
        int sep = s.indexOf("://");
        if (sep >= 0) s = s.substring(sep + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        // 必须先剥 userinfo，否则 user:pass@host 会被当成 host 的冒号
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }

    /** 端口（无则按 scheme 猜） */
    public int port() {
        String s = url;
        int sep = s.indexOf("://");
        String scheme = sep >= 0 ? s.substring(0, sep).toLowerCase() : "http";
        if (sep >= 0) s = s.substring(sep + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');
        if (colon >= 0) {
            try {
                return Integer.parseInt(s.substring(colon + 1));
            } catch (Exception ignore) {
                return scheme.equals("https") ? 443 : 80;
            }
        }
        return scheme.equals("https") ? 443 : 80;
    }

    public Builder newBuilder() {
        return new Builder(url);
    }

    public String url() {
        return url;
    }

    @Override
    public String toString() {
        return url;
    }

    public static final class Builder {

        private String base;
        private final Map<String, String> query = new LinkedHashMap<>();
        private final Map<String, String> encodedQuery = new LinkedHashMap<>();

        public Builder(String base) {
            this.base = base == null ? "" : base;
        }

        public Builder addQueryParameter(String name, String value) {
            query.put(name, value);
            return this;
        }

        /** 对齐 okhttp：入参已是编码串，build 时不再二次编码 */
        public Builder addEncodedQueryParameter(String name, String value) {
            encodedQuery.put(name, value);
            return this;
        }

        public HttpUrl build() {
            if (query.isEmpty() && encodedQuery.isEmpty()) return new HttpUrl(base);
            StringBuilder sb = new StringBuilder(base);
            int hash = sb.indexOf("#");
            String tail = "";
            if (hash >= 0) {
                tail = sb.substring(hash);
                sb.setLength(hash);
            }
            sb.append(sb.indexOf("?") >= 0 ? "&" : "?");
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) sb.append('&');
                first = false;
                sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            }
            for (Map.Entry<String, String> e : encodedQuery.entrySet()) {
                if (!first) sb.append('&');
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append(tail);
            return new HttpUrl(sb.toString());
        }

        private static String encode(String s) {
            try {
                return URLEncoder.encode(s == null ? "" : s, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return s == null ? "" : s;
            }
        }
    }
}
