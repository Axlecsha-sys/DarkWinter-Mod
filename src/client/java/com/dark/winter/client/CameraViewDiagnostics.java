// Captures read-only camera context for visual diagnostic reports.
package com.dark.winter.client;

/** Read-only camera context for visual smoke failures; does not control the view. */
public interface CameraViewDiagnostics {
    String darkwinter$describeShoulderView();
}
