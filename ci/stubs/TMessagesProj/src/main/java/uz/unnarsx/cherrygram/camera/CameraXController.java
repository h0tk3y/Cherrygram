/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Inert CameraX controller: it never binds a camera, reports every capability as unavailable and
 * never calls back. CameraXStubSupport switches the camera type to the stock Telegram camera so
 * this class is normally not reached; if it is, the camera preview simply stays black.
 */

package uz.unnarsx.cherrygram.camera;

import android.content.Context;
import android.util.Range;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import org.telegram.messenger.camera.Size;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class CameraXController implements CherryZoomSliderView.ZoomController {

    public static final int CAMERA_NONE = 0;
    public static final int CAMERA_NIGHT = 1;
    public static final int CAMERA_HDR = 2;
    public static final int CAMERA_AUTO = 3;
    public static final int CAMERA_WIDE = 4;
    public static final int CAMERA_ASPECT_RATIO_SELECTOR = 5;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CAMERA_NONE, CAMERA_NIGHT, CAMERA_HDR, CAMERA_AUTO, CAMERA_WIDE, CAMERA_ASPECT_RATIO_SELECTOR})
    public @interface EffectFacing {
    }

    /** Lifecycle owner handed to CameraX by CameraXView. Must be used on the main thread. */
    public static class CameraLifecycle implements LifecycleOwner {

        private final LifecycleRegistry lifecycleRegistry;

        public CameraLifecycle() {
            lifecycleRegistry = new LifecycleRegistry(this);
            lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        }

        public void start() {
            if (!isDestroyed()) {
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
            }
        }

        public void pause() {
            if (!isDestroyed()) {
                lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
            }
        }

        public void destroy() {
            if (!isDestroyed()) {
                lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
            }
        }

        public boolean isDestroyed() {
            return lifecycleRegistry.getCurrentState() == Lifecycle.State.DESTROYED;
        }

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return lifecycleRegistry;
        }
    }

    private boolean frontface;
    private int cameraEffect = CAMERA_NONE;

    public CameraXController(CameraLifecycle lifecycle, MeteringPointFactory meteringPointFactory, Preview.SurfaceProvider surfaceProvider) {
        CameraXStubSupport.ensureCameraXDisabled();
    }

    public static boolean hasGoodCamera(Context context) {
        CameraXStubSupport.ensureCameraXDisabled();
        return false;
    }

    @Nullable
    public static Range<Float> getZoomRatioRange(CameraInfo cameraInfo) {
        return null;
    }

    public void initCamera(Context context, boolean useFrontCamera, Runnable onPreInit) {
        frontface = useFrontCamera;
        // Never binds a camera, so onPreInit is intentionally never called.
    }

    public boolean isInitiated() {
        return false;
    }

    public boolean isCameraReady() {
        return false;
    }

    public void setFrontFace(boolean frontface) {
        this.frontface = frontface;
    }

    public boolean isFrontface() {
        return frontface;
    }

    public boolean hasFrontFaceCamera() {
        return false;
    }

    public void switchCamera() {
    }

    public void bindUseCasesKeepingZoom() {
    }

    public void closeCamera() {
    }

    public void setPreviewRotation(int rotation) {
    }

    public void setCaptureRotation(int rotation) {
    }

    public int getDisplayOrientation() {
        return 0;
    }

    public void setCameraEffect(@EffectFacing int effect) {
        cameraEffect = effect;
    }

    public int getCameraEffect() {
        return cameraEffect;
    }

    public boolean isAvailableHdrMode() {
        return false;
    }

    public boolean isAvailableWideMode() {
        return false;
    }

    public boolean isAvailableNightMode() {
        return false;
    }

    public boolean isAvailableAutoMode() {
        return false;
    }

    public void setUltraWideStartAllowed(boolean allowed) {
    }

    public int setNextFlashMode() {
        return ImageCapture.FLASH_MODE_OFF;
    }

    public int getCurrentFlashMode() {
        return ImageCapture.FLASH_MODE_OFF;
    }

    public boolean isFlashAvailable() {
        return false;
    }

    public void setTorchEnabled(boolean enabled) {
    }

    public void setZoom(float value) {
    }

    public float resetZoom() {
        return 0f;
    }

    @Override
    public float getZoomRatio() {
        return 1f;
    }

    @Override
    public void setZoomRatio(float ratio) {
    }

    @Override
    public float getMinZoomRatio() {
        return 1f;
    }

    @Override
    public float getMaxZoomRatio() {
        return 1f;
    }

    public boolean isExposureCompensationSupported() {
        return false;
    }

    public void setExposureCompensation(float value) {
    }

    public void focusToPoint(int x, int y, boolean visible) {
    }

    @Nullable
    public Size getPreviewSize() {
        return null;
    }

    public void recordVideo(File path, boolean mirror, CameraXView.VideoSavedCallback onStop) {
    }

    public void stopVideoRecording(boolean abandon) {
    }

    public void takePicture(File file, Runnable onTake) {
    }
}
