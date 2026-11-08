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

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Dialogs retain the account and full target that the user was shown. */
public final class MultiredditDialogs {

	private MultiredditDialogs() { }

	interface Pending {
		void cancel();
	}

	interface Backend {
		default Pending create(
				RedditAccount account,
				MultiredditTarget target,
				Collection<SubredditCanonicalId> subreddits,
				MultiredditApi.ActionListener listener) {
			throw new UnsupportedOperationException();
		}

		default Pending delete(
				RedditAccount account,
				MultiredditTarget target,
				MultiredditApi.ActionListener listener) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class ApiBackend implements Backend {
		private final Context mContext;

		private ApiBackend(final Context context) {
			mContext = context.getApplicationContext();
		}

		@Override
		public Pending delete(
				final RedditAccount account,
				final MultiredditTarget target,
				final MultiredditApi.ActionListener listener) {
			final CacheRequest request = MultiredditApi.newDeleteRequest(
					mContext, account, target, new MultiredditApi.ActionListener() {
						@Override
						public void onSuccess() {
							RedditMultiredditSubscriptionManager.getSingleton(mContext, account)
									.triggerUpdate(null, TimestampBound.NONE);
							listener.onSuccess();
						}

						@Override
						public void onFailure(@NonNull final RRError error) {
							listener.onFailure(error);
						}
					});
			CacheManager.getInstance(mContext).makeRequest(request);
			return request::cancel;
		}

		@Override
		public Pending create(
				final RedditAccount account,
				final MultiredditTarget target,
				final Collection<SubredditCanonicalId> subreddits,
				final MultiredditApi.ActionListener listener) {
			final CacheRequest request = MultiredditApi.newCreateRequest(
					mContext, account, target, subreddits, new MultiredditApi.ActionListener() {
						@Override
						public void onSuccess() {
							RedditMultiredditSubscriptionManager.getSingleton(mContext, account)
									.triggerUpdate(null, TimestampBound.NONE);
							listener.onSuccess();
						}

						@Override
						public void onFailure(@NonNull final RRError error) {
							listener.onFailure(error);
						}
					});
			CacheManager.getInstance(mContext).makeRequest(request);
			return request::cancel;
		}
	}

	@Nullable
	public static AlertDialog showCreate(
			final AppCompatActivity activity,
			final RedditAccount account,
			final Collection<SubredditCanonicalId> subreddits) {
		return showCreate(activity, account, subreddits, new ApiBackend(activity));
	}

	@Nullable
	static AlertDialog showCreate(
			final AppCompatActivity activity,
			final RedditAccount account,
			final Collection<SubredditCanonicalId> subreddits,
			final Backend backend) {

		if(!liveHost(activity)) {
			return null;
		}
		// The listing or default account can change while the input dialog is open.
		final List<SubredditCanonicalId> members = Collections.unmodifiableList(
				new ArrayList<>(subreddits));
		final EditText input = new EditText(activity);
		input.setSingleLine(true);
		input.setHint(R.string.multireddit_name_hint);
		input.setImeOptions(EditorInfo.IME_ACTION_GO);
		final TextView error = new TextView(activity);
		error.setVisibility(View.GONE);
		error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

		final LinearLayout contents = contents(activity);
		final TextView description = new TextView(activity);
		description.setText(activity.getString(
				R.string.multireddit_create_description, account.username, members.size()));
		contents.addView(description);
		contents.addView(input);
		contents.addView(error);

		final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.create_multireddit)
				.setView(contents)
				.setPositiveButton(R.string.create_multireddit, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		final Session session = new Session(activity, dialog, input, error);
		final Runnable submit = () -> {
			if(session.isWorking() || !session.isUsable()) {
				return;
			}
			final String name = input.getText().toString().trim();
			if(!currentAccount(activity, account)) {
				input.setError(activity.getString(R.string.multireddit_sign_in_required));
				return;
			}
			if(!MultiredditTarget.isValidName(name)) {
				input.setError(activity.getString(R.string.multireddit_invalid_name));
				return;
			}
			if(members.isEmpty() || members.size() > 100) {
				input.setError(activity.getString(R.string.multireddit_invalid_members));
				return;
			}
			final MultiredditTarget target = MultiredditTarget.forName(account, name);
			final int generation = session.begin();
			try {
				session.setPending(generation, backend.create(account, target, members,
						new MultiredditApi.ActionListener() {
							@Override
							public void onSuccess() {
								if(session.accepts(generation)) {
									General.quickToast(activity, activity.getString(
											R.string.multireddit_created, target.getDisplayPath()));
									dialog.dismiss();
								}
							}

							@Override
							public void onFailure(@NonNull final RRError failure) {
								session.fail(generation, failure);
							}
						}));
			} catch(final IllegalArgumentException invalid) {
				session.fail(generation, new RRError(null,
						activity.getString(R.string.multireddit_invalid_members), false));
			}
		};

		dialog.setOnShowListener(shown -> {
			session.attach();
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
		});
		input.setOnEditorActionListener(
				(view, action, event) -> handleEditor(action, event, submit));
		dialog.show();
		return dialog;
	}

	@Nullable
	public static AlertDialog showDelete(
			final AppCompatActivity activity,
			final RedditAccount account,
			final MultiredditTarget target,
			@Nullable final Runnable onDeleted) {
		return showDelete(activity, account, target, onDeleted, new ApiBackend(activity));
	}

	@Nullable
	static AlertDialog showDelete(
			final AppCompatActivity activity,
			final RedditAccount account,
			final MultiredditTarget target,
			@Nullable final Runnable onDeleted,
			final Backend backend) {
		if(!liveHost(activity)) {
			return null;
		}
		if(!target.isOwnedBy(account)) {
			return notOwned(activity, target);
		}
		final TextView error = new TextView(activity);
		error.setVisibility(View.GONE);
		error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		final LinearLayout contents = contents(activity);
		final TextView description = new TextView(activity);
		description.setText(activity.getString(
				R.string.multireddit_delete_confirmation, target.getDisplayPath()));
		contents.addView(description);
		contents.addView(error);
		final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.delete_multireddit)
				.setView(contents)
				.setPositiveButton(R.string.delete_multireddit, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		final Session session = new Session(activity, dialog, null, error);
		dialog.setOnShowListener(shown -> {
			session.attach();
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
				if(session.isWorking() || !session.isUsable()) {
					return;
				}
				if(!currentAccount(activity, account)) {
					error.setText(R.string.multireddit_sign_in_required);
					error.setVisibility(View.VISIBLE);
					return;
				}
				final int generation = session.begin();
				session.setPending(generation, backend.delete(account, target,
						new MultiredditApi.ActionListener() {
							@Override
							public void onSuccess() {
								if(session.accepts(generation)) {
									General.quickToast(activity, activity.getString(
											R.string.multireddit_deleted, target.getDisplayPath()));
									dialog.dismiss();
									if(onDeleted != null) {
										onDeleted.run();
									}
								}
							}

							@Override
							public void onFailure(@NonNull final RRError failure) {
								session.fail(generation, failure);
							}
						}));
			});
		});
		dialog.show();
		return dialog;
	}

	private static boolean liveHost(final AppCompatActivity activity) {
		return !activity.isFinishing() && !activity.isDestroyed()
				&& activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
	}

	private static boolean currentAccount(
			final AppCompatActivity activity,
			final RedditAccount account) {
		return !account.isAnonymous() && account.equals(
				RedditAccountManager.getInstance(activity).getAccount(account.username));
	}

	private static AlertDialog notOwned(
			final AppCompatActivity activity,
			final MultiredditTarget target) {
		return new MaterialAlertDialogBuilder(activity)
				.setTitle(target.getDisplayPath())
				.setMessage(R.string.multireddit_not_owned)
				.setNegativeButton(android.R.string.ok, null)
				.show();
	}

	static boolean handleEditor(
			final int action,
			@Nullable final KeyEvent event,
			final Runnable submit) {
		if(event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
			if(event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
				submit.run();
			}
			return true;
		}
		if(action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
			submit.run();
			return true;
		}
		return false;
	}

	private static LinearLayout contents(final Context context) {
		final LinearLayout view = new LinearLayout(context);
		view.setOrientation(LinearLayout.VERTICAL);
		final int padding = General.dpToPixels(context, 24);
		view.setPadding(padding, 0, padding, padding);
		return view;
	}

	private static final class Session implements DefaultLifecycleObserver {
		private final AppCompatActivity mActivity;
		private final AlertDialog mDialog;
		@Nullable private final EditText mInput;
		private final TextView mError;
		private boolean mAttached;
		private boolean mWorking;
		private int mGeneration;
		@Nullable private Pending mPending;

		private Session(
				final AppCompatActivity activity,
				final AlertDialog dialog,
				@Nullable final EditText input,
				final TextView error) {
			mActivity = activity;
			mDialog = dialog;
			mInput = input;
			mError = error;
			dialog.setOnDismissListener(ignored -> detach());
		}

		private void attach() {
			mAttached = true;
			mActivity.getLifecycle().addObserver(this);
		}

		@Override
		public void onStop(@NonNull final LifecycleOwner owner) {
			mDialog.dismiss();
		}

		@Override
		public void onDestroy(@NonNull final LifecycleOwner owner) {
			mDialog.dismiss();
			detach();
		}

		private void detach() {
			mAttached = false;
			mGeneration++;
			mWorking = false;
			if(mPending != null) {
				mPending.cancel();
				mPending = null;
			}
			mActivity.getLifecycle().removeObserver(this);
		}

		private boolean isWorking() {
			return mWorking;
		}

		private int begin() {
			mWorking = true;
			if(mInput != null) {
				mInput.setError(null);
				mInput.setEnabled(false);
			}
			mError.setVisibility(View.GONE);
			mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
			return ++mGeneration;
		}

		private boolean isUsable() {
			return mAttached && mDialog.isShowing() && liveHost(mActivity);
		}

		private boolean accepts(final int generation) {
			return isUsable() && mWorking && generation == mGeneration;
		}

		private void setPending(final int generation, final Pending pending) {
			if(accepts(generation) && mWorking) {
				mPending = pending;
			} else {
				pending.cancel();
			}
		}

		private void fail(final int generation, final RRError error) {
			if(!accepts(generation)) {
				return;
			}
			mPending = null;
			mWorking = false;
			if(mInput != null) {
				mInput.setEnabled(true);
			}
			mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
			mError.setText(error.message != null
					? error.message : mActivity.getString(R.string.multireddit_action_failed));
			mError.setVisibility(View.VISIBLE);
		}
	}
}
