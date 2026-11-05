package okhttp3;

import java.util.List;

/** Cookie 存取策略（默认实现为不保存）。 */
public interface CookieJar {

    List<Cookie> loadForRequest(HttpUrl url);

    void saveFromResponse(HttpUrl url, List<Cookie> cookies);

    CookieJar NO_COOKIES = new CookieJar() {
        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            return new java.util.ArrayList<>();
        }

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        }
    };
}
