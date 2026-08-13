package com.bilipartfix;

import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded access-order cache with shorter retention for negative lookups. */
final class MemoryCache<K, V> {
    static final class Lookup<V> {
        final boolean present;
        final V value;

        private Lookup(boolean present, V value) {
            this.present = present;
            this.value = value;
        }
    }

    private static final class Entry<V> {
        final V value;
        final long expiresAt;

        Entry(V value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private final int capacity;
    private final long successTtlMs;
    private final long negativeTtlMs;
    private final LinkedHashMap<K, Entry<V>> values =
            new LinkedHashMap<>(16, 0.75f, true);

    MemoryCache(int capacity, long successTtlMs, long negativeTtlMs) {
        this.capacity = capacity;
        this.successTtlMs = successTtlMs;
        this.negativeTtlMs = negativeTtlMs;
    }

    synchronized Lookup<V> get(K key) {
        Entry<V> entry = values.get(key);
        if (entry == null) return new Lookup<>(false, null);
        if (SystemClock.elapsedRealtime() >= entry.expiresAt) {
            values.remove(key);
            return new Lookup<>(false, null);
        }
        return new Lookup<>(true, entry.value);
    }

    synchronized void put(K key, V value) {
        long ttl = value == null ? negativeTtlMs : successTtlMs;
        values.put(key, new Entry<>(value, SystemClock.elapsedRealtime() + ttl));
        while (values.size() > capacity) {
            K eldest = values.entrySet().iterator().next().getKey();
            values.remove(eldest);
        }
    }

    synchronized int size() {
        return values.size();
    }
}
