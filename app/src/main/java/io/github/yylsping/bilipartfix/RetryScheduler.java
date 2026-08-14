package io.github.yylsping.bilipartfix;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Coalesces page retries without retaining destroyed activities or views. */
final class RetryScheduler {
    interface Task<T> {
        /** Return true when later retries for this generation are unnecessary. */
        boolean run(T owner);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Map<String, Integer>> GENERATIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RetryScheduler() {}

    static <T> void schedule(T owner, String channel, long[] delays, Task<T> task) {
        int generation;
        synchronized (GENERATIONS) {
            Map<String, Integer> channels = GENERATIONS.computeIfAbsent(
                    owner, ignored -> new HashMap<>());
            generation = channels.getOrDefault(channel, 0) + 1;
            channels.put(channel, generation);
        }
        WeakReference<T> reference = new WeakReference<>(owner);
        for (long delay : delays) {
            int expected = generation;
            MAIN.postDelayed(() -> {
                T current = reference.get();
                if (current == null || !isCurrent(current, channel, expected)) return;
                if (task.run(current)) finish(current, channel, expected);
            }, delay);
        }
    }

    private static boolean isCurrent(Object owner, String channel, int expected) {
        synchronized (GENERATIONS) {
            Map<String, Integer> channels = GENERATIONS.get(owner);
            return channels != null && channels.getOrDefault(channel, -1) == expected;
        }
    }

    private static void finish(Object owner, String channel, int expected) {
        synchronized (GENERATIONS) {
            Map<String, Integer> channels = GENERATIONS.get(owner);
            if (channels != null && channels.getOrDefault(channel, -1) == expected) {
                channels.put(channel, expected + 1);
            }
        }
    }
}
