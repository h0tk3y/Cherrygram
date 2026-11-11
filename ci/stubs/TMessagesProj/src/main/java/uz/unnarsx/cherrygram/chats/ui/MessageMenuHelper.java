/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Cherrygram's redesigned message context menu. allowNewMessageMenu() reports false, so
 * ChatActivity and the other callers build the stock Telegram menu; the colours and dividers
 * returned here are the stock ones. The menu-building methods are only reached when the new menu
 * is enabled and are no-ops.
 */

package uz.unnarsx.cherrygram.chats.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ReactionsContainerLayout;

public class MessageMenuHelper {

    private static volatile MessageMenuHelper instance;

    /** Stateless in the stub, so one instance serves every account. */
    public static MessageMenuHelper getInstance(int account) {
        MessageMenuHelper local = instance;
        if (local == null) {
            synchronized (MessageMenuHelper.class) {
                local = instance;
                if (local == null) {
                    instance = local = new MessageMenuHelper();
                }
            }
        }
        return local;
    }

    /** Stock popup menu background. */
    public static int getMessageMenuBackgroundColor(Theme.ResourcesProvider resourcesProvider) {
        return Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider);
    }

    /** Stock popup menu gap (separator) colour. */
    public static int getMessageMenuGapColor(Theme.ResourcesProvider resourcesProvider) {
        return Theme.getColor(Theme.key_actionBarDefaultSubmenuSeparator, resourcesProvider);
    }

    public static boolean isMenuOnRight(View view, MessageObject messageObject) {
        return false;
    }

    public boolean allowNewMessageMenu() {
        return false;
    }

    public boolean allowNewMessageMenu(MessageObject messageObject) {
        return false;
    }

    /** Stock menus show the gap views between sections. */
    public boolean showDivider() {
        return true;
    }

    /** Use the stock gap view instead of the custom-coloured one. */
    public boolean showCustomDivider(boolean inChat) {
        return false;
    }

    public long getCloseAnimationDuration() {
        return 0L;
    }

    public void createMenu(
            ChatActivity chatActivity,
            View messageView,
            ViewGroup scrimPopupContainerLayout,
            ReactionsContainerLayout reactionsLayout,
            MessageObject messageObject,
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout
    ) {
    }

    public void hideMessageView(View messageView, ChatActivity chatActivity, Object chatAdapter, MessageObject messageObject, Activity activity, boolean hide) {
    }

    public void hideMessageView(View messageView, ChatActivity chatActivity, Object chatAdapter, MessageObject messageObject, Activity activity, boolean hide, boolean animated) {
    }

    /** Draws the message copy shown above the chat while the new menu is open: nothing to draw. */
    public void drawCopyInChat(Canvas canvas, View parent) {
    }

    /** Runs the action right away (there is no custom close animation to wait for). */
    public void runAfterMenuClosed(Runnable runnable) {
        if (runnable != null) {
            AndroidUtilities.runOnUIThread(runnable);
        }
    }

    /** Window background blur behind dialogs/menus: disabled. */
    public void checkBlur(Activity activity, boolean show, boolean animated, float radius) {
    }

    /** LinearLayout with an optional maximum height. */
    public static class MaxHeightLinearLayout extends LinearLayout {

        private int maxHeight;
        private int maxWidth;

        public MaxHeightLinearLayout(Context context) {
            super(context);
        }

        public void setMaxHeight(int maxHeight) {
            this.maxHeight = maxHeight;
            requestLayout();
        }

        public int getMaxHeight() {
            return maxHeight;
        }

        public void setMaxWidth(int maxWidth) {
            this.maxWidth = maxWidth;
            requestLayout();
        }

        public int getMaxWidth() {
            return maxWidth;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (maxHeight > 0) {
                int size = MeasureSpec.getSize(heightMeasureSpec);
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                if (mode == MeasureSpec.UNSPECIFIED || size > maxHeight) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
                }
            }
            if (maxWidth > 0) {
                int size = MeasureSpec.getSize(widthMeasureSpec);
                int mode = MeasureSpec.getMode(widthMeasureSpec);
                if (mode == MeasureSpec.UNSPECIFIED || size > maxWidth) {
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
