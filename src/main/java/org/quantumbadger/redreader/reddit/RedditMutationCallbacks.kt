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

package org.quantumbadger.redreader.reddit

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.cache.CacheRequestCallbacks
import org.quantumbadger.redreader.common.AndroidCommon
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.GenericFactory
import org.quantumbadger.redreader.common.RRError
import org.quantumbadger.redreader.common.datastream.SeekableInputStream
import org.quantumbadger.redreader.common.time.TimestampUTC
import java.io.IOException
import java.util.UUID

/** Empty successful mutation responses and HTTP-200 Reddit error envelopes share this path. */
class RedditMutationCallbacks(
	private val context: Context,
	private val listener: Listener
) : CacheRequestCallbacks {

	interface Listener {
		fun onSuccess()
		fun onFailure(error: RRError)
	}

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
			val response = streamFactory.create().use { General.readWholeStreamAsUTF8(it) }
			val error = errorInResponse(response)
			if (error == null) {
				AndroidCommon.runOnUiThread { listener.onSuccess() }
			} else {
				onFailure(RRError(
					title = context.getString(R.string.api_action_failed_title),
					message = context.getString(R.string.api_action_failed_message, error),
					reportable = false,
					responseString = response
				))
			}
		} catch (error: Exception) {
			onFailure(RRError(
				title = context.getString(R.string.api_action_failed_title),
				message = context.getString(R.string.api_action_invalid_response),
				t = error
			))
		}
	}

	companion object {
		/** Null means success. Malformed non-empty JSON throws rather than reporting success. */
		@JvmStatic
		fun errorInResponse(response: String): String? {
			if (response.isBlank()) return null
			val parsed = Json.parseToJsonElement(response)
			// JsonElement parsing accepts unquoted primitive tokens, including an HTML
			// login page. Reddit mutation envelopes must be objects or arrays.
			require(parsed is JsonObject || parsed is JsonArray) { "Expected a Reddit API envelope" }
			return findError(parsed)
		}

		private fun findError(value: JsonElement): String? = when (value) {
			is JsonObject -> {
				val errors = value["errors"]
				val error = value["error"]
				when {
					errors != null && errors != JsonNull
						&& (!(errors is JsonArray) || errors.isNotEmpty()) -> errors.toString()
					error != null && error != JsonNull
						&& (error !is JsonPrimitive || error.contentOrNull !in listOf("", "0", "false")) ->
						error.toString()
					(value["success"] as? JsonPrimitive)?.booleanOrNull == false -> "success=false"
					else -> value.values.firstNotNullOfOrNull { findError(it) }
				}
			}
			is JsonArray -> value.firstNotNullOfOrNull { findError(it) }
			else -> null
		}
	}
}
