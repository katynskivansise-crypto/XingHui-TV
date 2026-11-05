package okhttp3;

import java.io.File;

/** 磁盘缓存占位（真实缓存由 Rust 引擎侧自行处理，这里只保留构造签名）。 */
public class Cache {

    private final File directory;
    private final long maxSize;

    public Cache(File directory, long maxSize) {
        this.directory = directory;
        this.maxSize = maxSize;
    }

    public File directory() {
        return directory;
    }

    public long maxSize() {
        return maxSize;
    }
}
