package okhttp3;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** multipart/form-data 请求体，按字节拼装（不经过字符串，避免二进制损坏）。 */
public class MultipartBody extends RequestBody {

    public static final MediaType FORM = MediaType.get("multipart/form-data");

    private final String boundary;
    private final MediaType type;

    private MultipartBody(String boundary, MediaType type, byte[] bytes) {
        super(type, bytes);
        this.boundary = boundary;
        this.type = type;
    }

    /** 单个 part。 */
    public static final class Part {

        final Headers headers;
        final RequestBody body;

        private Part(Headers headers, RequestBody body) {
            this.headers = headers == null ? Headers.of() : headers;
            this.body = body;
        }

        public static Part create(RequestBody body) {
            return new Part(Headers.of(), body);
        }

        public static Part create(Headers headers, RequestBody body) {
            return new Part(headers, body);
        }

        /** 对齐 okhttp：name / filename 都进 Content-Disposition，filename 可为 null */
        public static Part createFormData(String name, String filename, RequestBody body) {
            StringBuilder disp = new StringBuilder("form-data; name=\"");
            disp.append(name == null ? "" : name).append('"');
            if (filename != null) disp.append("; filename=\"").append(filename).append('"');
            Headers.Builder b = new Headers.Builder();
            b.add("Content-Disposition", disp.toString());
            return new Part(b.build(), body == null ? RequestBody.create(null, "") : body);
        }
    }

    public static final class Builder {

        private final List<Part> parts = new ArrayList<>();
        private MediaType type = FORM;

        public Builder addFormDataPart(String name, String value) {
            return addFormDataPart(name, null, RequestBody.create(null, value == null ? "" : value));
        }

        public Builder addFormDataPart(String name, String filename, RequestBody body) {
            parts.add(Part.createFormData(name, filename, body));
            return this;
        }

        public Builder addPart(Part part) {
            if (part != null) parts.add(part);
            return this;
        }

        public Builder setType(MediaType type) {
            if (type != null) this.type = type;
            return this;
        }

        public MultipartBody build() {
            String boundary = "----XingHuiTV" + UUID.randomUUID().toString().replace("-", "");
            MediaType real = MediaType.get(type.toString() + "; boundary=" + boundary);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Part p : parts) {
                write(out, "--" + boundary + "\r\n");
                for (String name : p.headers.names()) {
                    write(out, name + ": " + p.headers.get(name) + "\r\n");
                }
                if (p.body != null && p.body.contentType() != null) {
                    write(out, "Content-Type: " + p.body.contentType() + "\r\n");
                }
                write(out, "Content-Length: " + p.body.bytes().length + "\r\n\r\n");
                try {
                    out.write(p.body.bytes());
                } catch (Exception ignore) {
                }
                write(out, "\r\n");
            }
            write(out, "--" + boundary + "--\r\n");
            return new MultipartBody(boundary, real, out.toByteArray());
        }

        private static void write(ByteArrayOutputStream out, String s) {
            try {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignore) {
            }
        }
    }
}
