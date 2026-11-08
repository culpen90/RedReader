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

package org.quantumbadger.redreader.test.reddit

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.activities.ChangelogActivity
import org.quantumbadger.redreader.common.time.TimestampUTC
import org.quantumbadger.redreader.reddit.kthings.*
import org.quantumbadger.redreader.reddit.prepared.RedditParsedPost
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PostGalleryPreviewTest {

	private fun post() = RedditPost(
		id = "gallery", name = RedditIdAndType("t3_gallery"),
		url = UrlEncodedString("https://reddit.com/gallery/gallery"),
		domain = UrlEncodedString("reddit.com"),
		subreddit = UrlEncodedString("test"), num_comments = 1, score = 1,
		created_utc = RedditTimestampUTC(TimestampUTC.fromUtcSecs(0)),
		permalink = UrlEncodedString("/r/test/comments/gallery")
	)

	private fun parse(raw: RedditPost): RedditParsedPost =
		RedditParsedPost(Robolectric.buildActivity(ChangelogActivity::class.java).get(), raw, false)

	private fun image(width: Long, height: Long = width) = ImageMetadata(
		width, height, UrlEncodedString("https://preview.redd.it/" + width + ".jpg")
	)

	private fun gallery(
		source: ImageMetadata = image(12000),
		variants: List<ImageMetadata>? = listOf(image(320), image(640), image(1080))
	): RedditPost = post().copy(
		gallery_data = RedditPost.GalleryData(listOf(MaybeParseError.Ok(
			RedditPost.GalleryData.GalleryItem(UrlEncodedString("first"))
		))),
		media_metadata = mapOf(UrlEncodedString("first") to MaybeParseError.Ok(
			RedditMediaMetadata("valid", "Image", "image/jpeg", source, "first", variants)
		))
	)

	@Test
	fun gallerySelectsTheSmallestSufficientVariantInsteadOfTheFullSource() {
		val parsed = parse(gallery())
		assertEquals(320, parsed.getPreview(300, 0)!!.width)
		assertEquals(1080, parsed.getPreview(720, 0)!!.width)
		assertFalse(parsed.getPreview(720, 0)!!.url.value.contains("12000"))
	}

	@Test
	fun sourceOnlyGalleriesRemainAvailableToTheBoundedLoader() {
		assertEquals(12000, parse(gallery(variants = null)).getPreview(720, 0)!!.width)
		assertTrue(parse(gallery(variants = null)).hasImagePreview())
		assertEquals(2048, parse(gallery(image(2048), null)).getPreview(720, 0)!!.width)
	}

	@Test
	fun corruptGalleryEntriesDoNotAbortTheListingOrHideTheNextValidImage() {
		val raw = gallery()
		val items = listOf<MaybeParseError<RedditPost.GalleryData.GalleryItem>>(
			MaybeParseError.Err(IllegalArgumentException("bad attachment")),
			MaybeParseError.Ok(RedditPost.GalleryData.GalleryItem(UrlEncodedString("missing")))
		) + raw.gallery_data!!.items
		assertEquals(1080, parse(raw.copy(gallery_data = RedditPost.GalleryData(items)))
			.getPreview(720, 0)!!.width)
	}

	@Test
	fun invalidDimensionsUnsupportedSchemesAndFailedMetadataAreSkipped() {
		val invalidVariants = listOf(image(Long.MAX_VALUE), image(-1), image(0),
			ImageMetadata(720, 720, UrlEncodedString("file:///private/image.jpg")))
		assertNull(parse(gallery(image(Long.MAX_VALUE), invalidVariants)).getPreview(720, 0))
		val raw = gallery()
		val metadata = raw.media_metadata!!.values.first().ok().copy(status = "invalid")
		assertNull(parse(raw.copy(media_metadata = mapOf(UrlEncodedString("first") to
			MaybeParseError.Ok(metadata)))).getPreview(720, 0))
	}

	@Test
	fun emptyPreviewArraysAndSourceOnlyImagesAreHandled() {
		val empty = post().copy(preview = RedditPost.Preview(true, emptyList()))
		assertNull(parse(empty).getPreview(100, 0))
		val source = RedditPost.Preview.ImageDetails(UrlEncodedString("https://i.redd.it/image.jpg"), 720, 480)
		val normal = empty.copy(preview = RedditPost.Preview(true, listOf(
			RedditPost.Preview.Image(source, null, RedditPost.Preview.ImageVariants())
		)))
		assertEquals(720, parse(normal).getPreview(100, 0)!!.width)
	}

	@Test
	fun extremeTallGalleryImagesPreferTheSmallerAvailableVariant() {
		assertEquals(720, parse(gallery(image(1000, 100000), listOf(image(720, 720))))
			.getPreview(720, 0)!!.width)
	}
}
