// Exposes shared entity state for collision-clipped terrain climbing.
package com.dark.winter.movement;

/** Common state bridge; implemented on Entity, with no client class references. */
public interface TerrainStepAccess {
    TerrainStepMotion darkwinter$stepMotion();
}
