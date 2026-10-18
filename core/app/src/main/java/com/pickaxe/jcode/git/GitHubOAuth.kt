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
package com.pickaxe.jcode.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub OAuth "device flow" (RFC 8628).
 *
 * This is the flow GitHub recommends for clients that cannot receive a browser
 * redirect (like a mobile app): the app asks GitHub for a short user code, the
 * user enters it at github.com/login/device, and the app polls for the access
 * token. The token is then used as the HTTPS password for git operations.
 *
 * A client ID is required. GitHub only issues device codes to registered OAuth
 * Apps, so the user supplies the client ID of their own app (the app must have
 * "Enable Device Flow" checked).
 */
object GitHubOAuth {

    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val USER_URL = "https://api.github.com/user"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private const val USER_AGENT = "jcode-android"

    /** Scopes: private repository access plus the login name for display. */
    const val SCOPE = "repo read:user"

    /** Where the user enters [DeviceCode.userCode]. */
    const val DEFAULT_VERIFICATION_URI = "https://github.com/login/device"

    /** Where the user registers an OAuth App to obtain a client ID. */
    const val CREATE_APP_URL = "https://github.com/settings/applications/new"

    /** Raised when GitHub rejects the flow; [code] is GitHub's error string. */
    class OAuthException(val code: String, message: String) : Exception(message)

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int
    )

    /**
     * Starts the flow. Returns the code the user must type in, together with
     * the polling interval and lifetime of the request.
     */
    suspend fun requestDeviceCode(clientId: String): DeviceCode {
        val json = postForm(
            DEVICE_CODE_URL,
            mapOf("client_id" to clientId, "scope" to SCOPE)
        )
        val deviceCode = json.optString("device_code")
        val userCode = json.optString("user_code")
        if (deviceCode.isBlank() || userCode.isBlank()) {
            throw OAuthException(
                json.optString("error", "invalid_response"),
                json.optString("error_description", "GitHub did not return a device code")
            )
        }
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = json.optString("verification_uri", DEFAULT_VERIFICATION_URI),
            expiresIn = json.optInt("expires_in", 900),
            interval = json.optInt("interval", 5)
        )
    }

    /**
     * Polls until the user authorises the request. Honours the server-supplied
     * interval, backs off on "slow_down" and gives up once the code expires.
     */
    suspend fun awaitToken(clientId: String, device: DeviceCode): String {
        val deadline = System.currentTimeMillis() + device.expiresIn.coerceAtLeast(60) * 1000L
        // GitHub asks clients to wait at least the advertised interval (min 5s)
        // before the first poll.
        var interval = device.interval.coerceAtLeast(5)

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw OAuthException("expired_token", "The device code expired")
            }
            delay(interval * 1000L)

            val json = postForm(
                TOKEN_URL,
                mapOf(
                    "client_id" to clientId,
                    "device_code" to device.deviceCode,
                    "grant_type" to DEVICE_GRANT
                )
            )
            val token = json.optString("access_token")
            if (token.isNotBlank()) return token

            when (json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "expired_token" -> throw OAuthException("expired_token", "The device code expired")
                "access_denied" -> throw OAuthException("access_denied", "Authorization was denied")
                else -> throw OAuthException(
                    json.optString("error", "unknown_error"),
                    json.optString("error_description", "GitHub rejected the sign-in")
                )
            }
        }
    }

    /** Returns the authenticated user's login name, or null on failure. */
    suspend fun fetchLogin(token: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(USER_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("login").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun postForm(url: String, params: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val body = params.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (text.isBlank()) {
                    throw OAuthException("http_$status", "Empty response from GitHub (HTTP $status)")
                }
                JSONObject(text)
            } finally {
                connection?.disconnect()
            }
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
