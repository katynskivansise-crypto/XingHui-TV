package okhttp3;

/** 事件监听（spider 只用它做占位包装，不发事件）。 */
public class EventListener {

    public static final EventListener NONE = new EventListener();

    public interface Factory {
        EventListener create(Call call);
    }

    public static final Factory NONE_FACTORY = new Factory() {
        @Override
        public EventListener create(Call call) {
            return NONE;
        }
    };
}
