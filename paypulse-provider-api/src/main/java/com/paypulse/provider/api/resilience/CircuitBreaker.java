package com.paypulse.provider.api.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String name;
    private final int slidingWindowSize;
    private final double failureRateThresholdPercent;
    private final Duration waitDurationInOpenState;
    private final int permittedCallsInHalfOpen;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessfulCalls = new AtomicInteger(0);
    private volatile Instant lastOpenedTimestamp = Instant.EPOCH;

    public CircuitBreaker(
            String name,
            int slidingWindowSize,
            double failureRateThresholdPercent,
            Duration waitDurationInOpenState,
            int permittedCallsInHalfOpen) {
        this.name = name;
        this.slidingWindowSize = slidingWindowSize;
        this.failureRateThresholdPercent = failureRateThresholdPercent;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
    }

    public static CircuitBreaker ofDefaults(String name) {
        return new CircuitBreaker(name, 20, 50.0, Duration.ofSeconds(15), 5);
    }

    public synchronized boolean tryAcquirePermission() {
        State current = state.get();
        if (current == State.OPEN) {
            if (Instant.now().isAfter(lastOpenedTimestamp.plus(waitDurationInOpenState))) {
                // Transition to HALF_OPEN
                state.set(State.HALF_OPEN);
                callCount.set(0);
                failureCount.set(0);
                halfOpenSuccessfulCalls.set(0);
                return true;
            }
            return false;
        } else if (current == State.HALF_OPEN) {
            return callCount.incrementAndGet() <= permittedCallsInHalfOpen;
        }
        return true;
    }

    public synchronized void onSuccess() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            int successes = halfOpenSuccessfulCalls.incrementAndGet();
            if (successes >= permittedCallsInHalfOpen) {
                // Transition back to CLOSED
                state.set(State.CLOSED);
                resetCounters();
            }
        } else if (current == State.CLOSED) {
            int total = callCount.incrementAndGet();
            if (total >= slidingWindowSize) {
                evaluateMetrics();
            }
        }
    }

    public synchronized void onError(Throwable t) {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            // Immediate transition back to OPEN
            tripToOpen();
        } else if (current == State.CLOSED) {
            failureCount.incrementAndGet();
            int total = callCount.incrementAndGet();
            if (total >= slidingWindowSize) {
                evaluateMetrics();
            }
        }
    }

    private void evaluateMetrics() {
        int total = callCount.get();
        if (total == 0) return;
        double failureRate = (failureCount.get() * 100.0) / total;
        if (failureRate >= failureRateThresholdPercent) {
            tripToOpen();
        } else {
            resetCounters();
        }
    }

    private void tripToOpen() {
        state.set(State.OPEN);
        lastOpenedTimestamp = Instant.now();
        resetCounters();
    }

    private void resetCounters() {
        callCount.set(0);
        failureCount.set(0);
        halfOpenSuccessfulCalls.set(0);
    }

    public State getState() { return state.get(); }
    public String getName() { return name; }
    public int getFailureCount() { return failureCount.get(); }
    public int getCallCount() { return callCount.get(); }

    public void forceOpen() {
        tripToOpen();
    }

    public void forceClosed() {
        state.set(State.CLOSED);
        resetCounters();
    }
}
