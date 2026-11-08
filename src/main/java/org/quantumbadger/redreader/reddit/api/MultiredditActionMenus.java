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

package org.quantumbadger.redreader.reddit.api;

import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.account.RedditAccount;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.common.Constants;
import org.quantumbadger.redreader.common.LinkHandler;
import org.quantumbadger.redreader.reddit.url.MultiredditPostListURL;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.fragments.MultiredditDialogs;
import org.quantumbadger.redreader.reddit.things.InvalidSubredditNameException;
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId;
import org.quantumbadger.redreader.reddit.url.PostListingURL;
import org.quantumbadger.redreader.reddit.url.SubredditPostListURL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Context actions keep the identity of the displayed listing, not the current default. */
public final class MultiredditActionMenus {

	private MultiredditActionMenus() { }

	public static void attachToHeader(
			final AppCompatActivity activity,
			final RedditAccount account,
			final PostListingURL url,
			final View header) {
		if(url instanceof SubredditPostListURL
				&& ((SubredditPostListURL)url).type
						== SubredditPostListURL.Type.SUBREDDIT_COMBINATION) {
			header.setOnLongClickListener(view -> {
				showCombination(activity, account, (SubredditPostListURL)url);
				return true;
			});
		}
		if(url instanceof MultiredditPostListURL && !account.isAnonymous()) {
			final MultiredditTarget target;
			try {
				target = MultiredditTarget.fromListing(account, (MultiredditPostListURL)url);
			} catch(final IllegalArgumentException invalid) {
				return;
			}
			if(target.isOwnedBy(account)) {
				header.setOnLongClickListener(view -> {
					showMultireddit(activity, account, target,
							() -> LinkHandler.onLinkClicked(
									activity, Constants.Reddit.getNonAPIUri("/")));
					return true;
				});
			}
		}
	}

	@Nullable
	public static AlertDialog showCombination(
			final AppCompatActivity activity,
			final RedditAccount account,
			final SubredditPostListURL url) {
		if(account.isAnonymous() || !PrefsUtility.getStringSet(
				R.string.pref_menus_subreddit_combinations_context_items_key,
				R.array.pref_menus_subreddit_combinations_context_items_return)
				.contains("create_multireddit")) {
			return null;
		}
		final List<SubredditCanonicalId> subreddits;
		try {
			subreddits = combinationSubreddits(url);
		} catch(final IllegalArgumentException invalid) {
			General.quickToast(activity, R.string.multireddit_invalid_members);
			return null;
		}
		return new MaterialAlertDialogBuilder(activity)
				.setTitle(url.humanReadableName(activity, true))
				.setItems(new String[] { activity.getString(R.string.create_multireddit) },
						(dialog, item) -> MultiredditDialogs.showCreate(
								activity, account, subreddits))
				.show();
	}

	@Nullable
	public static AlertDialog showMultireddit(
			final AppCompatActivity activity,
			final RedditAccount account,
			final MultiredditTarget target,
			@Nullable final Runnable onDeleted) {
		if(!target.isOwnedBy(account)) {
			return null;
		}
		final ArrayList<String> titles = new ArrayList<>();
		final ArrayList<Runnable> actions = new ArrayList<>();
		if(PrefsUtility.getStringSet(
				R.string.pref_menus_multireddits_context_items_key,
				R.array.pref_menus_multireddits_context_items_return)
				.contains("delete_multireddit")) {
			titles.add(activity.getString(R.string.delete_multireddit));
			actions.add(() -> MultiredditDialogs.showDelete(activity, account, target, onDeleted));
		}
		if(actions.isEmpty()) {
			return null;
		}
		return new MaterialAlertDialogBuilder(activity)
				.setTitle(target.getDisplayPath())
				.setItems(titles.toArray(new String[0]), (dialog, item) -> actions.get(item).run())
				.show();
	}

	public static List<SubredditCanonicalId> combinationSubreddits(final SubredditPostListURL url) {
		if(url.type != SubredditPostListURL.Type.SUBREDDIT_COMBINATION || url.subreddit == null) {
			throw new IllegalArgumentException("Expected a subreddit combination");
		}
		final LinkedHashSet<SubredditCanonicalId> members = new LinkedHashSet<>();
		try {
			for(final String name : url.subreddit.split("\\+", -1)) {
				final SubredditCanonicalId member = new SubredditCanonicalId(name);
				MultiredditApi.subredditName(member);
				members.add(member);
			}
		} catch(final InvalidSubredditNameException invalid) {
			throw new IllegalArgumentException("Invalid subreddit combination", invalid);
		}
		if(members.isEmpty() || members.size() > 100) {
			throw new IllegalArgumentException("Invalid multireddit size");
		}
		return Collections.unmodifiableList(new ArrayList<>(members));
	}
}
