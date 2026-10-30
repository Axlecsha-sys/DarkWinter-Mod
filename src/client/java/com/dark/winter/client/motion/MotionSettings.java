// Defines immutable motion settings and defaults for persisted configuration.
package com.dark.winter.client.motion;

/** Immutable settings; old motion.properties files receive defaults for new keys. */
public record MotionSettings(
        boolean enabled, boolean locomotion, boolean camera, boolean body,
        boolean footsteps, boolean clothing,
        double walkSpeed, double startSeconds,
        double cameraWeight, double turnWeight, double stepWeight,
        double walkStepBlocks, double runStepBlocks,
        double stepVolume, double clothVolume, double bodyWeight,
        double backwardSpeed, double strafeSpeed, double reverseSeconds,
        boolean lookControl, double lookResponseSeconds,
        double maxYawSpeed, double maxPitchSpeed
) {
    /** Derive laboratory policy without rewriting the shared motion configuration. */
    public MotionSettings testing(boolean movementEnabled,boolean cameraEnabled) {
        return new MotionSettings(movementEnabled || cameraEnabled,
                movementEnabled,cameraEnabled,body && movementEnabled,
                footsteps && movementEnabled,clothing && movementEnabled,
                walkSpeed,startSeconds,cameraWeight,turnWeight,stepWeight,walkStepBlocks,runStepBlocks,
                stepVolume,clothVolume,bodyWeight,backwardSpeed,strafeSpeed,reverseSeconds,
                lookControl && cameraEnabled,lookResponseSeconds,maxYawSpeed,maxPitchSpeed);
    }
    /** Per-save laboratory numbers; the immutable source and its global configuration stay unchanged. */
    public MotionSettings testingNumbers(double testingWalkSpeed,double testingCameraWeight,double testingTurnWeight){
        return new MotionSettings(enabled,locomotion,camera,body,footsteps,clothing,
                testingWalkSpeed,startSeconds,testingCameraWeight,testingTurnWeight,stepWeight,walkStepBlocks,runStepBlocks,
                stepVolume,clothVolume,bodyWeight,backwardSpeed,strafeSpeed,reverseSeconds,
                lookControl,lookResponseSeconds,maxYawSpeed,maxPitchSpeed);
    }
    public static final MotionSettings DEFAULT = new MotionSettings(
            true, true, true, true, true, false,
            0.82, 0.14, 1.0, 1.0, 1.0,
            1.65, 2.05, 0.30, 0.0, 1.0,
            0.60, 0.80, 0.10, true, 0.085, 210.0, 150.0);
}
