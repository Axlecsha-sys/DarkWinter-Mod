// Tracks shoulder selection, camera transitions, and smoothed side offsets.

package com.dark.winter.client;

public final class ShoulderCameraState {

    // Настройки камеры
    private static final float SHOULDER_DISTANCE = 0.8F;
    private static final float SMOOTHING_SPEED = 9.0F;

    private static boolean rightShoulder = true;
    private static float currentSide = SHOULDER_DISTANCE;
    private static long lastUpdateNanos = 0L;

    private ShoulderCameraState() {}

    public static boolean isRightShoulder() {
        return rightShoulder;
    }

    public static void toggleShoulder() {
        rightShoulder = !rightShoulder;
    }

    public static float updateSideOffset() {
        long now = System.nanoTime();

        float deltaSeconds = lastUpdateNanos == 0L
                ? 0.0F
                : Math.min(
                (now - lastUpdateNanos) / 1_000_000_000.0F,
                0.1F
        );

        lastUpdateNanos = now;

        float target = rightShoulder
                ? SHOULDER_DISTANCE
                : -SHOULDER_DISTANCE;

        float alpha = (float) (
                1.0D - Math.exp(-SMOOTHING_SPEED * deltaSeconds)
        );

        currentSide += (target - currentSide) * alpha;

        if (Math.abs(target - currentSide) < 0.001F) {
            currentSide = target;
        }

        return currentSide;
    }

    public static void pauseSmoothing() {
        lastUpdateNanos = 0L;
    }

    private static boolean manualToFirst = false;

    public static void beginManualToFirst() {
        manualToFirst = true;
    }

    public static boolean isManualToFirst() {
        return manualToFirst;
    }

    public static void finishManualToFirst() {
        manualToFirst = false;
    }
}
