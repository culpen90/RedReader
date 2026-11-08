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

import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** An immutable snapshot of post filters, compiled once for each listing response. */
public final class PostFilter {

	private final List<String> mTitleTerms = new ArrayList<>();
	private final List<Pattern> mTitlePatterns;
	private final List<Pattern> mSubredditPatterns;

	public PostFilter(
			final List<String> titleTerms,
			final List<String> titleRegexes,
			final List<String> subredditRegexes) {

		for(final String term : titleTerms) {
			if(!term.trim().isEmpty()) {
				mTitleTerms.add(term.trim().toLowerCase(Locale.ROOT));
			}
		}
		mTitlePatterns = compile(titleRegexes);
		mSubredditPatterns = compile(subredditRegexes);
	}

	private static List<Pattern> compile(final List<String> expressions) {
		final List<Pattern> patterns = new ArrayList<>();
		for(final String expression : expressions) {
			if(expression.isEmpty()) {
				continue;
			}
			try {
				patterns.add(Pattern.compile(expression));
			} catch(final PatternSyntaxException e) {
				// Preferences can also arrive through backups, bypassing the input dialog.
				Log.w("PostFilter", "Ignoring an invalid saved filter expression", e);
			}
		}
		return patterns;
	}

	public boolean hidesTitle(@Nullable final String title) {
		if(title == null) {
			return false;
		}
		if(!mTitleTerms.isEmpty()) {
			final String lowerTitle = title.toLowerCase(Locale.ROOT);
			for(final String term : mTitleTerms) {
				if(lowerTitle.contains(term)) {
					return true;
				}
			}
		}
		return matches(mTitlePatterns, title);
	}

	public boolean blocksSubreddit(final String subreddit) {
		return matches(mSubredditPatterns, subreddit);
	}

	private static boolean matches(final List<Pattern> patterns, final String value) {
		for(final Pattern pattern : patterns) {
			if(pattern.matcher(value).find()) {
				return true;
			}
		}
		return false;
	}
}
