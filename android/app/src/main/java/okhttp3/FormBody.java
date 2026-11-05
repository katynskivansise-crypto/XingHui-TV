package okhttp3;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** application/x-www-form-urlencoded 请求体。 */
public class FormBody extends RequestBody {

    private final List<String> names = new ArrayList<>();
    private final List<String> values = new ArrayList<>();

    public FormBody(List<String> names, List<String> values, boolean encoded) {
        super(MediaType.FORM_CHARSET, null);
        this.names.addAll(names);
        this.values.addAll(values);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < this.names.size(); i++) {
            if (!first) sb.append('&');
            first = false;
            sb.append(encoded ? this.names.get(i) : encode(this.names.get(i)));
            sb.append('=');
            sb.append(encoded ? this.values.get(i) : encode(this.values.get(i)));
        }
        this.bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    public static final class Builder {

        private final List<String> names = new ArrayList<>();
        private final List<String> values = new ArrayList<>();
        private boolean encoded;

        public Builder add(String name, String value) {
            names.add(name == null ? "" : name);
            values.add(value == null ? "" : value);
            encoded = false;
            return this;
        }

        public Builder addEncoded(String name, String value) {
            names.add(name == null ? "" : name);
            values.add(value == null ? "" : value);
            encoded = true;
            return this;
        }

        public FormBody build() {
            return new FormBody(names, values, encoded);
        }
    }
}
