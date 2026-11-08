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

import android.view.KeyEvent;
import android.view.WindowManager;
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
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Lifecycle;

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
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId;

import java.util.ArrayList;
import java.util.List;

/** Adds a subreddit only to a verified owned multi for the account captured on opening. */
public final class AddToMultiredditDialog {

	public interface Operations {
		CacheRequest loadNames(RedditAccount account, MultiredditApi.MembersListener listener);

		CacheRequest add(
				RedditAccount account,
				MultiredditTarget target,
				SubredditCanonicalId subreddit,
				MultiredditApi.ActionListener listener);

		void refresh(RedditAccount account);
	}

	private AddToMultiredditDialog() {
	}

	@Nullable
	public static AlertDialog show(
			@NonNull final AppCompatActivity activity,
			@NonNull final RedditAccount account,
			@NonNull final SubredditCanonicalId subreddit) {

		return show(activity, account, subreddit, new Operations() {
			@Override
			public CacheRequest loadNames(
					final RedditAccount user,
					final MultiredditApi.MembersListener listener) {
				final CacheRequest request = MultiredditApi.newOwnedNamesRequest(
						activity.getApplicationContext(), user, listener);
				CacheManager.getInstance(activity).makeRequest(request);
				return request;
			}

			@Override
			public CacheRequest add(
					final RedditAccount user,
					final MultiredditTarget target,
					final SubredditCanonicalId source,
					final MultiredditApi.ActionListener listener) {
				final CacheRequest request = MultiredditApi.newAddSubredditRequest(
						activity.getApplicationContext(), user, target, source, listener);
				CacheManager.getInstance(activity).makeRequest(request);
				return request;
			}

			@Override
			public void refresh(final RedditAccount user) {
				RedditMultiredditSubscriptionManager.getSingleton(activity, user)
						.triggerUpdate(null, TimestampBound.NONE);
			}
		});
	}

	@Nullable
	public static AlertDialog show(
			@NonNull final AppCompatActivity activity,
			@NonNull final RedditAccount account,
			@NonNull final SubredditCanonicalId subreddit,
			@NonNull final Operations operations) {

		if(activity.isFinishing() || activity.isDestroyed()
				|| !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
			return null;
		}
		if(!availableAccount(activity, account)) {
			General.quickToast(activity, R.string.multireddit_sign_in_required);
			return null;
		}
		try {
			MultiredditApi.subredditName(subreddit);
		} catch(final IllegalArgumentException error) {
			General.quickToast(activity, R.string.multireddit_single_subreddit_required);
			return null;
		}

		final LinearLayout contents = new LinearLayout(activity);
		contents.setOrientation(LinearLayout.VERTICAL);
		final int padding = General.dpToPixels(activity, 24);
		contents.setPadding(padding, 0, padding, 0);

		final TextView status = new TextView(activity);
		contents.addView(status);
		final AutoCompleteTextView input = new AutoCompleteTextView(activity);
		input.setHint(R.string.multireddit_choose_name);
		input.setSingleLine(true);
		input.setImeOptions(EditorInfo.IME_ACTION_GO);
		contents.addView(input);

		final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(activity.getString(R.string.multireddit_add_title,
						subreddit.getDisplayNameLowercase(), account.username))
				.setView(contents)
				.setPositiveButton(R.string.multireddit_add_button, null)
				.setNeutralButton(R.string.multireddit_retry, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();

		final Controller controller = new Controller(
				activity, account, subreddit, operations, dialog, input, status);
		dialog.setOnDismissListener(ignored -> controller.close());
		dialog.setOnShowListener(ignored -> {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE)
					.setOnClickListener(v -> controller.submit());
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> controller.load());
			controller.load();
		});
		input.setOnEditorActionListener((view, actionId, event) -> {
			if(event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
				if(event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
					controller.submit();
				}
				return true;
			}
			if(actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
				controller.submit();
				return true;
			}
			return false;
		});
		activity.getLifecycle().addObserver(controller);
		dialog.show();
		if(dialog.getWindow() != null) {
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
					| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		}
		return dialog;
	}

	private static boolean availableAccount(
			final AppCompatActivity activity,
			final RedditAccount account) {
		return !account.isAnonymous() && account.equals(
				RedditAccountManager.getInstance(activity).getAccount(account.username));
	}

	private static final class Controller implements DefaultLifecycleObserver {
		private final AppCompatActivity mActivity;
		private final RedditAccount mAccount;
		private final SubredditCanonicalId mSubreddit;
		private final Operations mOperations;
		private final AlertDialog mDialog;
		private final AutoCompleteTextView mInput;
		private final TextView mStatus;
		private final List<String> mNames = new ArrayList<>();
		private CacheRequest mNamesRequest;
		private CacheRequest mMutationRequest;
		private boolean mClosed;
		private boolean mLoading;
		private boolean mBusy;
		private boolean mNamesReady;
		private int mGeneration;

		private Controller(
				final AppCompatActivity activity,
				final RedditAccount account,
				final SubredditCanonicalId subreddit,
				final Operations operations,
				final AlertDialog dialog,
				final AutoCompleteTextView input,
				final TextView status) {
			mActivity = activity;
			mAccount = account;
			mSubreddit = subreddit;
			mOperations = operations;
			mDialog = dialog;
			mInput = input;
			mStatus = status;
		}

		private boolean active() {
			if(mClosed || !mDialog.isShowing()
					|| mActivity.isFinishing() || mActivity.isDestroyed()) {
				return false;
			}
			if(!availableAccount(mActivity, mAccount)) {
				close();
				mDialog.dismiss();
				return false;
			}
			return true;
		}

		private boolean accepts(final int generation, final boolean pending) {
			return active() && pending && generation == mGeneration;
		}

		private void load() {
			if(!active() || mLoading || mBusy) {
				return;
			}
			mLoading = true;
			mNamesReady = false;
			final int generation = ++mGeneration;
			if(mNamesRequest != null) {
				mNamesRequest.cancel();
				mNamesRequest = null;
			}
			mStatus.setText(R.string.multireddit_loading_names);
			updateControls();
			final CacheRequest request = mOperations.loadNames(mAccount,
					new MultiredditApi.MembersListener() {
						@Override
						public void onSuccess(@NonNull final List<String> names) {
							if(!accepts(generation, mLoading)) {
								return;
							}
							mLoading = false;
							mNamesReady = true;
							mNames.clear();
							mNames.addAll(names);
							mInput.setAdapter(new ArrayAdapter<>(mActivity,
									android.R.layout.simple_dropdown_item_1line, mNames));
							mStatus.setText(names.isEmpty()
									? R.string.multireddit_no_owned_names
									: R.string.multireddit_choose_name);
							updateControls();
							mInput.requestFocus();
						}

						@Override
						public void onFailure(@NonNull final RRError error) {
							if(!accepts(generation, mLoading)) {
								return;
							}
							mLoading = false;
							mStatus.setText(R.string.multireddit_names_failed);
							updateControls();
						}
					});
			if(active() && generation == mGeneration) {
				mNamesRequest = request;
			} else {
				request.cancel();
			}
		}

		private void submit() {
			if(!active() || mBusy || !mNamesReady || mLoading) {
				return;
			}
			final String name = mInput.getText().toString().trim();
			String matching = null;
			for(final String owned : mNames) {
				if(owned.equalsIgnoreCase(name)) {
					matching = owned;
					break;
				}
			}
			if(matching == null) {
				mInput.setError(mActivity.getString(R.string.multireddit_select_owned_name));
				return;
			}
			final MultiredditTarget target = MultiredditTarget.forName(mAccount, matching);
			mBusy = true;
			final int generation = ++mGeneration;
			if(mMutationRequest != null) {
				mMutationRequest.cancel();
				mMutationRequest = null;
			}
			mInput.setError(null);
			mStatus.setText(R.string.multireddit_adding);
			updateControls();
			final CacheRequest request = mOperations.add(mAccount, target, mSubreddit,
					new MultiredditApi.ActionListener() {
						@Override
						public void onSuccess() {
							if(!accepts(generation, mBusy)) {
								return;
							}
							mOperations.refresh(mAccount);
							General.quickToast(mActivity, mActivity.getString(
									R.string.multireddit_added,
									mSubreddit.getDisplayNameLowercase(),
									target.getDisplayPath()));
							mDialog.dismiss();
						}

						@Override
						public void onFailure(@NonNull final RRError error) {
							if(!accepts(generation, mBusy)) {
								return;
							}
							mBusy = false;
							mStatus.setText(R.string.multireddit_add_failed);
							mInput.setError(error.message != null ? error.message : error.title);
							updateControls();
						}
					});
			if(active() && generation == mGeneration) {
				mMutationRequest = request;
			} else {
				request.cancel();
			}
		}

		private void updateControls() {
			mInput.setEnabled(!mBusy && !mLoading);
			mDialog.getButton(AlertDialog.BUTTON_POSITIVE)
					.setEnabled(!mBusy && !mLoading && mNamesReady && !mNames.isEmpty());
			mDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!mBusy && !mLoading);
		}

		@Override
		public void onStop(@NonNull final LifecycleOwner owner) {
			mDialog.dismiss();
		}

		private void close() {
			if(mClosed) {
				return;
			}
			mClosed = true;
			mGeneration++;
			mActivity.getLifecycle().removeObserver(this);
			if(mNamesRequest != null) {
				mNamesRequest.cancel();
			}
			if(mMutationRequest != null) {
				mMutationRequest.cancel();
			}
		}
	}
}
