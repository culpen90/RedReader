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

package org.quantumbadger.redreader.common;

import androidx.annotation.NonNull;

import org.quantumbadger.redreader.reddit.things.InvalidSubredditNameException;
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId;
import org.quantumbadger.redreader.reddit.url.SubredditPostListURL;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** A validated snapshot of ordinary subreddit pins suitable for a combined listing. */
public final class PinnedSubredditFeed {

	// Application request-size safeguards. Never silently truncate a user's pinned feed.
	private static final int MAX_SUBREDDITS = 100;
	private static final int MAX_COMBINATION_CHARACTERS = 1500;
	private static final Set<String> SPECIAL_FEEDS = new HashSet<>(Arrays.asList(
			"all", "popular", "random", "randnsfw", "friends", "mod"));

	@NonNull private final String mCombination;
	private final int mSubredditCount;

	private PinnedSubredditFeed(@NonNull final TreeSet<String> subreddits) {
		mCombination = StringUtils.join(subreddits, "+");
		mSubredditCount = subreddits.size();
	}

	@NonNull
	public static PinnedSubredditFeed from(@NonNull final List<SubredditCanonicalId> pins) {
		final TreeSet<String> subreddits = new TreeSet<>();
		for(final SubredditCanonicalId pin : pins) {
			// Profile pins have a different URL namespace and cannot be joined into /r/ feeds.
			if(!pin.toString().startsWith("/r/")) {
				continue;
			}
			final String[] components = pin.getDisplayNameLowercase().split("[+]", -1);
			boolean valid = true;
			for(final String component : components) {
				if(!component.matches("[a-z0-9_]+") || component.startsWith("u_")
						|| SPECIAL_FEEDS.contains(component)) {
					valid = false;
					break;
				}
			}
			if(valid) {
				subreddits.addAll(Arrays.asList(components));
			}
		}
		return new PinnedSubredditFeed(subreddits);
	}

	public boolean isEmpty() {
		return mSubredditCount == 0;
	}

	public boolean isTooLarge() {
		return mSubredditCount > MAX_SUBREDDITS
				|| mCombination.length() > MAX_COMBINATION_CHARACTERS;
	}

	@NonNull
	public SubredditPostListURL getUrl() {
		if(isEmpty() || isTooLarge()) {
			throw new IllegalStateException("The pinned feed has no valid bounded listing URL");
		}
		try {
			return SubredditPostListURL.getSubreddit(mCombination).asSubredditPostListURL();
		} catch(final InvalidSubredditNameException e) {
			throw new IllegalStateException("Validated subreddit combination was rejected", e);
		}
	}
}
