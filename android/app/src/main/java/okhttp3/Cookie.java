package okhttp3;

/** 最小 Cookie 载体（仅占位，spider 未直接读写字段）。 */
public class Cookie {

    private final String name;
    private final String value;
    private final String domain;
    private final String path;

    private Cookie(String name, String value, String domain, String path) {
        this.name = name;
        this.value = value;
        this.domain = domain;
        this.path = path;
    }

    public static Cookie create(String name, String value) {
        return new Cookie(name, value, "", "/");
    }

    public String name() {
        return name;
    }

    public String value() {
        return value;
    }

    public String domain() {
        return domain;
    }

    public String path() {
        return path;
    }
}
