/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.pickaxe.jcode.artificial.harness.providers

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal val JSON_MEDIA = "application/json".toMediaType()

/** Shared HTTP client for all chat providers. */
internal val HTTP: OkHttpClient by lazy {
  OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()
}

/** Parse a tool-call arguments string into a JSON object, tolerating junk. */
internal fun argsToJson(raw: String): JSONObject =
  if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrElse { JSONObject() }

/** Run a request and return the response body, throwing a readable error on failure. */
internal fun fetchJson(providerName: String, request: Request): String {
  HTTP.newCall(request).execute().use { response ->
    val text = response.body?.string().orEmpty()
    if (!response.isSuccessful) {
      throw RuntimeException("$providerName error (HTTP ${response.code}): ${text.take(600)}")
    }
    return text
  }
}
