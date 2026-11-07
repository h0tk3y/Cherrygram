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
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatActivityContainer;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Screen showing the merged feed of every channel of a chat folder. The feed itself is a
 * {@link ChatActivity} in {@link ChatActivity#MODE_FOLDER_FEED} hosted in a container, the same way
 * the hashtag search hosts its "public posts" chat.
 */
public class FolderFeedActivity extends BaseFragment {

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
        parent.presentFragment(new FolderFeedActivity(filter.id));
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        if (FolderFeedController.findFilter(currentAccount, filterId) == null) {
            return false;
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        MessagesController.DialogFilter filter = FolderFeedController.findFilter(currentAccount, filterId);
        ArrayList<TLRPC.Chat> channels = FolderFeedController.getFeedChannels(currentAccount, filter);

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
        ArrayList<TLRPC.Chat> channels = FolderFeedController.getFeedChannels(currentAccount, FolderFeedController.findFilter(currentAccount, filterId));
        if (channels.isEmpty()) {
            return;
        }
        CharSequence[] titles = new CharSequence[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            titles[i] = channels.get(i).title;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.CG_FolderFeed_Channels));
        builder.setItems(titles, (dialog, which) -> {
            if (which < 0 || which >= channels.size()) {
                return;
            }
            Bundle args = new Bundle();
            args.putLong("chat_id", channels.get(which).id);
            if (getMessagesController().checkCanOpenChat(args, FolderFeedActivity.this)) {
                presentFragment(new ChatActivity(args));
            }
        });
        showDialog(builder.create());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (chatContainer != null) {
            chatContainer.onResume();
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
        // ChatActivityContainer never destroys its fragment: release its observers and the feed session.
        if (chatContainer != null && chatContainer.chatActivity != null && chatContainer.chatActivity.getFragmentView() != null) {
            chatContainer.chatActivity.onFragmentDestroy();
        }
        super.onFragmentDestroy();
    }
}
