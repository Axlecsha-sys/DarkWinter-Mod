// Exposes the native container-panel origin for custom GUI surfaces.
package com.dark.winter.client;

/** Native panel origin; no slot or input state is changed. */
public interface ContainerSurfaceAccess {
    int darkwinter$panelLeft();
    int darkwinter$panelTop();
}
