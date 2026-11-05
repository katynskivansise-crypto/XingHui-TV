package com.github.catvod.net.interceptor;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 响应拦截器。
 *
 * 原版依赖 okio（Okio.buffer/Okio.source）做流式 deflate 解包；自研 okhttp3 兼容层
 * 不带 okio（okio 是官方 okhttp 的传递依赖，已随依赖一起移除），这里改成把解压后
 * 的字节读全量再交给 ResponseBody，spider 只读 string()/bytes()，语义一致。
 */
public class ResponseInterceptor implements Interceptor {

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        String encoding = response.header("Content-Encoding");
        if (response.body() != null && "deflate".equalsIgnoreCase(encoding)) return deflate(response);
        return response;
    }

    private Response deflate(Response response) {
        InflaterInputStream is = null;
        try {
            is = new InflaterInputStream(response.body().byteStream(), new Inflater(true));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return response.newBuilder()
                    .headers(response.headers())
                    .body(ResponseBody.create(response.body().contentType(), bos.toByteArray()))
                    .build();
        } catch (Exception e) {
            // 解包失败就原样返回，不让一个压缩体把整条请求链打断
            return response;
        } finally {
            if (is != null) try {
                is.close();
            } catch (Exception ignore) {
            }
        }
    }
}
