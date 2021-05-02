// This is a workaround until this issue is resolved: https://github.com/grpc/grpc-java/issues/7456
// Basically we just want to to signficantly reduce the backoff duration (max of 1 second) vs. the hard-coded value
// which is way too long (max of 2 minutes, come on!)
package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Retry Policy for Transport reconnection.  Initial parameters from
 * https://github.com/grpc/grpc/blob/master/doc/connection-backoff.md
 *
 * <p>TODO(carl-mastrangelo): add unit tests for this class
 */
public final class ExponentialBackoffPolicy implements BackoffPolicy {
    public static final class Provider implements BackoffPolicy.Provider {
        @Override
        public BackoffPolicy get() {
            return new ExponentialBackoffPolicy();
        }
    }

    private Random random = new Random();
    private long initialBackoffNanos = TimeUnit.MILLISECONDS.toNanos(100); // Overridden
    private long maxBackoffNanos = TimeUnit.SECONDS.toNanos(1);  // Overridden
    private double multiplier = 1.6;
    private double jitter = .2;

    private long nextBackoffNanos = initialBackoffNanos;

    @Override
    public long nextBackoffNanos() {
        long currentBackoffNanos = nextBackoffNanos;
        nextBackoffNanos = Math.min((long) (currentBackoffNanos * multiplier), maxBackoffNanos);
        return currentBackoffNanos
                + uniformRandom(-jitter * currentBackoffNanos, jitter * currentBackoffNanos);
    }

    private long uniformRandom(double low, double high) {
        checkArgument(high >= low);
        double mag = high - low;
        return (long) (random.nextDouble() * mag + low);
    }

    /*
     * No guice and no flags means we get to implement these setters for testing ourselves.  Do not
     * call these from non-test code.
     */

    @VisibleForTesting
    ExponentialBackoffPolicy setRandom(Random random) {
        this.random = random;
        return this;
    }

    @VisibleForTesting
    ExponentialBackoffPolicy setInitialBackoffNanos(long initialBackoffNanos) {
        this.initialBackoffNanos = initialBackoffNanos;
        return this;
    }

    @VisibleForTesting
    ExponentialBackoffPolicy setMaxBackoffNanos(long maxBackoffNanos) {
        this.maxBackoffNanos = maxBackoffNanos;
        return this;
    }

    @VisibleForTesting
    ExponentialBackoffPolicy setMultiplier(double multiplier) {
        this.multiplier = multiplier;
        return this;
    }

    @VisibleForTesting
    ExponentialBackoffPolicy setJitter(double jitter) {
        this.jitter = jitter;
        return this;
    }
}
