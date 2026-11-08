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

package org.quantumbadger.redreader.reddit.api

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.cache.CacheRequestCallbacks
import org.quantumbadger.redreader.common.AndroidCommon
import org.quantumbadger.redreader.common.Constants
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.GenericFactory
import org.quantumbadger.redreader.common.RRError
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.common.datastream.SeekableInputStream
import org.quantumbadger.redreader.common.time.TimestampUTC
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.http.PostField
import org.quantumbadger.redreader.http.body.HTTPRequestBody
import org.quantumbadger.redreader.reddit.RedditMutationCallbacks
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId
import java.io.IOException
import java.util.UUID

/** Request factories capture both account and full target before the request is queued. */
object MultiredditApi {

	interface ActionListener : RedditMutationCallbacks.Listener

	interface MembersListener {
		fun onSuccess(members: List<String>)
		fun onFailure(error: RRError)
	}

	@JvmStatic
	fun newCreateRequest(
		context: Context,
		account: RedditAccount,
		target: MultiredditTarget,
		subreddits: Collection<SubredditCanonicalId>,
		listener: ActionListener
	): CacheRequest {
		target.requireOwnedBy(account)
		val members = subreddits.map { subredditName(it) }.distinct()
		require(members.isNotEmpty() && members.size <= 100) { "Invalid multireddit size" }
		val model = buildJsonObject {
			put("display_name", target.name)
			put("visibility", "private")
			put("subreddits", JsonArray(members.map { name ->
				buildJsonObject { put("name", name) }
			}))
		}
		return mutation(context, account, target, null, HTTPBackend.Method.POST, model, listener)
	}

	@JvmStatic
	fun newAddSubredditRequest(
		context: Context,
		account: RedditAccount,
		target: MultiredditTarget,
		subreddit: SubredditCanonicalId,
		listener: ActionListener
	): CacheRequest {
		val name = subredditName(subreddit)
		return mutation(context, account, target, name, HTTPBackend.Method.PUT,
			buildJsonObject { put("name", name) }, listener)
	}

	@JvmStatic
	fun newRemoveSubredditRequest(
		context: Context,
		account: RedditAccount,
		target: MultiredditTarget,
		subreddit: SubredditCanonicalId,
		listener: ActionListener
	): CacheRequest = mutation(context, account, target, subredditName(subreddit),
		HTTPBackend.Method.DELETE, null, listener)

	@JvmStatic
	fun newDeleteRequest(
		context: Context,
		account: RedditAccount,
		target: MultiredditTarget,
		listener: ActionListener
	): CacheRequest = mutation(context, account, target, null,
		HTTPBackend.Method.DELETE, null, listener)

	private fun mutation(
		context: Context,
		account: RedditAccount,
		target: MultiredditTarget,
		subreddit: String?,
		method: HTTPBackend.Method,
		model: JsonObject?,
		listener: ActionListener
	): CacheRequest {
		target.requireOwnedBy(account)
		val body = model?.let {
			HTTPRequestBody.PostFields(PostField("model", it.toString()))
		}
		return CacheRequest.newUncachedRequest(apiUrl(target, subreddit), account, method,
			body, context, RedditMutationCallbacks(context, listener))
	}

	@JvmStatic
	fun newMembersRequest(
		context: Context,
		account: RedditAccount,
		target: MultiredditTarget,
		listener: MembersListener
	): CacheRequest {
		target.requireOwnedBy(account)
		return CacheRequest.newUncachedRequest(apiUrl(target, null), account,
			HTTPBackend.Method.GET, null, context, object : CacheRequestCallbacks {
				override fun onFailure(error: RRError) {
					AndroidCommon.runOnUiThread { listener.onFailure(error) }
				}

				override fun onDataStreamComplete(
					streamFactory: GenericFactory<SeekableInputStream, IOException>,
					timestamp: TimestampUTC,
					session: UUID,
					fromCache: Boolean,
					mimetype: String?
				) {
					try {
						val text = streamFactory.create().use { General.readWholeStreamAsUTF8(it) }
						val members = parseMembers(text)
						AndroidCommon.runOnUiThread { listener.onSuccess(members) }
					} catch (error: Exception) {
						onFailure(RRError(
							title = context.getString(R.string.api_action_failed_title),
							message = context.getString(R.string.api_action_invalid_response),
							t = error
						))
					}
				}
			})
	}

	@JvmStatic
	fun newOwnedNamesRequest(
		context: Context,
		account: RedditAccount,
		listener: MembersListener
	): CacheRequest {
		require(!account.isAnonymous) { "A signed-in account is required" }
		return CacheRequest.newUncachedRequest(
			Constants.Reddit.getUri(Constants.Reddit.PATH_MULTIREDDITS_MINE), account,
			HTTPBackend.Method.GET, null, context, object : CacheRequestCallbacks {
				override fun onFailure(error: RRError) {
					AndroidCommon.runOnUiThread { listener.onFailure(error) }
				}

				override fun onDataStreamComplete(
					streamFactory: GenericFactory<SeekableInputStream, IOException>,
					timestamp: TimestampUTC,
					session: UUID,
					fromCache: Boolean,
					mimetype: String?
				) {
					try {
						val text = streamFactory.create().use { General.readWholeStreamAsUTF8(it) }
						val names = parseOwnedNames(text, account)
						AndroidCommon.runOnUiThread { listener.onSuccess(names) }
					} catch (error: Exception) {
						onFailure(RRError(
							title = context.getString(R.string.api_action_failed_title),
							message = context.getString(R.string.api_action_invalid_response),
							t = error
						))
					}
				}
			})
	}

	@JvmStatic
	fun parseOwnedNames(response: String, account: RedditAccount): List<String> {
		require(RedditMutationCallbacks.errorInResponse(response) == null) {
			"The API returned an error"
		}
		val result = Json.parseToJsonElement(response) as? JsonArray
			?: throw IllegalArgumentException("Expected a list of multireddits")
		return result.mapNotNull { item ->
			val data = (item as? JsonObject)?.get("data") as? JsonObject
				?: throw IllegalArgumentException("Missing multireddit data")
			val name = (data["name"] as? JsonPrimitive)?.contentOrNull
				?: throw IllegalArgumentException("Missing multireddit name")
			val owner = (data["owner"] as? JsonPrimitive)?.contentOrNull
			val path = (data["path"] as? JsonPrimitive)?.contentOrNull
			val pathOwner = path?.let {
				val segments = it.toUri().pathSegments
				if (segments.size != 4 || segments[0] != "user" || segments[2] != "m"
					|| !segments[3].equals(name, ignoreCase = true)) {
					throw IllegalArgumentException("Invalid multireddit path")
				}
				segments[1]
			}
			require(owner != null || pathOwner != null) { "Missing multireddit owner" }
			if (owner != null && !owner.equals(account.canonicalUsername, ignoreCase = true)
				|| pathOwner != null && !pathOwner.equals(account.canonicalUsername, ignoreCase = true)
			) {
				null
			} else {
				MultiredditTarget.forName(account, name).name
			}
		}.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
	}

	@JvmStatic
	fun parseMembers(response: String): List<String> {
		require(RedditMutationCallbacks.errorInResponse(response) == null) {
			"The API returned an error"
		}
		val result = Json.parseToJsonElement(response) as? JsonObject
			?: throw IllegalArgumentException("Expected multireddit object")
		val data = result["data"] as? JsonObject
			?: throw IllegalArgumentException("Missing multireddit data")
		val members = data["subreddits"] as? JsonArray
			?: throw IllegalArgumentException("Missing multireddit members")
		return members.map { member ->
			val name = ((member as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
				?: throw IllegalArgumentException("Missing subreddit name")
			subredditName(SubredditCanonicalId(name))
		}.distinct().sorted()
	}

	@JvmStatic
	fun canAddSubreddit(subreddit: SubredditCanonicalId): Boolean {
		val name = subreddit.displayNameLowercase
		return subreddit.toString().startsWith("/r/") && subredditPattern.matches(name)
			&& name !in specialFeeds
	}

	@JvmStatic
	fun subredditName(subreddit: SubredditCanonicalId): String {
		require(canAddSubreddit(subreddit)) { "Expected one ordinary subreddit" }
		return subreddit.displayNameLowercase
	}

	private val subredditPattern = Regex("[a-z0-9_]+")
	private val specialFeeds = setOf("all", "popular", "friends", "mod", "random", "randnsfw")

	private fun apiUrl(target: MultiredditTarget, subreddit: String?): UriString {
		val path = Uri.Builder().encodedPath("/api/multi")
			.appendPath("user").appendPath(target.owner).appendPath("m").appendPath(target.name)
		if (subreddit != null) path.appendPath("r").appendPath(subreddit)
		return Constants.Reddit.getUri(path.build().toString())
	}
}
