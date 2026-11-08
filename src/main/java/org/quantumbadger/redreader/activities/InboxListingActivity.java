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

package org.quantumbadger.redreader.activities;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.account.RedditAccount;
import org.quantumbadger.redreader.account.RedditAccountManager;
import org.quantumbadger.redreader.adapters.GroupedRecyclerViewAdapter;
import org.quantumbadger.redreader.cache.CacheManager;
import org.quantumbadger.redreader.cache.CacheRequest;
import org.quantumbadger.redreader.cache.CacheRequestCallbacks;
import org.quantumbadger.redreader.cache.downloadstrategy.DownloadStrategyAlways;
import org.quantumbadger.redreader.common.AndroidCommon;
import org.quantumbadger.redreader.common.Constants;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.common.GenericFactory;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.common.Priority;
import org.quantumbadger.redreader.common.RRError;
import org.quantumbadger.redreader.common.RRThemeAttributes;
import org.quantumbadger.redreader.common.SharedPrefsWrapper;
import org.quantumbadger.redreader.common.StringUtils;
import org.quantumbadger.redreader.common.UriString;
import org.quantumbadger.redreader.common.datastream.SeekableInputStream;
import org.quantumbadger.redreader.common.time.TimeDuration;
import org.quantumbadger.redreader.common.time.TimestampUTC;
import org.quantumbadger.redreader.http.FailedRequestBody;
import org.quantumbadger.redreader.reddit.APIResponseHandler;
import org.quantumbadger.redreader.reddit.InboxReadOnExit;
import org.quantumbadger.redreader.reddit.RedditAPI;
import org.quantumbadger.redreader.reddit.kthings.JsonUtils;
import org.quantumbadger.redreader.reddit.kthings.MaybeParseError;
import org.quantumbadger.redreader.reddit.kthings.RedditComment;
import org.quantumbadger.redreader.reddit.kthings.RedditFieldReplies;
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType;
import org.quantumbadger.redreader.reddit.kthings.RedditListing;
import org.quantumbadger.redreader.reddit.kthings.RedditMessage;
import org.quantumbadger.redreader.reddit.kthings.RedditThing;
import org.quantumbadger.redreader.reddit.prepared.RedditChangeDataManager;
import org.quantumbadger.redreader.reddit.prepared.RedditParsedComment;
import org.quantumbadger.redreader.reddit.prepared.RedditPreparedMessage;
import org.quantumbadger.redreader.reddit.prepared.RedditRenderableComment;
import org.quantumbadger.redreader.reddit.prepared.RedditRenderableInboxItem;
import org.quantumbadger.redreader.views.RedditInboxItemView;
import org.quantumbadger.redreader.views.ScrollbarRecyclerViewManager;
import org.quantumbadger.redreader.views.liststatus.ErrorView;
import org.quantumbadger.redreader.views.liststatus.LoadingView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InboxListingActivity extends ViewsBaseActivity {

	private static final String TAG = "InboxListingActivity";

	private static final int OPTIONS_MENU_MARK_ALL_AS_READ = 0;
	private static final int OPTIONS_MENU_SHOW_UNREAD_ONLY = 1;
	private static final int OPTIONS_MENU_REFRESH = 2;
	private static final int OPTIONS_MENU_MARK_VIEWED_ON_EXIT = 3;

	public enum InboxType {
		INBOX, SENT, MODMAIL
	}

	private static final String PREF_ONLY_UNREAD = "inbox_only_show_unread";
	private static final String PREF_MARK_VIEWED_ON_EXIT = "inbox_mark_as_read_when_back";
	private static final String STATE_ACCOUNT = "inbox_account";
	private static final String STATE_VIEWED_IDS = "inbox_viewed_unread_ids";

	private GroupedRecyclerViewAdapter adapter;

	private LoadingView loadingView;
	private LinearLayout notifications;

	private CacheRequest request;

	private InboxType inboxType;
	private boolean mOnlyShowUnread;
	private boolean mMarkViewedReadOnExit;
	private RedditAccount mUser;
	private InboxReadOnExit mReadOnExit;
	private RecyclerView mRecyclerView;
	private boolean mResumed;
	private volatile boolean mDestroyed;
	private final Rect mVisibleBounds = new Rect();
	private final Rect mRowBounds = new Rect();
	private final Rect mContentBounds = new Rect();
	private final int[] mLocationOnScreen = new int[2];
	private final ViewTreeObserver.OnPreDrawListener mVisibleItemsListener = () -> {
		recordVisibleUnreadItems();
		return true;
	};

	private RRThemeAttributes mTheme;
	private RedditChangeDataManager mChangeDataManager;

	private final ArrayList<InboxItem> mItems = new ArrayList<>();

	// Items which the user has expanded after they were collapsed for length
	private final HashSet<RedditIdAndType> mExpandedItems = new HashSet<>();

	private final Handler itemHandler = new Handler(Looper.getMainLooper()) {
		@Override
		public void handleMessage(final Message msg) {
			if(mDestroyed || isFinishing()) {
				return;
			}
			final InboxItem item = (InboxItem)msg.obj;
			mItems.add(item);
			adapter.appendToGroup(0, item);
		}
	};

	private final class InboxItem extends GroupedRecyclerViewAdapter.Item {

		private final RedditRenderableInboxItem mItem;
		private final boolean mUnreadOnLoad;

		private InboxItem(final RedditRenderableInboxItem item, final boolean unreadOnLoad) {
			this.mItem = item;
			mUnreadOnLoad = unreadOnLoad;
		}

		@Override
		public Class getViewType() {
			return RedditInboxItemView.class;
		}

		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(final ViewGroup viewGroup) {

			final RedditInboxItemView view = new RedditInboxItemView(
					InboxListingActivity.this,
					mUser,
					mTheme,
					inboxType != InboxType.SENT,
					mExpandedItems);

			final RecyclerView.LayoutParams layoutParams
					= new RecyclerView.LayoutParams(
							ViewGroup.LayoutParams.MATCH_PARENT,
							ViewGroup.LayoutParams.WRAP_CONTENT);
			view.setLayoutParams(layoutParams);

			return new RecyclerView.ViewHolder(view) {
			};
		}

		@Override
		public void onBindViewHolder(final RecyclerView.ViewHolder viewHolder) {
			((RedditInboxItemView)viewHolder.itemView).reset(
					InboxListingActivity.this,
					mChangeDataManager,
					mTheme,
					mItem);
		}

		@Override
		public boolean isHidden() {
			return false;
		}
	}

	// TODO load more on scroll to bottom?

	@Override
	protected boolean baseActivityContentExtendsBehindNavigationBar() {
		return true;
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {

		PrefsUtility.applyTheme(this);
		super.onCreate(savedInstanceState);

		final RedditAccountManager accounts = RedditAccountManager.getInstance(this);
		final String restoredAccount = savedInstanceState == null
				? null : savedInstanceState.getString(STATE_ACCOUNT);
		mUser = restoredAccount == null
				? accounts.getDefaultAccount() : accounts.getAccount(restoredAccount);
		if(mUser == null) {
			// Never restore another account's viewed IDs into the new default account.
			finish();
			return;
		}
		mReadOnExit = new InboxReadOnExit(mUser, savedInstanceState == null
				? null : savedInstanceState.getStringArrayList(STATE_VIEWED_IDS));
		mTheme = new RRThemeAttributes(this);
		mChangeDataManager = RedditChangeDataManager.getInstance(mUser);

		final SharedPrefsWrapper sharedPreferences
				= General.getSharedPrefs(this);
		final String title;

		if(getIntent() != null) {
			final String inboxTypeString = getIntent().getStringExtra("inboxType");

			if(inboxTypeString != null) {
				inboxType = InboxType.valueOf(StringUtils.asciiUppercase(inboxTypeString));
			} else {
				inboxType = InboxType.INBOX;
			}
		} else {
			inboxType = InboxType.INBOX;
		}

		mOnlyShowUnread = sharedPreferences.getBoolean(PREF_ONLY_UNREAD, false);
		mMarkViewedReadOnExit = sharedPreferences.getBoolean(PREF_MARK_VIEWED_ON_EXIT, false);

		switch(inboxType) {
			case SENT:
				title = getString(R.string.mainmenu_sent_messages);
				break;
			case MODMAIL:
				title = getString(R.string.mainmenu_modmail);
				break;
			default:
				title = getString(R.string.mainmenu_inbox);
				break;
		}

		setTitle(title);

		final LinearLayout outer = new LinearLayout(this);
		outer.setOrientation(LinearLayout.VERTICAL);

		loadingView = new LoadingView(
				this,
				getString(R.string.download_waiting),
				true,
				true);

		notifications = new LinearLayout(this);
		notifications.setOrientation(LinearLayout.VERTICAL);
		notifications.addView(loadingView);

		final ScrollbarRecyclerViewManager recyclerViewManager
				= new ScrollbarRecyclerViewManager(this, null, false);

		adapter = new GroupedRecyclerViewAdapter(1);
		mRecyclerView = recyclerViewManager.getRecyclerView();
		mRecyclerView.setAdapter(adapter);
		mRecyclerView.getViewTreeObserver().addOnPreDrawListener(mVisibleItemsListener);

		outer.addView(notifications);
		outer.addView(recyclerViewManager.getOuterView());

		makeFirstRequest(this);

		setBaseActivityListing(outer);
	}

	public void cancel() {
		if(request != null) {
			request.cancel();
		}
	}

	private void makeFirstRequest(final Context context) {

		final RedditAccount user = mUser;
		final CacheManager cm = CacheManager.getInstance(context);

		final UriString url;

		if(inboxType == InboxType.SENT) {
			url = Constants.Reddit.getUri("/message/sent.json?limit=100");
		} else if(inboxType == InboxType.MODMAIL) {
			url = Constants.Reddit.getUri("/message/moderator.json?limit=100");
		} else {
			if(mOnlyShowUnread) {
				url = Constants.Reddit.getUri("/message/unread.json?mark=false&limit=100");
			} else {
				url = Constants.Reddit.getUri("/message/inbox.json?mark=false&limit=100");
			}
		}

		// TODO parameterise limit
		request = new CacheRequest(
				url,
				user,
				null,
				new Priority(Constants.Priority.API_INBOX_LIST),
				DownloadStrategyAlways.INSTANCE,
				Constants.FileType.INBOX_LIST,
				CacheRequest.DownloadQueueType.REDDIT_API,
				false,
				context,
				new CacheRequestCallbacks() {

					@Override
					public void onDataStreamComplete(
							@NonNull final GenericFactory<SeekableInputStream, IOException>
									streamFactory,
							final TimestampUTC timestamp,
							@NonNull final UUID session,
							final boolean fromCache,
							@Nullable final String mimetype) {

						if(mDestroyed || isFinishing()) {
							return;
						}
						try {
							final RedditThing rootThing = JsonUtils.INSTANCE
									.decodeRedditThingFromStream(streamFactory.create());

							final RedditListing listing
									= ((RedditThing.Listing)rootThing).getData();

							if(loadingView != null) {
								loadingView.setIndeterminate(R.string.download_downloading);
							}

							// TODO pref (currently 10 mins)
							// TODO xml
							if(fromCache) {
								if (timestamp.elapsed().isGreaterThan(TimeDuration.minutes(10))) {
									AndroidCommon.UI_THREAD_HANDLER.post(() -> {
										if(mDestroyed) {
											return;
										}
										final TextView cacheNotif = new TextView(context);
										cacheNotif.setText(context.getString(
												R.string.listing_cached,
												timestamp.format()));
										final int paddingPx = General.dpToPixels(context, 6);
										final int sidePaddingPx = General.dpToPixels(context, 10);
										cacheNotif.setPadding(
												sidePaddingPx,
												paddingPx,
												sidePaddingPx,
												paddingPx);
										cacheNotif.setTextSize(13f);
										notifications.addView(cacheNotif);
									});
								}
							}

							// TODO {"error": 403} is received for unauthorized subreddits

							if (listing.getChildren().isEmpty()) {

								AndroidCommon.runOnUiThread(() -> {
									final View emptyView =
											LayoutInflater.from(context).inflate(
													R.layout.no_items_yet,
													notifications,
													true);

									((TextView)emptyView.findViewById(R.id.empty_view_text))
											.setText(R.string.no_messages_yet);

									General.setLayoutMatchWidthWrapHeight(emptyView);
								});
							}

							for(final MaybeParseError<RedditThing> maybeThing
									: listing.getChildren()) {

								// TODO show error instead
								final RedditThing thing = maybeThing.ok();

								if(thing instanceof RedditThing.Comment) {
									final RedditComment comment
											= ((RedditThing.Comment) thing).getData();

									mChangeDataManager.update(timestamp, comment);

									final RedditParsedComment parsedComment
											= new RedditParsedComment(
											comment,
											InboxListingActivity.this);

									final RedditRenderableComment renderableComment
											= new RedditRenderableComment(
											parsedComment,
											null,
											-100_000,
											null,
											false,
											true,
											true);

									itemHandler.sendMessage(General.handlerMessage(
											0,
											new InboxItem(
											renderableComment,
											Boolean.TRUE.equals(comment.getNew()))));

								} else if(thing instanceof RedditThing.Message) {

									final RedditMessage messageRaw
											= ((RedditThing.Message) thing).getData();

									mChangeDataManager.update(timestamp, messageRaw);

									final RedditPreparedMessage message
											= new RedditPreparedMessage(
													InboxListingActivity.this,
													messageRaw,
													inboxType);

									itemHandler.sendMessage(General.handlerMessage(
											0,
											new InboxItem(message, Boolean.TRUE.equals(messageRaw.getNew()))));

									if(message.src.getReplies()
											instanceof RedditFieldReplies.Some) {

										// TODO make RedditThing generic (and override data)?

										final ArrayList<MaybeParseError<RedditThing>> replies
												= ((RedditThing.Listing)((RedditFieldReplies.Some)
														message.src.getReplies()).getValue())
																.getData().getChildren();

										for(final MaybeParseError<RedditThing> childMsgValue
												: replies) {

											final RedditMessage childMsgRaw
													= ((RedditThing.Message)childMsgValue.ok())
															.getData();

											mChangeDataManager.update(timestamp, childMsgRaw);

											final RedditPreparedMessage childMsg
													= new RedditPreparedMessage(
															InboxListingActivity.this,
															childMsgRaw,
													inboxType);

											itemHandler.sendMessage(General.handlerMessage(
													0,
													new InboxItem(
													childMsg,
													Boolean.TRUE.equals(childMsgRaw.getNew()))));
										}
									}
								} else {
									throw new RuntimeException("Unknown item in list.");
								}
							}

							if(loadingView != null) {
								loadingView.setDone(R.string.download_done);
							}


						} catch(final Exception e) {
							onFailure(General.getGeneralErrorForFailure(
									context,
									CacheRequest.RequestFailureType.PARSE,
									e,
									null,
									url,
									FailedRequestBody.from(streamFactory)));
						}
					}

					@Override
					public void onFailure(@NonNull final RRError error) {

						if(mDestroyed) {
							return;
						}
						Log.e(TAG, "Inbox fetch error: " + error, error.t);

						request = null;

						if(loadingView != null) {
							loadingView.setDone(R.string.download_failed);
						}

						AndroidCommon.runOnUiThread(() -> {
							if(!mDestroyed) {
								notifications.addView(new ErrorView(InboxListingActivity.this, error));
							}
						});
					}
				});

		cm.makeRequest(request);
	}

	private void recordVisibleUnreadItems() {
		if(mDestroyed || !mResumed || mRecyclerView == null || !mRecyclerView.isShown()
				|| !mRecyclerView.getGlobalVisibleRect(mVisibleBounds)) {
			return;
		}
		mRecyclerView.getLocationOnScreen(mLocationOnScreen);
		mContentBounds.set(
				mLocationOnScreen[0] + mRecyclerView.getPaddingLeft(),
				mLocationOnScreen[1] + mRecyclerView.getPaddingTop(),
				mLocationOnScreen[0] + mRecyclerView.getWidth() - mRecyclerView.getPaddingRight(),
				mLocationOnScreen[1] + mRecyclerView.getHeight() - mRecyclerView.getPaddingBottom());
		if(!mVisibleBounds.intersect(mContentBounds)) {
			return;
		}
		for(int i = 0; i < mRecyclerView.getChildCount(); i++) {
			final View row = mRecyclerView.getChildAt(i);
			if(!row.isShown() || !row.getGlobalVisibleRect(mRowBounds)
					|| !Rect.intersects(mVisibleBounds, mRowBounds)) {
				continue;
			}
			final int position = mRecyclerView.getChildAdapterPosition(row);
			if(position < 0 || position >= adapter.getItemCount()) {
				continue;
			}
			final InboxItem item = (InboxItem)adapter.getItemAtPosition(position);
			mReadOnExit.onVisible(item.mItem.getIdAndType(), item.mUnreadOnLoad);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		mResumed = true;
	}

	@Override
	protected void onPause() {
		recordVisibleUnreadItems();
		mResumed = false;
		super.onPause();
	}

	@Override
	protected void onSaveInstanceState(@NonNull final Bundle state) {
		recordVisibleUnreadItems();
		if(mUser != null && mReadOnExit != null) {
			state.putString(STATE_ACCOUNT, mUser.username);
			state.putStringArrayList(STATE_VIEWED_IDS, mReadOnExit.savedIds());
		}
		super.onSaveInstanceState(state);
	}

	@Override
	public void finish() {
		markViewedMessagesOnExit();
		super.finish();
	}

	@Override
	protected void onStop() {
		if(isFinishing()) {
			markViewedMessagesOnExit();
		}
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		mDestroyed = true;
		cancel();
		itemHandler.removeCallbacksAndMessages(null);
		if(mRecyclerView != null) {
			final ViewTreeObserver observer = mRecyclerView.getViewTreeObserver();
			if(observer.isAlive()) {
				observer.removeOnPreDrawListener(mVisibleItemsListener);
			}
		}
		super.onDestroy();
	}

	private void markViewedMessagesOnExit() {
		if(mUser == null || mReadOnExit == null || mChangeDataManager == null) {
			return;
		}
		recordVisibleUnreadItems();
		final RedditAccount registered = RedditAccountManager.getInstance(this)
				.getAccount(mUser.username);
		final List<List<RedditIdAndType>> batches = mReadOnExit.finish(
				mMarkViewedReadOnExit && mUser.equals(registered),
				inboxType != InboxType.SENT,
				mChangeDataManager::isRead);
		final RedditChangeDataManager changes = mChangeDataManager;
		final Context appContext = getApplicationContext();
		final AtomicBoolean failureShown = new AtomicBoolean();
		for(final List<RedditIdAndType> batch : batches) {
			RedditAPI.markMessagesAsRead(
					CacheManager.getInstance(appContext),
					new APIResponseHandler.ActionResponseHandler(this) {
						@Override
						protected void onSuccess() {
							final TimestampUTC now = TimestampUTC.now();
							for(final RedditIdAndType id : batch) {
								changes.markRead(now, id, true);
							}
						}

						@Override
						protected void onFailure(@NonNull final RRError error) {
							Log.e(TAG, "Failed to mark viewed inbox items read", error.t);
							if(failureShown.compareAndSet(false, true)) {
								General.quickToast(appContext, R.string.inbox_mark_read_failed);
							}
						}

						@Override
						protected void onCallbackException(final Throwable error) {
							BugReportActivity.addGlobalError(new RRError(
									"Mark viewed messages read", "Callback failure", true, error));
						}
					},
					mUser,
					batch,
					appContext);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		if(inboxType == InboxType.SENT) {
			return false;
		}

		final MenuItem refresh = menu.add(0, OPTIONS_MENU_REFRESH, 0, R.string.options_refresh);
		refresh.setIcon(R.drawable.ic_refresh_dark);
		refresh.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

		if (inboxType != InboxType.SENT) {
			menu.add(0, OPTIONS_MENU_MARK_ALL_AS_READ, 1, R.string.mark_all_as_read);
			menu.add(0, OPTIONS_MENU_SHOW_UNREAD_ONLY, 2, R.string.inbox_unread_only);
			menu.getItem(2).setCheckable(true);
			if (mOnlyShowUnread) {
				menu.getItem(2).setChecked(true);
			}
		}

		if(inboxType != InboxType.SENT && mUser.isNotAnonymous()) {
			menu.add(0, OPTIONS_MENU_MARK_VIEWED_ON_EXIT, 3,
					R.string.mark_inbox_as_read_when_back)
					.setCheckable(true)
					.setChecked(mMarkViewedReadOnExit);
		}

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch(item.getItemId()) {
			case OPTIONS_MENU_MARK_ALL_AS_READ:
				RedditAPI.markAllAsRead(
						CacheManager.getInstance(this),
						new APIResponseHandler.ActionResponseHandler(this) {
							@Override
							protected void onSuccess() {
								General.quickToast(
										context,
										R.string.mark_all_as_read_success);

								AndroidCommon.runOnUiThread(() -> {
									final TimestampUTC now = TimestampUTC.now();

									for(final InboxItem item : mItems) {
										mChangeDataManager.markRead(
												now,
												item.mItem.getIdAndType(),
												true);
									}

									adapter.notifyItemRangeChanged(0, adapter.getItemCount());
								});
							}

							@Override
							protected void onCallbackException(final Throwable t) {
								BugReportActivity.addGlobalError(new RRError(
										"Mark all as Read failed",
										"Callback exception",
										true,
										t));
							}

							@Override
							protected void onFailure(@NonNull final RRError error) {
								General.showResultDialog(
										InboxListingActivity.this,
										error);
							}
						},
						mUser,
						this);

				return true;

			case OPTIONS_MENU_MARK_VIEWED_ON_EXIT: {
				mMarkViewedReadOnExit = !item.isChecked();
				item.setChecked(mMarkViewedReadOnExit);
				General.getSharedPrefs(this).edit()
						.putBoolean(PREF_MARK_VIEWED_ON_EXIT, mMarkViewedReadOnExit).apply();
				return true;
			}

			case OPTIONS_MENU_SHOW_UNREAD_ONLY: {

				final boolean enabled = !item.isChecked();

				item.setChecked(enabled);
				mOnlyShowUnread = enabled;

				General.getSharedPrefs(this)
						.edit()
						.putBoolean(PREF_ONLY_UNREAD, enabled)
						.apply();

				General.recreateActivityNoAnimation(this);
				return true;
			}

			case OPTIONS_MENU_REFRESH: {
				General.recreateActivityNoAnimation(this);
				return true;
			}

			default:
				return super.onOptionsItemSelected(item);
		}
	}
}
