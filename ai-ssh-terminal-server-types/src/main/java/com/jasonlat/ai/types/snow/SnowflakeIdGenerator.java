package com.jasonlat.ai.types.snow;

import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator {

    private final long workerId = 1L;
    private final static long START_TIMESTAMP = 1625097600000L; // 2021-07-01
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private final static long SEQUENCE_BITS = 12L;
    private final static long WORKER_ID_BITS = 10L;
    private final static long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private final static long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private final static long TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private final static long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards. Refusing to generate id");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT) |
                (workerId << WORKER_ID_SHIFT) |
                sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

}