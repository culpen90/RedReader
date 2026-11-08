/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.views;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.activities.BaseActivity;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.common.LinkHandler;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.common.RRError;
import org.quantumbadger.redreader.reddit.api.RedditPostActions;
import org.quantumbadger.redreader.reddit.prepared.InlinePreviewLoader;
import org.quantumbadger.redreader.reddit.prepared.RedditPreparedPost;
import org.quantumbadger.redreader.reddit.prepared.RedditParsedPost;
import org.quantumbadger.redreader.views.liststatus.ErrorView;

/** A header preview owns its listener only while attached; it never preloads offscreen headers. */
public final class PostHeaderPreviewView extends LinearLayout
		implements InlinePreviewLoader.Listener, ViewTreeObserver.OnPreDrawListener {

	@NonNull private final BaseActivity mActivity;
	@NonNull private final RedditPreparedPost mPost;
	@NonNull private final View mPreview;
	@NonNull private final InlinePreviewHolderView mHolder;
	@NonNull private final ImageView mImage;
	@NonNull private final View mPlayOverlay;
	@NonNull private final LoadingSpinnerView mSpinner;
	@NonNull private final LinearLayout mErrors;
	@Nullable private InlinePreviewLoader mLoader;
	private long mNextPolicyCheck;

	public PostHeaderPreviewView(
			@NonNull final BaseActivity activity,
			@NonNull final RedditPreparedPost post) {

		super(activity);
		mActivity = activity;
		mPost = post;
		setOrientation(VERTICAL);
		inflate(activity, R.layout.post_header_preview, this);
		mPreview = findViewById(R.id.reddit_post_image_preview_outer);
		mHolder = findViewById(R.id.reddit_post_image_preview_holder);
		mImage = findViewById(R.id.reddit_post_image_preview_imageview);
		mPlayOverlay = findViewById(R.id.reddit_post_image_preview_play_overlay);
		mSpinner = new LoadingSpinnerView(activity);
		mHolder.addView(mSpinner);
		mErrors = new LinearLayout(activity);
		mErrors.setOrientation(VERTICAL);
		addView(mErrors);
		setContentDescription(activity.getString(R.string.post_image));
		setOnClickListener(view -> {
			if(!post.isSelf() && post.src.getUrl() != null) {
				LinkHandler.onLinkClicked(activity, post.src.getUrl(), false, post.src.getSrc());
			} else {
				final RedditParsedPost.ImagePreviewDetails
						preview = post.src.getPreview(1, 1);
				if(preview != null) {
					LinkHandler.onLinkClicked(activity, preview.url);
				}
			}
		});
		setOnLongClickListener(view -> {
			RedditPostActions.INSTANCE.showActionMenu(activity, post);
			return true;
		});
		setVisibility(GONE);
		mSpinner.setVisibility(GONE);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		getViewTreeObserver().addOnPreDrawListener(this);
		refresh();
	}

	@Override
	protected void onDetachedFromWindow() {
		final ViewTreeObserver observer = getViewTreeObserver();
		if(observer.isAlive()) {
			observer.removeOnPreDrawListener(this);
		}
		detachLoader();
		super.onDetachedFromWindow();
	}

	private void detachLoader() {
		if(mLoader != null) {
			mLoader.removeListener(this);
			mLoader = null;
		}
		mImage.setImageDrawable(null);
		mSpinner.setVisibility(GONE);
	}

	@Override
	public boolean onPreDraw() {
		if(SystemClock.uptimeMillis() >= mNextPolicyCheck) {
			refresh();
		}
		return true;
	}

	private void refresh() {
		mNextPolicyCheck = SystemClock.uptimeMillis() + 1000;
		if(!PrefsUtility.post_layout().usesLargePreviews() || !mPost.shouldShowInlinePreview()) {
			detachLoader();
			setVisibility(GONE);
			return;
		}
		final InlinePreviewLoader.PreviewDetails details =
				InlinePreviewLoader.calculatePreviewDetails(mActivity, mPost);
		if(details == null) {
			detachLoader();
			setVisibility(GONE);
			return;
		}
		mHolder.setImageSize(details.imageWidthPx, details.imageHeightPx);
		final InlinePreviewLoader loader = mPost.getInlinePreviewLoader(mActivity);
		if(loader != mLoader) {
			detachLoader();
			mLoader = loader;
			if(loader != null) {
				loader.setListener(this);
			}
		} else if(loader != null) {
			loader.refresh();
		}
	}

	@Override
	public void onInlinePreviewStateChanged(@NonNull final InlinePreviewLoader loader) {
		if(loader != mLoader || !isAttachedToWindow()) {
			return;
		}
		mErrors.removeAllViews();
		mPlayOverlay.setVisibility(GONE);
		switch(loader.getState()) {
			case LOADED:
				mImage.setImageBitmap(loader.getBitmap());
				mSpinner.setVisibility(GONE);
				mPreview.setVisibility(VISIBLE);
				setVisibility(VISIBLE);
				if(mPost.isVideoPreview()) {
					mPlayOverlay.setVisibility(VISIBLE);
				}
				break;
			case LOADING:
				mImage.setImageDrawable(null);
				mPreview.setVisibility(VISIBLE);
				mSpinner.setVisibility(VISIBLE);
				setVisibility(VISIBLE);
				break;
			case FAILED:
				mImage.setImageDrawable(null);
				mSpinner.setVisibility(GONE);
				mPreview.setVisibility(GONE);
				final RRError error = loader.getError();
				if(error != null) {
					final ErrorView errorView = new ErrorView(mActivity, error);
					mErrors.addView(errorView);
					General.setLayoutMatchWidthWrapHeight(errorView);
				}
				setVisibility(error == null ? GONE : VISIBLE);
				break;
			case NOT_LOADED:
			default:
				mImage.setImageDrawable(null);
				mSpinner.setVisibility(GONE);
				setVisibility(GONE);
				break;
		}
	}
}
