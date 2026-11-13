// Runs independent server light layers concurrently while retaining native completion and notification order.
package com.dark.winter.fixes.performance;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * The existing light coordinator owns BLOCK; one admitted helper owns SKY.
 * Callers must establish native PRE/POST ordering and exclude the server/client owner thread.
 */
public final class ParallelLightUpdates {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("darkwinter.parallelLight", "false"))
            && Runtime.getRuntime().availableProcessors() >= 4;
    private static final Semaphore ADMISSION = new Semaphore(1);
    private static final ThreadLocal<Job> HELPER = new ThreadLocal<>();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            Thread.ofPlatform().name("DarkWinter skylight").daemon(true)
                    .inheritInheritableThreadLocals(false).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private static final LongAdder PAIRED = new LongAdder();
    private static final LongAdder FALLBACK_BUSY = new LongAdder();
    private static final LongAdder FALLBACK_DISABLED = new LongAdder();
    private static final LongAdder FALLBACK_REJECTED = new LongAdder();
    private static final LongAdder PUBLICATIONS = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final AtomicInteger PEAK_PUBLICATIONS = new AtomicInteger();

    private ParallelLightUpdates() {}

    /** Cumulative observations; reading them does not alter admission or configuration. */
    public record Stats(long paired, long fallbackBusy, long fallbackDisabled,
                        long fallbackRejected, long publications, long failures,
                        int peakPublications) {}

    public static Stats stats() {
        return new Stats(PAIRED.sum(), FALLBACK_BUSY.sum(), FALLBACK_DISABLED.sum(),
                FALLBACK_REJECTED.sum(), PUBLICATIONS.sum(), FAILURES.sum(),
                PEAK_PUBLICATIONS.get());
    }

    public static boolean isHelperContext() {
        return HELPER.get() != null;
    }

    /** Identity guard for the one SKY storage selected by the native coordinator. */
    public static boolean isExpectedPublication(Object owner) {
        Job job = HELPER.get();
        return job != null && job.expectedPublicationOwner != null && job.expectedPublicationOwner == owner;
    }

    /** Defers the one native SKY map swap; its callbacks then run on the coordinator after BLOCK. */
    public static void deferPublication(Runnable action) {
        Objects.requireNonNull(action, "action");
        Job job = HELPER.get();
        if (job == null) throw new IllegalStateException("No parallel SKY operation on this thread");
        if (job.publication != null)
            throw new IllegalStateException("A SKY operation cannot publish twice");
        job.publication = action;
    }

    /**
     * No task is queued behind another helper. A rejected/unavailable helper uses the native path.
     * Both computations must own distinct engines; neither may wait for work on the other engine.
     */
    public static int runPair(IntSupplier block, IntSupplier sky, IntSupplier sequential) {
        return runPair(block, sky, sequential, null);
    }

    public static int runPair(IntSupplier block, IntSupplier sky, IntSupplier sequential,
                              Object expectedPublicationOwner) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(sky, "sky");
        Objects.requireNonNull(sequential, "sequential");
        if (!ENABLED || isHelperContext()) {
            FALLBACK_DISABLED.increment();
            return sequential.getAsInt();
        }
        if (!ADMISSION.tryAcquire()) {
            FALLBACK_BUSY.increment();
            return sequential.getAsInt();
        }

        Job job;
        try {
            job = new Job(sky, expectedPublicationOwner);
            EXECUTOR.execute(job);
        } catch (RejectedExecutionException rejected) {
            ADMISSION.release();
            FALLBACK_REJECTED.increment();
            return sequential.getAsInt();
        } catch (Throwable failure) {
            ADMISSION.release();
            throw propagate(failure);
        }

        PAIRED.increment();
        boolean interrupted = false;
        long replayed = 0;
        try {
            int blockResult = 0;
            Throwable blockFailure = null;
            try {
                blockResult = block.getAsInt();
            } catch (Throwable failure) {
                blockFailure = failure;
            }
            // Cancellation/interrupt must never let a later PRE/POST overlap the still-running SKY engine.
            for (;;) {
                try {
                    job.completed.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (job.publication != null) PEAK_PUBLICATIONS.set(1);
            if (blockFailure != null) {
                if (job.failure != null && job.failure != blockFailure)
                    blockFailure.addSuppressed(job.failure);
                throw propagate(blockFailure);
            }
            if (job.failure != null) throw propagate(job.failure);
            if (expectedPublicationOwner != null && job.publication == null)
                throw new IllegalStateException("The selected SKY storage did not defer its publication");
            Runnable publication = job.publication;
            job.publication = null;
            if (publication != null) {
                publication.run();
                replayed = 1;
            }
            return blockResult + job.result;
        } catch (Throwable failure) {
            FAILURES.increment();
            throw propagate(failure);
        } finally {
            PUBLICATIONS.add(replayed);
            job.publication = null;
            ADMISSION.release();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new java.util.concurrent.CompletionException(failure);
    }

    /** The single publication reference crosses threads only after the latch acquire. */
    private static final class Job implements Runnable {
        private IntSupplier sky;
        private Object expectedPublicationOwner;
        private final CountDownLatch completed = new CountDownLatch(1);
        private Runnable publication;
        private int result;
        private Throwable failure;

        private Job(IntSupplier sky, Object expectedPublicationOwner) {
            this.sky = sky;
            this.expectedPublicationOwner = expectedPublicationOwner;
        }

        @Override public void run() {
            try {
                HELPER.set(this);
                result = sky.getAsInt();
            } catch (Throwable thrown) {
                failure = thrown;
            } finally {
                sky = null;
                expectedPublicationOwner = null;
                try {
                    HELPER.remove();
                } finally {
                    completed.countDown();
                }
            }
        }
    }
}
