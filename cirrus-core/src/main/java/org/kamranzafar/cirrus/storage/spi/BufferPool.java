package org.kamranzafar.cirrus.storage.spi;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A JVM-wide pool of upload part buffers. Concurrent multipart uploads reuse buffers instead of
 * allocating (and garbage-collecting) one large array per part. At most {@code cirrus.buffer-pool.max-bytes}
 * (system property, default 256 MiB) is kept idle; beyond that, released buffers are left to the GC.
 */
public final class BufferPool {

    private static final long MAX_IDLE_BYTES = Long.getLong("cirrus.buffer-pool.max-bytes", 256L * 1024 * 1024);
    private static final BufferPool SHARED = new BufferPool(MAX_IDLE_BYTES);

    private final long maxIdleBytes;
    private final Map<Integer, ConcurrentLinkedDeque<byte[]>> idle = new ConcurrentHashMap<>();
    private final AtomicLong idleBytes = new AtomicLong();

    BufferPool(long maxIdleBytes) {
        this.maxIdleBytes = maxIdleBytes;
    }

    public static BufferPool shared() {
        return SHARED;
    }

    /** A buffer of exactly {@code size} bytes; its contents are unspecified. */
    public byte[] acquire(int size) {
        ConcurrentLinkedDeque<byte[]> buffers = idle.get(size);
        byte[] buffer = buffers == null ? null : buffers.pollFirst();
        if (buffer == null) {
            return new byte[size];
        }
        idleBytes.addAndGet(-size);
        return buffer;
    }

    /** Returns a buffer for reuse. The caller must not touch it afterwards. */
    public void release(byte[] buffer) {
        if (buffer == null || idleBytes.get() + buffer.length > maxIdleBytes) {
            return;
        }
        idleBytes.addAndGet(buffer.length);
        idle.computeIfAbsent(buffer.length, s -> new ConcurrentLinkedDeque<>()).offerFirst(buffer);
    }

    long idleBytes() {
        return idleBytes.get();
    }
}
