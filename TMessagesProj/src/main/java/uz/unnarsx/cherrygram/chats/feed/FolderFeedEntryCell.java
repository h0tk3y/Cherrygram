/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package uz.unnarsx.cherrygram.chats.feed;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.LayoutHelper;

/** Pinned "All channels" row shown on top of a folder's chat list; opens {@link FolderFeedActivity}. */
public class FolderFeedEntryCell extends FrameLayout {

    private final ImageView imageView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final Theme.ResourcesProvider resourcesProvider;
    private MessagesController.DialogFilter filter;

    public FolderFeedEntryCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        CombinedDrawable drawable = Theme.createCircleDrawableWithIcon(dp(54), R.drawable.msg_folders_channels);
        drawable.setIconSize(dp(28), dp(28));
        Theme.setCombinedDrawableColor(drawable, Theme.getColor(Theme.key_avatar_backgroundSaved, resourcesProvider), false);
        Theme.setCombinedDrawableColor(drawable, Theme.getColor(Theme.key_avatar_text, resourcesProvider), true);
        imageView.setImageDrawable(drawable);
        addView(imageView, LayoutHelper.createFrame(54, 54, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 10, 0, 10, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_chats_name, resourcesProvider));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        titleView.setText(getString(R.string.CG_FolderFeed_Title));
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 16 : 76, 13, LocaleController.isRTL ? 76 : 16, 0));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitleView.setTextColor(Theme.getColor(Theme.key_chats_message, resourcesProvider));
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), LocaleController.isRTL ? 16 : 76, 39, LocaleController.isRTL ? 76 : 16, 0));

        setBackground(Theme.getSelectorDrawable(false));
    }

    public void setFilter(int account, MessagesController.DialogFilter filter) {
        this.filter = filter;
        int count = FolderFeedController.getFeedChannelsCount(account, filter);
        subtitleView.setText(LocaleController.formatString(R.string.CG_FolderFeed_EntrySubtitle, LocaleController.formatPluralString("CG_FolderFeed_ChannelsCount", count)));
    }

    public MessagesController.DialogFilter getFilter() {
        return filter;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(72), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (Theme.dividerPaint != null) {
            if (LocaleController.isRTL) {
                canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth() - dp(76), getMeasuredHeight() - 1, Theme.dividerPaint);
            } else {
                canvas.drawLine(dp(76), getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
