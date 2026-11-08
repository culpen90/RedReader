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

package org.quantumbadger.redreader.fragments;

import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.account.RedditAccount;
import org.quantumbadger.redreader.account.RedditAccountManager;
import org.quantumbadger.redreader.cache.CacheManager;
import org.quantumbadger.redreader.cache.CacheRequest;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.common.RRError;
import org.quantumbadger.redreader.common.TimestampBound;
import org.quantumbadger.redreader.reddit.api.MultiredditApi;
import org.quantumbadger.redreader.reddit.api.MultiredditTarget;
import org.quantumbadger.redreader.reddit.api.RedditMultiredditSubscriptionManager;
import org.quantumbadger.redreader.reddit.things.InvalidSubredditNameException;
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Loads current membership before allowing removal from a captured, owned multireddit. */
public final class RemoveFromMultiredditDialog {

	private RemoveFromMultiredditDialog() { }

	interface Operations {
		default void refresh(final RedditAccount account) { }

		MultiredditDialogs.Pending members(
				RedditAccount account,
				MultiredditTarget target,
				MultiredditApi.MembersListener listener);

		MultiredditDialogs.Pending remove(
				RedditAccount account,
				MultiredditTarget target,
				SubredditCanonicalId subreddit,
				MultiredditApi.ActionListener listener);
	}

	@Nullable
	public static AlertDialog show(
			final AppCompatActivity activity,
			final RedditAccount account,
			final MultiredditTarget target,
			@Nullable final Runnable onRemoved) {
		return show(activity, account, target, onRemoved, new Operations() {
			@Override
			public void refresh(final RedditAccount user) {
				RedditMultiredditSubscriptionManager.getSingleton(activity, user)
						.triggerUpdate(null, TimestampBound.NONE);
			}

			@Override
			public MultiredditDialogs.Pending members(
					final RedditAccount user,
					final MultiredditTarget multi,
					final MultiredditApi.MembersListener listener) {
				final CacheRequest request = MultiredditApi.newMembersRequest(
						activity.getApplicationContext(), user, multi, listener);
				CacheManager.getInstance(activity).makeRequest(request);
				return request::cancel;
			}

			@Override
			public MultiredditDialogs.Pending remove(
					final RedditAccount user,
					final MultiredditTarget multi,
					final SubredditCanonicalId subreddit,
					final MultiredditApi.ActionListener listener) {
				final CacheRequest request = MultiredditApi.newRemoveSubredditRequest(
						activity.getApplicationContext(), user, multi, subreddit, listener);
				CacheManager.getInstance(activity).makeRequest(request);
				return request::cancel;
			}
		});
	}

	@Nullable
	static AlertDialog show(
			final AppCompatActivity activity,
			final RedditAccount account,
			final MultiredditTarget target,
			@Nullable final Runnable onRemoved,
			final Operations operations) {
		if(!target.isOwnedBy(account) || !currentAccount(activity, account)
				|| activity.isFinishing() || activity.isDestroyed()
				|| !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
			return null;
		}
		final LinearLayout contents = new LinearLayout(activity);
		contents.setOrientation(LinearLayout.VERTICAL);
		final int padding = General.dpToPixels(activity, 24);
		contents.setPadding(padding, 0, padding, padding);
		final TextView description = new TextView(activity);
		description.setText(target.getDisplayPath());
		contents.addView(description);
		final TextView status = new TextView(activity);
		status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		contents.addView(status);
		final AutoCompleteTextView input = new AutoCompleteTextView(activity);
		input.setHint(R.string.multireddit_choose_member);
		input.setSingleLine(true);
		input.setThreshold(0);
		input.setImeOptions(EditorInfo.IME_ACTION_GO);
		contents.addView(input);
		final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.remove_subreddit_from_multireddit)
				.setView(contents)
				.setPositiveButton(R.string.multireddit_remove_button, null)
				.setNeutralButton(R.string.multireddit_members_retry, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		final Controller controller = new Controller(
				activity, account, target, onRemoved, operations, dialog, input, status);
		dialog.setOnDismissListener(ignored -> controller.close());
		dialog.setOnShowListener(ignored -> {
			activity.getLifecycle().addObserver(controller);
			dialog.getButton(AlertDialog.BUTTON_POSITIVE)
					.setOnClickListener(v -> controller.submit());
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> controller.load());
			controller.load();
		});
		input.setOnClickListener(v -> {
			if(input.isEnabled()) {
				input.showDropDown();
			}
		});
		input.setOnEditorActionListener((view, action, event) ->
				MultiredditDialogs.handleEditor(action, event, controller::submit));
		dialog.show();
		return dialog;
	}

	private static boolean currentAccount(
			final AppCompatActivity activity,
			final RedditAccount account) {
		return !account.isAnonymous() && account.equals(
				RedditAccountManager.getInstance(activity).getAccount(account.username));
	}

	private static final class Controller implements DefaultLifecycleObserver {
		private final AppCompatActivity mActivity;
		private final RedditAccount mAccount;
		private final MultiredditTarget mTarget;
		@Nullable private final Runnable mOnRemoved;
		private final Operations mOperations;
		private final AlertDialog mDialog;
		private final AutoCompleteTextView mInput;
		private final TextView mStatus;
		private final List<String> mMembers = new ArrayList<>();
		@Nullable private MultiredditDialogs.Pending mPending;
		private boolean mClosed;
		private boolean mWorking;
		private boolean mReady;
		private int mGeneration;

		private Controller(
				final AppCompatActivity activity,
				final RedditAccount account,
				final MultiredditTarget target,
				@Nullable final Runnable onRemoved,
				final Operations operations,
				final AlertDialog dialog,
				final AutoCompleteTextView input,
				final TextView status) {
			mActivity = activity;
			mAccount = account;
			mTarget = target;
			mOnRemoved = onRemoved;
			mOperations = operations;
			mDialog = dialog;
			mInput = input;
			mStatus = status;
		}

		private boolean active() {
			return !mClosed && mDialog.isShowing()
					&& !mActivity.isFinishing() && !mActivity.isDestroyed();
		}

		private boolean canStart() {
			if(!active() || mWorking) {
				return false;
			}
			if(!currentAccount(mActivity, mAccount)) {
				mStatus.setText(R.string.multireddit_sign_in_required);
				mReady = false;
				controls();
				return false;
			}
			return true;
		}

		private boolean accepts(final int generation) {
			if(!active() || !mWorking || generation != mGeneration) {
				return false;
			}
			if(!currentAccount(mActivity, mAccount)) {
				mDialog.dismiss();
				return false;
			}
			return true;
		}

		private void pending(final int generation, final MultiredditDialogs.Pending request) {
			if(accepts(generation)) {
				mPending = request;
			} else {
				request.cancel();
			}
		}

		private void load() {
			if(!canStart()) {
				return;
			}
			mWorking = true;
			mReady = false;
			mMembers.clear();
			final int generation = ++mGeneration;
			mStatus.setText(R.string.multireddit_loading_members);
			controls();
			pending(generation, mOperations.members(mAccount, mTarget,
					new MultiredditApi.MembersListener() {
						@Override
						public void onSuccess(@NonNull final List<String> names) {
							if(!accepts(generation)) {
								return;
							}
							final LinkedHashSet<String> valid = new LinkedHashSet<>();
							try {
								for(final String name : names) {
									valid.add(MultiredditApi.subredditName(
											new SubredditCanonicalId(name)));
								}
							} catch(final InvalidSubredditNameException
									| IllegalArgumentException error) {
								failed(generation, null);
								return;
							}
							mPending = null;
							mWorking = false;
							mReady = true;
							mMembers.addAll(valid);
							mInput.setAdapter(new ArrayAdapter<>(mActivity,
									android.R.layout.simple_dropdown_item_1line, mMembers));
							mStatus.setText(mMembers.isEmpty() ? R.string.multireddit_no_members
									: R.string.multireddit_choose_member);
							controls();
						}

						@Override
						public void onFailure(@NonNull final RRError error) {
							failed(generation, error);
						}
					}));
		}

		private void submit() {
			if(!canStart() || !mReady || mMembers.isEmpty()) {
				return;
			}
			final SubredditCanonicalId subreddit;
			try {
				subreddit = new SubredditCanonicalId(mInput.getText().toString());
				if(!mMembers.contains(MultiredditApi.subredditName(subreddit))) {
					throw new IllegalArgumentException("Not a current member");
				}
			} catch(final InvalidSubredditNameException | IllegalArgumentException error) {
				mInput.setError(mActivity.getString(R.string.multireddit_select_member));
				return;
			}
			mWorking = true;
			mInput.setError(null);
			final int generation = ++mGeneration;
			mStatus.setText(R.string.multireddit_removing);
			controls();
			pending(generation, mOperations.remove(mAccount, mTarget, subreddit,
					new MultiredditApi.ActionListener() {
						@Override
						public void onSuccess() {
							if(!accepts(generation)) {
								return;
							}
							if(currentAccount(mActivity, mAccount)) {
								mOperations.refresh(mAccount);
							}
							General.quickToast(mActivity, mActivity.getString(
									R.string.multireddit_removed,
									subreddit.getDisplayNameLowercase(), mTarget.getDisplayPath()));
							mDialog.dismiss();
							if(mOnRemoved != null) {
								mOnRemoved.run();
							}
						}

						@Override
						public void onFailure(@NonNull final RRError error) {
							failed(generation, error);
						}
					}));
		}

		private void failed(final int generation, @Nullable final RRError error) {
			if(!accepts(generation)) {
				return;
			}
			mPending = null;
			mWorking = false;
			mStatus.setText(error != null && error.message != null
					? error.message : mActivity.getString(R.string.multireddit_members_failed));
			controls();
		}

		private void controls() {
			mInput.setEnabled(!mWorking && mReady && !mMembers.isEmpty());
			mDialog.getButton(AlertDialog.BUTTON_POSITIVE)
					.setEnabled(!mWorking && mReady && !mMembers.isEmpty());
			mDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!mWorking);
		}

		@Override
		public void onStop(@NonNull final LifecycleOwner owner) {
			mDialog.dismiss();
		}

		@Override
		public void onDestroy(@NonNull final LifecycleOwner owner) {
			mDialog.dismiss();
			close();
		}

		private void close() {
			mClosed = true;
			mGeneration++;
			if(mPending != null) {
				mPending.cancel();
				mPending = null;
			}
			mActivity.getLifecycle().removeObserver(this);
		}
	}
}
