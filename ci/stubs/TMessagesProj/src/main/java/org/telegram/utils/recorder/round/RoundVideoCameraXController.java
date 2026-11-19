/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * CameraX-based round-video camera source. The stub delegates everything to the stock Camera2
 * implementation (RoundVideoCameraController), so a session built with setUseCameraX(true) still
 * records normally; the CameraX-only extras (zoom presets, CameraX controller, tap to focus) are
 * reported as unavailable.
 */

package org.telegram.utils.recorder.round;

import android.content.Context;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import uz.unnarsx.cherrygram.camera.CameraXController;
import uz.unnarsx.cherrygram.camera.CherryZoomSliderView;

final class RoundVideoCameraXController extends RoundVideoCameraSource {

    private final RoundVideoCameraController delegate;

    RoundVideoCameraXController(
            @NonNull Context context,
            @NonNull TextureView previewView,
            @NonNull RoundVideoSession.OutputResolution outputResolution,
            int videoBitrate,
            @NonNull RoundVideoSession.CameraResolution cameraResolution,
            @NonNull RoundVideoSession.FrameRate frameRate,
            boolean compositionEnabled,
            @NonNull RoundVideoDiagnostics diagnostics,
            @NonNull RoundVideoCameraController.Callback callback
    ) {
        delegate = new RoundVideoCameraController(
                context,
                previewView,
                outputResolution,
                videoBitrate,
                cameraResolution,
                frameRate,
                compositionEnabled,
                diagnostics,
                callback
        );
    }

    @Override
    void startSegment(
            @NonNull RoundVideoMp4Writer writer,
            long timelineOffsetUs,
            @NonNull RoundVideoSession.CameraFacing facing
    ) {
        delegate.startSegment(writer, timelineOffsetUs, facing);
    }

    @Override
    boolean setCameraFacing(@NonNull RoundVideoSession.CameraFacing facing) {
        return delegate.setCameraFacing(facing);
    }

    @Override
    boolean setZoom(float progress) {
        return delegate.setZoom(progress);
    }

    @Override
    boolean setTorchEnabled(boolean enabled) {
        return delegate.setTorchEnabled(enabled);
    }

    @Override
    boolean stopSegment() {
        return delegate.stopSegment();
    }

    @Override
    void release() {
        delegate.release();
    }

    @Nullable
    CherryZoomSliderView.ZoomController getZoomController() {
        return null;
    }

    @Nullable
    CameraXController getCameraXController() {
        return null;
    }

    void focusToPoint(float x, float y) {
    }
}
