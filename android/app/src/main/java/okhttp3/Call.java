package okhttp3;

import java.io.IOException;

/** 一次请求的句柄。 */
public interface Call {
    Request request();
    void cancel();
    Response execute() throws IOException;
}
