package okhttp3;

import java.util.ArrayList;
import java.util.List;

/** 任务调度状态（spider 只读队列快照）。 */
public class Dispatcher {

    private final List<Call> queued = new ArrayList<>();
    private final List<Call> running = new ArrayList<>();

    public List<Call> queuedCalls() {
        return new ArrayList<>(queued);
    }

    public List<Call> runningCalls() {
        return new ArrayList<>(running);
    }

    public void enqueue(Call call) {
        synchronized (this) {
            queued.add(call);
        }
    }

    public void dequeue(Call call) {
        synchronized (this) {
            queued.remove(call);
        }
    }

    public void started(Call call) {
        synchronized (this) {
            running.add(call);
        }
    }

    public void finished(Call call) {
        synchronized (this) {
            running.remove(call);
        }
    }
}
