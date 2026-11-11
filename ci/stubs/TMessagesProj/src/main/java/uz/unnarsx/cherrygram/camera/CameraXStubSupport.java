/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * This class only exists in CI stub builds (it has no closed-source counterpart). The CameraX
 * camera is implemented by the closed-source CameraXController, so in a stub build it cannot
 * work. Whether the CameraX code paths are used is decided by tracked code
 * (CameraXUtils.isCurrentCameraCameraX(), which reads CherrygramCameraConfig.cameraType and
 * defaults to CAMERA_X on capable devices), so the stubs switch that preference back to the stock
 * Telegram camera before any camera UI is built. Every stock camera code path is then used.
 */

package uz.unnarsx.cherrygram.camera;

import org.telegram.messenger.ApplicationLoader;

import uz.unnarsx.cherrygram.core.configs.CherrygramCameraConfig;

public final class CameraXStubSupport {

    private CameraXStubSupport() {
    }

    /** CameraX is never available in a stub build. */
    public static boolean isCameraXAvailable() {
        return false;
    }

    /** Switches the "CameraX" camera type back to the stock Telegram camera. Safe to call often. */
    public static void ensureCameraXDisabled() {
        if (ApplicationLoader.applicationContext == null) {
            // CherrygramCameraConfig needs the context in its initializer; never initialize it early.
            return;
        }
        try {
            if (CherrygramCameraConfig.INSTANCE.getCameraType() == CherrygramCameraConfig.CAMERA_X) {
                CherrygramCameraConfig.INSTANCE.setCameraType(CherrygramCameraConfig.TELEGRAM_CAMERA);
            }
        } catch (Throwable ignore) {
            // Never let the guard break the caller.
        }
    }
}
