/*
 * CI build stub — the real implementation is closed-source and not in this repository.
 *
 * Compact (icon row) variant of the new message menu. allowCompactStyle() reports false, so
 * ChatActivity and CGMessageMenuInjector keep the stock menu items and never construct this view.
 */

package uz.unnarsx.cherrygram.chats.ui;

import android.annotation.SuppressLint;
import android.widget.FrameLayout;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ChatActivity;

@SuppressLint("ViewConstructor")
public class MessageMenuCompactView extends FrameLayout {

    public static boolean allowCompactStyle() {
        return false;
    }

    public MessageMenuCompactView(
            ChatActivity chatActivity,
            MessageObject selectedObject,
            int optionsCount,
            boolean allowReply,
            boolean allowEdit,
            boolean allowForward,
            boolean allowCopy,
            boolean allowCopyPhoto,
            boolean allowCopyLink,
            boolean allowDelete
    ) {
        super(chatActivity != null && chatActivity.getContext() != null ? chatActivity.getContext() : ApplicationLoader.applicationContext);
        setVisibility(GONE);
    }
}
