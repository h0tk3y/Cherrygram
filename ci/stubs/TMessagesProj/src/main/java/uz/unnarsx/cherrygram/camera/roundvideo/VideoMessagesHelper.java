/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Round-video (video message) helpers for the CameraX camera. CameraX is unavailable in stub
 * builds (see CameraXStubSupport), so the CameraX entry points are no-ops; flash handling is
 * delegated to the view's own stock implementation (InstantCameraViewBase.applyFlashState()).
 */

package uz.unnarsx.cherrygram.camera.roundvideo;

import android.graphics.SurfaceTexture;
import android.util.Range;

import androidx.annotation.Nullable;

import org.telegram.ui.Components.InstantCameraViewBase;

import uz.unnarsx.cherrygram.camera.CameraXController;
import uz.unnarsx.cherrygram.camera.CameraXStubSupport;

public class VideoMessagesHelper {

    /** Set by the camera views; always null in stub builds. */
    @Nullable
    public CameraXController cameraXController;

    public VideoMessagesHelper() {
        // Constructed from InstantCameraView/InstantCameraView2 field initializers, i.e. before
        // they check CameraXUtils.isCurrentCameraCameraX().
        CameraXStubSupport.ensureCameraXDisabled();
    }

    /** 60 fps round videos are a CameraX feature; always 30 fps here. */
    public static boolean isCamera60Fps() {
        return false;
    }

    /**
     * AE target FPS range for Camera2Session video recording. null clears the key in the
     * CaptureRequest, i.e. the camera's default (stock Telegram behaviour).
     */
    @Nullable
    public static Range<Integer> getCameraXFpsRange() {
        return null;
    }

    public void createSingleCameraX(InstantCameraViewBase cameraView, SurfaceTexture surfaceTexture) {
    }

    public void createDualCameraX(InstantCameraViewBase cameraView, SurfaceTexture[] surfaceTextures) {
    }

    public void switchCameraX(InstantCameraViewBase cameraView) {
    }

    public void prepareCameraSwitch(InstantCameraViewBase cameraView) {
    }

    public void destroyCameraX(InstantCameraViewBase cameraView) {
        cameraXController = null;
    }

    /** Applies the current flash state using the view's stock implementation. */
    public void checkFlash(InstantCameraViewBase cameraView) {
        if (cameraView != null) {
            cameraView.applyFlashState();
        }
    }

    public void updateCameraXFlash(InstantCameraViewBase cameraView) {
        checkFlash(cameraView);
    }

    /** Long-press flash configurator (CameraX only): not handled. */
    public boolean createFlashConfigurator(InstantCameraViewBase cameraView) {
        return false;
    }

    public void showExposureControls(InstantCameraViewBase cameraView, boolean show) {
    }
}
