package okhttp3;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** DNS 解析策略。实际解析由 Rust 侧完成，这里只提供 spider 需要的类型与 SYSTEM 常量。 */
public interface Dns {

    List<InetAddress> lookup(String hostname) throws UnknownHostException;

    Dns SYSTEM = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> out = new ArrayList<>();
            InetAddress[] all = InetAddress.getAllByName(hostname);
            if (all != null) for (InetAddress a : all) out.add(a);
            return out;
        }
    };
}
