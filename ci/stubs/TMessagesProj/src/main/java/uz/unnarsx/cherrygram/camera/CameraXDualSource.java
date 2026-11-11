/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Dual-camera CameraX source. isDualSupported() reports false, so StoryRecorder / DualCameraView
 * never create one; open() returns null, which CameraView treats as "no session".
 */

package uz.unnarsx.cherrygram.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;

import androidx.annotation.Nullable;

import org.telegram.messenger.camera.CameraSessionWrapper;
import org.telegram.messenger.camera.Size;

public class CameraXDualSource {

    public interface SessionCallback {
        void run(Session session);
    }

    /** Camera session handed to CameraView in place of a Camera1/Camera2 session. */
    public static class Session extends CameraSessionWrapper {

        @Nullable
        public Size getDisplaySize() {
            return null;
        }

        @Nullable
        public android.util.Size getResolution() {
            return null;
        }
    }

    public CameraXDualSource(Context context, boolean frontface) {
        CameraXStubSupport.ensureCameraXDisabled();
    }

    public static boolean isDualSupported(Context context) {
        CameraXStubSupport.ensureCameraXDisabled();
        return false;
    }

    public static Size estimateDisplaySize() {
        return new Size(1280, 720);
    }

    @Nullable
    public Session open(boolean front, SurfaceTexture surfaceTexture, SessionCallback onDisplaySizeChanged) {
        return null;
    }

    @Nullable
    public CameraXController getController() {
        return null;
    }

    public void setMainFront(boolean front) {
    }

    public float getMainExposureValue() {
        return 0.5f;
    }

    public void setOnMainCameraChanged(Runnable runnable) {
    }

    public void setOnError(Runnable runnable) {
    }

    public void release() {
    }
}
