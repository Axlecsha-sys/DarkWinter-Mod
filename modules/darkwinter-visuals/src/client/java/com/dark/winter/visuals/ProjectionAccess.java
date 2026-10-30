// Exposes the captured camera projection matrix to the visual renderer.
package com.dark.winter.visuals;
import org.joml.Matrix4f;
public interface ProjectionAccess {
    Matrix4f darkwinter$renderProjection();
}
