package okhttp3;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** 媒体类型（替代官方 okhttp3.MediaType，只看 type/subtype/charset）。 */
public class MediaType {

    public static final MediaType FORM = get("application/x-www-form-urlencoded");

    /** 与官方 okhttp 一致：表单默认带 utf-8 */
    public static final MediaType FORM_CHARSET = get("application/x-www-form-urlencoded; charset=utf-8");

    private final String value;

    private MediaType(String value) {
        this.value = value == null ? "" : value;
    }

    public static MediaType get(String value) {
        return value == null ? null : new MediaType(value);
    }

    public static MediaType parse(String value) {
        return value == null ? null : new MediaType(value);
    }

    public String type() {
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    public String subtype() {
        String sub = value.substring(value.indexOf('/') + 1);
        int semi = sub.indexOf(';');
        return semi < 0 ? sub : sub.substring(0, semi).trim();
    }

    public Charset charset() {
        Charset cs = charsetOrNull();
        return cs == null ? StandardCharsets.UTF_8 : cs;
    }

    public Charset charsetOrNull() {
        String lower = value.toLowerCase();
        int idx = lower.indexOf("charset=");
        if (idx < 0) return null;
        String name = value.substring(idx + 8).split(";")[0].trim().replace("\"", "").replace("'", "");
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
