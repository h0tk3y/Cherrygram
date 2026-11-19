/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * "Folders at the bottom" is reported as disabled, so DialogsActivity and GlassTabView keep the
 * stock top folder tabs; the remaining methods are only reached when the feature is enabled and
 * are no-ops here.
 */

package uz.unnarsx.cherrygram.core.ui.folders

import android.content.Context
import android.graphics.RectF
import android.view.ViewGroup
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor
import org.telegram.ui.Components.blur3.capture.IBlur3Capture
import org.telegram.ui.DialogsActivity

object FoldersHelper {

    fun moveFoldersToBottom(): Boolean {
        return false
    }

    fun setupFilterTabs(
        context: Context?,
        contentView: ViewGroup?,
        filterTabsView: FilterTabsView?,
        resourcesProvider: Theme.ResourcesProvider?,
        liquidGlassFactory: BlurredBackgroundDrawableViewFactory?,
        fadeFactory: BlurredBackgroundDrawableViewFactory?,
        onlySelect: Boolean
    ) {
    }

    fun updateFoldersOffset(dialogsActivity: DialogsActivity?, onlySelect: Boolean) {
    }

    fun getFloatingButtonsOffset(filterTabsView: FilterTabsView?): Float {
        return 0f
    }

    fun blur3_InvalidateBlur(
        dialogsActivity: DialogsActivity?,
        capture: IBlur3Capture?,
        actionBarPosition: RectF?,
        foldersPosition: RectF?,
        mainTabsPosition: RectF?,
        positions: ArrayList<RectF>?,
        noiseSuppressor: DownscaleScrollableNoiseSuppressor?
    ) {
    }
}
