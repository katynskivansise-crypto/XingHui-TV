package okhttp3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** HTTP 头（大小写不敏感，多值）。 */
public final class Headers {

    private final Map<String, List<String>> map;

    private Headers(Map<String, List<String>> map) {
        this.map = map == null ? new LinkedHashMap<String, List<String>>() : map;
    }

    public static Headers of(Map<String, String> src) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        if (src != null) for (Map.Entry<String, String> e : src.entrySet()) {
            List<String> l = new ArrayList<>();
            l.add(e.getValue());
            m.put(e.getKey(), l);
        }
        return new Headers(m);
    }

    public static Headers of(String... namesAndValues) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            List<String> l = m.get(namesAndValues[i]);
            if (l == null) {
                l = new ArrayList<>();
                m.put(namesAndValues[i], l);
            }
            l.add(namesAndValues[i + 1]);
        }
        return new Headers(m);
    }

    static Map<String, List<String>> rawOf(Headers h) {
        return h == null ? new LinkedHashMap<String, List<String>>() : h.map;
    }

    public String get(String name) {
        List<String> l = map.get(name);
        if (l != null && !l.isEmpty()) return l.get(l.size() - 1);
        // 大小写不敏感兜底
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
        }
        return null;
    }

    public List<String> values(String name) {
        List<String> l = map.get(name);
        if (l != null) return new ArrayList<>(l);
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return new ArrayList<>(e.getValue());
        }
        return new ArrayList<>();
    }

    public Set<String> names() {
        return new LinkedHashSet<>(map.keySet());
    }

    public Map<String, String> toMultimap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            out.put(e.getKey(), e.getValue().isEmpty() ? "" : e.getValue().get(0));
        }
        return out;
    }

    public int size() {
        return map.size();
    }

    public Builder newBuilder() {
        Builder b = new Builder();
        b.addAll(this);
        return b;
    }

    @Override
    public String toString() {
        return map.toString();
    }

    public static final class Builder {

        private final Map<String, List<String>> map = new LinkedHashMap<>();

        public Builder add(String name) {
            return add(name, "");
        }

        public Builder add(String name, String value) {
            List<String> l = map.get(name);
            if (l == null) {
                l = new ArrayList<>();
                map.put(name, l);
            }
            l.add(value == null ? "" : value);
            return this;
        }

        public Builder addUnsafeNonAscii(String name, String value) {
            return add(name, value);
        }

        public Builder addAll(Headers headers) {
            if (headers == null) return this;
            for (Map.Entry<String, List<String>> e : headers.map.entrySet()) {
                for (String v : e.getValue()) add(e.getKey(), v);
            }
            return this;
        }

        public String get(String name) {
            List<String> l = map.get(name);
            if (l != null && !l.isEmpty()) return l.get(l.size() - 1);
            for (Map.Entry<String, List<String>> e : map.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            }
            return null;
        }

        public Builder removeAll(String name) {
            map.remove(name);
            for (String k : new ArrayList<>(map.keySet())) {
                if (k.equalsIgnoreCase(name)) map.remove(k);
            }
            return this;
        }

        public Builder set(String name, String value) {
            removeAll(name);
            return add(name, value);
        }

        public Headers build() {
            return new Headers(map);
        }
    }
}
