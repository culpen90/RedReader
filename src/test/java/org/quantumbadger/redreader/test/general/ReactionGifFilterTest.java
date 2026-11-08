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

package org.quantumbadger.redreader.test.general;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.quantumbadger.redreader.reddit.ReactionGifFilter;

public class ReactionGifFilterTest {

	private final ReactionGifFilter mFilter
			= new ReactionGifFilter(ReactionGifFilter.DEFAULT_DOMAINS);

	@Test
	public void testGifOnly() {
		assertTrue(mFilter.isReactionGif("https://giphy.com/gifs/pynZagVcYxVUk"));
		assertTrue(mFilter.isReactionGif("![gif](giphy|pynZagVcYxVUk)"));
		assertTrue(mFilter.isReactionGif("![gif](giphy|pynZagVcYxVUk|downsized)"));
		assertTrue(mFilter.isReactionGif("https://media.giphy.com/media/abc/giphy.gif"));
		assertTrue(mFilter.isReactionGif("https://tenor.com/view/foo-bar-123"));
		assertTrue(mFilter.isReactionGif("https://i.imgur.com/abc.gifv"));
		assertTrue(mFilter.isReactionGif("[this](https://giphy.com/gifs/abc)"));
	}

	@Test
	public void testGifWithFewWords() {
		assertTrue(mFilter.isReactionGif("lol\n\n![gif](giphy|abc)"));
		assertTrue(mFilter.isReactionGif("Me right now: https://giphy.com/gifs/abc"));
		assertTrue(mFilter.isReactionGif("I can't even ![gif](giphy|abc)"));
	}

	@Test
	public void testGifWithRealText() {
		assertFalse(mFilter.isReactionGif(
				"This scene absolutely destroyed me when I saw it ![gif](giphy|abc)"));
		assertFalse(mFilter.isReactionGif(
				"Beautiful comment from a beautiful man. https://giphy.com/gifs/abc"));
	}

	@Test
	public void testNotGif() {
		assertFalse(mFilter.isReactionGif("lol"));
		assertFalse(mFilter.isReactionGif(""));
		assertFalse(mFilter.isReactionGif(null));
		assertFalse(mFilter.isReactionGif("https://en.wikipedia.org/wiki/Patrick_Stewart"));
		assertFalse(mFilter.isReactionGif("![img](abc123)"));
		assertFalse(mFilter.isReactionGif("https://notgiphy.com/abc"));
		assertFalse(mFilter.isReactionGif(
				"source https://www.bbc.co.uk/news https://giphy.com/gifs/abc"));
	}

	@Test
	public void testCustomDomains() {
		final ReactionGifFilter filter = new ReactionGifFilter("*.example.org,  gfycat.com");
		assertTrue(filter.isReactionGif("https://cdn.example.org/x"));
		assertTrue(filter.isReactionGif("https://gfycat.com/x"));
		assertFalse(filter.isReactionGif("https://giphy.com/gifs/abc"));
		assertTrue(filter.isReactionGif("![gif](giphy|abc)"));
	}
	@Test
	public void testFourWordBoundary() {
		assertTrue(mFilter.isReactionGif("one two three four https://giphy.com/gifs/abc"));
		assertFalse(mFilter.isReactionGif("one two three four five https://giphy.com/gifs/abc"));
		assertTrue(mFilter.isReactionGif("[one two three four](https://tenor.com/view/abc)"));
		assertFalse(mFilter.isReactionGif("[one two three four five](https://tenor.com/view/abc)"));
	}

	@Test
	public void testContractionsAndUnicodeWords() {
		assertTrue(mFilter.isReactionGif("I can't even today ![gif](giphy|abc)"));
		assertFalse(mFilter.isReactionGif("I can't even do this ![gif](giphy|abc)"));
		assertTrue(mFilter.isReactionGif("ÜBER schön café déjà ![gif](giphy|abc)"));
		assertFalse(mFilter.isReactionGif("ÜBER schön café déjà voilà ![gif](giphy|abc)"));
	}

	@Test
	public void testDomainsAreCaseInsensitiveAndRequireLabelBoundary() {
		assertTrue(mFilter.isReactionGif("HTTPS://CDN.GIPHY.COM/media/abc"));
		assertTrue(mFilter.isReactionGif("www.tenor.com/view/abc"));
		assertFalse(mFilter.isReactionGif("https://giphy.com.attacker.example/abc"));
		assertFalse(mFilter.isReactionGif("https://attacker-giphy.com/abc"));
		assertFalse(mFilter.isReactionGif("https://nottenor.com/abc"));
		assertFalse(mFilter.isReactionGif("https://giphy.com@attacker.example/abc"));
		assertFalse(mFilter.isReactionGif("https://giphy.com:443@attacker.example/abc"));
		assertFalse(mFilter.isReactionGif("https://tenor.com:80@attacker.example/abc"));
		assertTrue(mFilter.isReactionGif("https://giphy.com:443/gifs/abc"));
		assertTrue(mFilter.isReactionGif("https://user:password@tenor.com/view/abc"));
	}

	@Test
	public void testDirectGifExtensionsIgnoreQueryAndFragment() {
		assertTrue(mFilter.isReactionGif("https://example.org/ABC.GIF?width=100#frame"));
		assertTrue(mFilter.isReactionGif("https://example.org/abc.gifv#frame"));
		assertFalse(mFilter.isReactionGif("https://example.org/abc.jpg?name=abc.gif"));
		assertFalse(mFilter.isReactionGif("https://example.org/abc.gif.jpg"));
	}

	@Test
	public void testAllLinksMustBeGifLinks() {
		assertTrue(mFilter.isReactionGif(
				"https://giphy.com/gifs/abc https://tenor.com/view/def"));
		assertFalse(mFilter.isReactionGif(
				"![gif](giphy|abc) [source](https://example.org/article)"));
		assertFalse(mFilter.isReactionGif(
				"![gif](giphy|abc) ![img](https://example.org/photo.jpg)"));
	}

	@Test
	public void testEmptyAndMalformedTargetsDoNotMatch() {
		assertFalse(mFilter.isReactionGif("![gif]()"));
		assertFalse(mFilter.isReactionGif("![gif](giphy)"));
		assertFalse(mFilter.isReactionGif("![gif](giphy|)"));
		assertFalse(mFilter.isReactionGif("![gif](giphy||downsized)"));
		assertFalse(mFilter.isReactionGif("![gif](notgiphy|abc)"));
		assertFalse(mFilter.isReactionGif("https://"));
		assertFalse(mFilter.isReactionGif("[not a GIF](not-a-url)"));
	}

	@Test
	public void testCustomDomainNormalizationAndSubdomains() {
		final ReactionGifFilter filter
				= new ReactionGifFilter(" *.EXAMPLE.ORG, .example.net\ncustom.test  ");
		assertTrue(filter.isReactionGif("https://example.org/a"));
		assertTrue(filter.isReactionGif("https://cdn.example.org/a"));
		assertTrue(filter.isReactionGif("https://example.net/a"));
		assertTrue(filter.isReactionGif("https://custom.test/a"));
		assertFalse(filter.isReactionGif("https://notexample.org/a"));
		assertFalse(filter.isReactionGif("https://example.org.attacker.test/a"));
	}

	@Test
	public void testEmptyDomainListKeepsOnlyDirectGifsAndRedditPicker() {
		final ReactionGifFilter filter = new ReactionGifFilter(null);
		assertFalse(filter.isReactionGif("https://giphy.com/gifs/abc"));
		assertTrue(filter.isReactionGif("https://example.org/abc.gif"));
		assertTrue(filter.isReactionGif("![gif](giphy|abc)"));
	}

	@Test
	public void testDisabledFilterAndMissingBodyDoNotMatch() {
		assertFalse(ReactionGifFilter.matches(null,
				new org.quantumbadger.redreader.reddit.kthings.UrlEncodedString(
						"![gif](giphy|abc)")));
		assertFalse(ReactionGifFilter.matches(mFilter, null));
	}

	@Test
	public void testDirectGifTargetsMustBeValidWebUrls() {
		assertFalse(mFilter.isReactionGif("[gif](javascript:alert.gif)"));
		assertFalse(mFilter.isReactionGif("[gif](file:///tmp/reaction.gif)"));
		assertFalse(mFilter.isReactionGif("[gif](not-a-url.gif)"));
		assertFalse(mFilter.isReactionGif("https:///reaction.gif"));
		assertFalse(mFilter.isReactionGif("https://%zz/reaction.gif"));
		assertFalse(mFilter.isReactionGif("[gif](ftp://example.org/reaction.gif)"));
		assertTrue(mFilter.isReactionGif("[gif](https://example.org/reaction.gif)"));
	}

}
