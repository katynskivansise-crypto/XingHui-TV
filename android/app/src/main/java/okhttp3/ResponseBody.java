package okhttp3;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

/** 响应体（全量字节缓存，spider 只用到 byteStream/bytes/string）。 */
public class ResponseBody {

    private byte[] bytes;
    private MediaType type;

    public ResponseBody() {
        this.bytes = new byte[0];
        this.type = null;
    }

    public ResponseBody(byte[] bytes, MediaType type) {
        this.bytes = bytes == null ? new byte[0] : bytes;
        this.type = type;
    }

    public static ResponseBody create(MediaType type, byte[] bytes) {
        return new ResponseBody(bytes, type);
    }

    public InputStream byteStream() {
        return new ByteArrayInputStream(bytes());
    }

    public byte[] bytes() {
        return bytes == null ? new byte[0] : bytes;
    }

    public void close() {
    }

    public long contentLength() {
        return bytes().length;
    }

    public MediaType contentType() {
        return type;
    }

    public String string() {
        byte[] b = bytes();
        // 对齐 okhttp 的 BomAwareReader：BOM 优先级高于 Content-Type 里的 charset
        if (b.length >= 3 && b[0] == (byte) 0xEF && b[1] == (byte) 0xBB && b[2] == (byte) 0xBF) {
            return new String(b, 3, b.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && b[0] == (byte) 0xFE && b[1] == (byte) 0xFF) {
            return new String(b, 2, b.length - 2, java.nio.charset.StandardCharsets.UTF_16BE);
        }
        if (b.length >= 2 && b[0] == (byte) 0xFF && b[1] == (byte) 0xFE) {
            return new String(b, 2, b.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
        }
        Charset cs = type == null ? null : type.charsetOrNull();
        return new String(b, cs == null ? java.nio.charset.StandardCharsets.UTF_8 : cs);
    }
}
