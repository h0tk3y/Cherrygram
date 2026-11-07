/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Preview of the bottom main tabs in MainTabsPreferencesEntry. The real cell lets the user drag
 * and toggle tabs; the stub only shows the enabled tabs (read-only) and never reports changes.
 */

package uz.unnarsx.cherrygram.preferences.tabs;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import uz.unnarsx.cherrygram.core.ui.mainTabs.MainTabsManager;

public class MainTabsPreviewCell extends FrameLayout {

    public interface OnTabsChangedListener {
        void onTabsChanged();
    }

    private final LinearLayout tabsLayout;
    private boolean editMode;
    private OnTabsChangedListener onTabsChangedListener;

    public MainTabsPreviewCell(Context context) {
        super(context);
        tabsLayout = new LinearLayout(context);
        tabsLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabsLayout.setGravity(Gravity.CENTER);
        addView(tabsLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
    }

    public void setOnTabsChangedListener(OnTabsChangedListener listener) {
        onTabsChangedListener = listener;
    }

    public void setTabs(
            ArrayList<MainTabsManager.Tab> tabs,
            Context context,
            Theme.ResourcesProvider resourcesProvider,
            int currentAccount,
            boolean fromSettings,
            boolean showSearch
    ) {
        tabsLayout.removeAllViews();
        if (tabs == null || context == null) {
            return;
        }
        for (MainTabsManager.Tab tab : tabs) {
            if (tab == null || !tab.enabled) {
                continue;
            }
            try {
                View tabView = MainTabsManager.INSTANCE.createTabView(context, resourcesProvider, currentAccount, tab.getType(), fromSettings, showSearch);
                tabsLayout.addView(tabView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }
}
