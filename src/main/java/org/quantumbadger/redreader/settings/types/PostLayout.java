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

package org.quantumbadger.redreader.settings.types;

/** Post layout affects presentation and candidate selection, never media-loading permission. */
public enum PostLayout {
	THUMBNAILS("thumbnails"), ALWAYS_PREVIEW("always_preview"), CARD("card");

	private final String mValue;

	PostLayout(final String value) {
		mValue = value;
	}

	public String getPreferenceValue() {
		return mValue;
	}

	public boolean usesLargePreviews() {
		return this != THUMBNAILS;
	}

	public static PostLayout fromPreference(final String value) {
		for(final PostLayout layout : values()) {
			if(layout.mValue.equals(value)) {
				return layout;
			}
		}
		return THUMBNAILS;
	}
}
