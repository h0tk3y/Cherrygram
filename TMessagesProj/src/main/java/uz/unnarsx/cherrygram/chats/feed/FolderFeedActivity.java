/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package uz.unnarsx.cherrygram.chats.feed;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatActivityContainer;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/**
 * Screen showing the merged feed of every channel of a chat folder. The feed itself is a
 * {@link ChatActivity} in {@link ChatActivity#MODE_FOLDER_FEED} hosted in a container, the same way
 * the hashtag search hosts its "public posts" chat.
 */
public class FolderFeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_CHANNELS = 1;

    private final int filterId;
    private ChatActivityContainer chatContainer;

    public FolderFeedActivity(int filterId) {
        super();
        this.filterId = filterId;
    }

    public static boolean open(BaseFragment parent, MessagesController.DialogFilter filter) {
        if (parent == null || filter == null || !FolderFeedController.isAvailableForFilter(parent.getCurrentAccount(), filter)) {
            return false;
        }
        // On tablets, open in the right-hand chat pane like a regular chat instead of the floating
        // "layers" popup used for non-chat screens. No effect on phones.
        parent.presentFragment(new INavigationLayout.NavigationParams(new FolderFeedActivity(filter.id)).forceRightLayout());
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        if (FolderFeedController.findFilter(currentAccount, filterId) == null) {
            return false;
        }
        getNotificationCenter().addObserver(this, NotificationCenter.cgFolderFeedProgress);
        getNotificationCenter().addObserver(this, NotificationCenter.cgFolderFeedLoaded);
        return super.onFragmentCreate();
    }

    private int getFeedGuid() {
        return chatContainer != null && chatContainer.chatActivity != null ? chatContainer.chatActivity.getClassGuid() : 0;
    }

    private void setChannelsSubtitle() {
        if (actionBar != null) {
            int count = FolderFeedController.getFeedChannelsCount(currentAccount, FolderFeedController.findFilter(currentAccount, filterId));
            actionBar.setSubtitle(LocaleController.formatPluralString("CG_FolderFeed_ChannelsCount", count));
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (actionBar == null || (Integer) args[0] != getFeedGuid()) {
            return;
        }
        if (id == NotificationCenter.cgFolderFeedProgress) {
            // A large folder takes a while to open: show how many channels are loaded so far.
            actionBar.setSubtitle(LocaleController.formatString(R.string.CG_FolderFeed_Loading, (Integer) args[1], (Integer) args[2]));
        } else if (id == NotificationCenter.cgFolderFeedLoaded) {
            setChannelsSubtitle();
        }
    }

    @Override
    public View createView(Context context) {
        // The view can be rebuilt (e.g. on a theme change) while the fragment lives on: the new container
        // brings a new feed, so release the previous one.
        destroyFeed();
        MessagesController.DialogFilter filter = FolderFeedController.findFilter(currentAccount, filterId);
        List<TLRPC.Chat> channels = FolderFeedController.getFeedChannels(currentAccount, filter);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(filter != null && !TextUtils.isEmpty(filter.name) ? filter.name : getString(R.string.CG_FolderFeed_Title));
        actionBar.setSubtitle(LocaleController.formatPluralString("CG_FolderFeed_ChannelsCount", channels.size()));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CHANNELS) {
                    showChannelsList();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_CHANNELS, R.drawable.msg_folders_channels).setContentDescription(getString(R.string.CG_FolderFeed_Channels));

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        Bundle args = new Bundle();
        args.putInt("chatMode", ChatActivity.MODE_FOLDER_FEED);
        args.putInt("folderFeedFilterId", filterId);
        chatContainer = new ChatActivityContainer(context, getParentLayout(), args) {
            boolean activityCreated = false;

            @Override
            protected void initChatActivity() {
                if (!activityCreated) {
                    activityCreated = true;
                    super.initChatActivity();
                }
            }
        };
        frameLayout.addView(chatContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        return fragmentView;
    }

    private void showChannelsList() {
        if (getParentActivity() == null) {
            return;
        }
        List<TLRPC.Chat> channels = FolderFeedController.getFeedChannels(currentAccount, FolderFeedController.findFilter(currentAccount, filterId));
        if (channels.isEmpty()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.CG_FolderFeed_Channels));
        // AlertDialog already wraps the custom view in a ScrollView, so a plain vertical list is enough.
        LinearLayout listLayout = new LinearLayout(getParentActivity());
        listLayout.setOrientation(LinearLayout.VERTICAL);
        builder.setView(listLayout);
        AlertDialog dialog = builder.create();
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat chat = channels.get(i);
            UserCell cell = new UserCell(getParentActivity(), 16, 0, false, false, getResourceProvider(), false, false) {
                @Override
                public boolean onInterceptTouchEvent(MotionEvent ev) {
                    // The whole row opens the channel: keep the avatar's own profile/story taps from firing.
                    return true;
                }
            };
            // Always a second line, so rows without a known subscriber count stay vertically centred.
            CharSequence status = chat.participants_count > 0 ? LocaleController.formatPluralString("Subscribers", chat.participants_count) : getString(R.string.AccDescrChannel);
            cell.setData(chat, chat.title, status, 0);
            cell.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_dialogButtonSelector), Theme.RIPPLE_MASK_ALL));
            cell.setOnClickListener(v -> {
                dialog.dismiss();
                Bundle args = new Bundle();
                args.putLong("chat_id", chat.id);
                if (getMessagesController().checkCanOpenChat(args, FolderFeedActivity.this)) {
                    presentFragment(new ChatActivity(args));
                }
            });
            listLayout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        showDialog(dialog);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (chatContainer != null) {
            chatContainer.onResume();
        }
    }

    // ChatActivityContainer doesn't forward these; ChatActivity needs them e.g. to open comments.
    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        if (chatContainer != null && chatContainer.chatActivity != null && chatContainer.chatActivity.getFragmentView() != null) {
            chatContainer.chatActivity.onBecomeFullyVisible();
        }
    }

    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        if (chatContainer != null && chatContainer.chatActivity != null && chatContainer.chatActivity.getFragmentView() != null) {
            chatContainer.chatActivity.onBecomeFullyHidden();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (chatContainer != null) {
            chatContainer.onPause();
        }
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.cgFolderFeedProgress);
        getNotificationCenter().removeObserver(this, NotificationCenter.cgFolderFeedLoaded);
        destroyFeed();
        super.onFragmentDestroy();
    }

    /** ChatActivityContainer never destroys its fragment: release its observers and the feed session. */
    private void destroyFeed() {
        if (chatContainer != null && chatContainer.chatActivity != null && chatContainer.chatActivity.getFragmentView() != null) {
            chatContainer.chatActivity.onFragmentDestroy();
        }
        chatContainer = null;
    }
}
