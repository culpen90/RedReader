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

package org.quantumbadger.redreader.reddit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.quantumbadger.redreader.account.RedditAccount;
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Captures only unread items which were actually visible during one inbox visit. */
public final class InboxReadOnExit {

	// Reddit's read_message endpoint accepts at most 25 item fullnames per request.
	public static final int MAX_BATCH_SIZE = 25;
	private static final Pattern INBOX_ID = Pattern.compile("t[14]_[A-Za-z0-9]+");

	@NonNull private final RedditAccount mAccount;
	private final LinkedHashSet<RedditIdAndType> mViewedUnread = new LinkedHashSet<>();
	private boolean mFinished;

	public InboxReadOnExit(
			@NonNull final RedditAccount account,
			@Nullable final List<String> restoredIds) {
		mAccount = account;
		if(restoredIds != null) {
			for(final String id : restoredIds) {
				if(isInboxItemId(id)) {
					mViewedUnread.add(new RedditIdAndType(id));
				}
			}
		}
	}

	private static boolean isInboxItemId(@Nullable final String id) {
		return id != null && INBOX_ID.matcher(id).matches();
	}

	public void onVisible(@NonNull final RedditIdAndType id, final boolean unread) {
		if(!mFinished && unread && isInboxItemId(id.getValue())) {
			mViewedUnread.add(id);
		}
	}

	@NonNull
	public ArrayList<String> savedIds() {
		final ArrayList<String> result = new ArrayList<>(mViewedUnread.size());
		for(final RedditIdAndType id : mViewedUnread) {
			result.add(id.getValue());
		}
		return result;
	}

	/** Freeze the visit before sending anything, so late downloads cannot enter the batch. */
	@NonNull
	public List<List<RedditIdAndType>> finish(
			final boolean enabled,
			final boolean readableInbox,
			@NonNull final Predicate<RedditIdAndType> isRead) {

		if(mFinished) {
			return Collections.emptyList();
		}
		mFinished = true;

		if(!enabled || !readableInbox || mAccount.isAnonymous()) {
			return Collections.emptyList();
		}

		final List<List<RedditIdAndType>> batches = new ArrayList<>();
		List<RedditIdAndType> batch = new ArrayList<>();
		for(final RedditIdAndType id : mViewedUnread) {
			if(isRead.test(id)) {
				continue;
			}
			if(batch.size() == MAX_BATCH_SIZE) {
				batches.add(batch);
				batch = new ArrayList<>();
			}
			batch.add(id);
		}
		if(!batch.isEmpty()) {
			batches.add(batch);
		}
		return batches;
	}
}
