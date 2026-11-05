package okhttp3;

/** 请求体（只保留 spider 用到的构造方式）。 */
public class RequestBody {

    MediaType type;
    byte[] bytes;

    public RequestBody() {
    }

    public RequestBody(MediaType type, byte[] bytes) {
        this.type = type;
        this.bytes = bytes;
    }

    public MediaType contentType() {
        return type;
    }

    public byte[] bytes() {
        return bytes == null ? new byte[0] : bytes;
    }

    public String text() {
        return new String(bytes(), type == null ? java.nio.charset.StandardCharsets.UTF_8 : type.charset());
    }

    public static RequestBody create(MediaType type, String content) {
        return new RequestBody(type, (content == null ? "" : content).getBytes(
                type == null ? java.nio.charset.StandardCharsets.UTF_8 : type.charset()));
    }

    public static RequestBody create(MediaType type, byte[] content) {
        return new RequestBody(type, content == null ? new byte[0] : content);
    }

    public static RequestBody create(MediaType type, byte[] content, int length) {
        return new RequestBody(type, content == null ? new byte[0] : content);
    }

    /** dex 里 spider.jar 实际引用的是四参版本 (MediaType, byte[], int, int) */
    public static RequestBody create(MediaType type, byte[] content, int offset, int length) {
        return new RequestBody(type, content == null ? new byte[0] : content);
    }
}
