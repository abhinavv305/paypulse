package com.paypulse.provider.api.resilience;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Bulkhead {

    private final String name;
    private final Semaphore semaphore;
    private final int maxConcurrentCalls;

    public Bulkhead(String name, int maxConcurrentCalls) {
        this.name = name;
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.semaphore = new Semaphore(maxConcurrentCalls, true);
    }

    public boolean tryAcquire(long timeoutMs) {
        try {
            return semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        semaphore.release();
    }

    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }

    public String getName() {
        return name;
    }
}
