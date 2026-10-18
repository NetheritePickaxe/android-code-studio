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

import com.pickaxe.jcode.utils.PreferencesManager
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.regex.Pattern

/**
 * Global Git configuration helpers.
 *
 * Handles:
 * - URL rewriting through an optional GitHub mirror/proxy prefix
 * - HTTP(S) proxy configuration via the system ProxySelector
 * - Building a CredentialsProvider only when credentials exist, so public
 *   repositories keep working anonymously
 * - Optional connect/read timeouts via TransportCommand.setTimeout
 */
object GitConfigHelper {

    private val githubUrlPattern =
        Pattern.compile("^https?://github\\.com(:\\d+)?(/.*)?$", Pattern.CASE_INSENSITIVE)

    /**
     * Prepends the configured mirror to a github.com URL, e.g.
     * `https://ghproxy.com/` + `https://github.com/user/repo.git`. Non-GitHub
     * URLs, and URLs already going through the mirror, are returned untouched.
     */
    fun applyGitHubMirror(url: String, prefs: PreferencesManager): String {
        val mirror = prefs.getGitHubMirror()?.trim().orEmpty()
        if (mirror.isBlank()) return url
        if (!githubUrlPattern.matcher(url).matches()) return url
        if (url.startsWith(mirror, ignoreCase = true)) return url
        val base = if (mirror.endsWith("/")) mirror else "$mirror/"
        return base + url
    }

    /**
     * Returns a CredentialsProvider built from the signed-in OAuth token, then
     * from the stored username/token, or null when nothing is stored. Sending an
     * empty Basic auth header breaks some servers, so anonymous access is
     * preferred until the user configures credentials in the Git settings.
     */
    fun credentialsProvider(prefs: PreferencesManager): CredentialsProvider? {
        val oauthToken = prefs.getOAuthToken()
        if (!oauthToken.isNullOrBlank()) {
            // GitHub accepts the OAuth token as the HTTPS password; any username
            // works, but the real login reads best in server logs.
            val login = prefs.getOAuthLogin()?.takeIf { it.isNotBlank() } ?: "x-access-token"
            return UsernamePasswordCredentialsProvider(login, oauthToken)
        }
        val username = prefs.getUsername()
        val password = prefs.getPassword()
        if (username.isNullOrBlank() && password.isNullOrBlank()) return null
        return UsernamePasswordCredentialsProvider(username ?: "", password ?: "")
    }

    /**
     * Routes HTTP(S) connections made by JGit through the configured proxy by
     * installing a global ProxySelector. Cleared when no proxy is configured.
     */
    fun configureProxy(prefs: PreferencesManager) {
        try {
            val proxyHost = prefs.getProxyHost()
            val proxyPort = prefs.getProxyPort()
            if (proxyHost.isNullOrBlank() || proxyPort <= 0) {
                ProxySelector.setDefault(null)
                return
            }
            val address = InetSocketAddress(proxyHost, proxyPort)
            val selector = object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> {
                    return listOf(Proxy(Proxy.Type.HTTP, address))
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
            }
            ProxySelector.setDefault(selector)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun setTimeouts(command: TransportCommand<*, *>, prefs: PreferencesManager) {
        try {
            val timeout = prefs.getGitTimeout()
            if (timeout > 0) {
                command.setTimeout(timeout)
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
