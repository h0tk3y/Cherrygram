/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Zoom preset slider for the CameraX camera. The stub view stays GONE and never shows any
 * content, so it takes no space and intercepts no touches; the stock zoom controls are used.
 */

package uz.unnarsx.cherrygram.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

@SuppressLint("ViewConstructor")
public class CherryZoomSliderView extends FrameLayout {

    /** Zoom source the slider can be bound to (CameraXController implements it). */
    public interface ZoomController {
        float getZoomRatio();

        void setZoomRatio(float ratio);

        float getMinZoomRatio();

        float getMaxZoomRatio();
    }

    public interface OnPresetSelectedListener {
        void onPresetSelected(float zoom);
    }

    private ZoomController zoomController;
    private OnPresetSelectedListener onPresetSelectedListener;

    public CherryZoomSliderView(Context context, Theme.ResourcesProvider resourcesProvider, boolean dark) {
        super(context);
        CameraXStubSupport.ensureCameraXDisabled();
        setVisibility(View.GONE);
    }

    public void bindCamera(@Nullable ZoomController controller) {
        zoomController = controller;
    }

    public void unbindCamera() {
        zoomController = null;
    }

    public void prepareForCameraChange() {
    }

    public void hideImmediately() {
        setVisibility(View.GONE);
    }

    public void collapse() {
    }

    public void scaleZoom(float factor) {
    }

    public void setOnPresetSelectedListener(OnPresetSelectedListener listener) {
        onPresetSelectedListener = listener;
    }

    public void setLiquidGlassBackground(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
    }
}
