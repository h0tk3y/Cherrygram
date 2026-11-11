/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Animated front/back camera switch inside a recording round video. The stub never becomes
 * active (begin() returns false), so InstantCameraView keeps its stock switch behaviour.
 * It allocates no GL resources.
 */

package uz.unnarsx.cherrygram.camera.roundvideo;

public class InstantCameraSwitchTransition {

    public InstantCameraSwitchTransition(int videoWidth) {
    }

    public boolean begin(int frameTexture, boolean toFront) {
        return false;
    }

    public boolean isActive() {
        return false;
    }

    public boolean isWaitingForCamera() {
        return false;
    }

    /** @return true when the first frame from the new camera has arrived. */
    public boolean onCameraFrame(int frameTexture) {
        return false;
    }

    /** @return true when the transition has finished. */
    public boolean render(int frameTexture) {
        return true;
    }

    public long getNextFrameDelayMs() {
        return 16L;
    }

    public void cancel() {
    }

    public void destroy() {
    }
}
