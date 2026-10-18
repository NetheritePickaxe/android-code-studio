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
package com.pickaxe.jcode.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class PreferencesManager(context: Context) {
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "git_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    private val normalPrefs: SharedPreferences = context.getSharedPreferences("git_config", Context.MODE_PRIVATE)
    
    // Git Config
    fun setGitUserName(name: String) {
        normalPrefs.edit().putString("git_user_name", name).apply()
    }
    
    fun getGitUserName(): String {
        return normalPrefs.getString("git_user_name", "User") ?: "User"
    }
    
    fun setGitUserEmail(email: String) {
        normalPrefs.edit().putString("git_user_email", email).apply()
    }
    
    fun getGitUserEmail(): String {
        return normalPrefs.getString("git_user_email", "user@example.com") ?: "user@example.com"
    }
    
    // Mirror & Proxy
    fun setGitHubMirror(mirror: String) {
        normalPrefs.edit().putString("git_mirror", mirror.trim()).apply()
    }
    
    fun getGitHubMirror(): String? {
        return normalPrefs.getString("git_mirror", null)
    }
    
    fun setProxyHost(host: String) {
        normalPrefs.edit().putString("git_proxy_host", host.trim()).apply()
    }
    
    fun getProxyHost(): String? {
        return normalPrefs.getString("git_proxy_host", null)
    }
    
    fun setProxyPort(port: Int) {
        normalPrefs.edit().putInt("git_proxy_port", port).apply()
    }
    
    fun getProxyPort(): Int {
        return normalPrefs.getInt("git_proxy_port", 0)
    }
    
    fun setGitTimeout(seconds: Int) {
        normalPrefs.edit().putInt("git_timeout", seconds).apply()
    }
    
    fun getGitTimeout(): Int {
        return normalPrefs.getInt("git_timeout", 60)
    }
    
    // SSH
    fun setSshKeyPath(path: String) {
        normalPrefs.edit().putString("git_ssh_key_path", path.trim()).apply()
    }
    
    fun getSshKeyPath(): String? {
        return normalPrefs.getString("git_ssh_key_path", null)
    }
    
    fun setSshKeyPassphrase(passphrase: String) {
        encryptedPrefs.edit().putString("git_ssh_passphrase", passphrase).apply()
    }
    
    fun getSshKeyPassphrase(): String? {
        return encryptedPrefs.getString("git_ssh_passphrase", null)
    }
    
    fun setSshKnownHostsPath(path: String) {
        normalPrefs.edit().putString("git_ssh_known_hosts", path.trim()).apply()
    }
    
    fun getSshKnownHostsPath(): String? {
        return normalPrefs.getString("git_ssh_known_hosts", null)
    }
    
    fun clearSshConfig() {
        normalPrefs.edit()
            .remove("git_ssh_key_path")
            .remove("git_ssh_known_hosts")
            .apply()
        encryptedPrefs.edit().remove("git_ssh_passphrase").apply()
    }
    
    // Credentials
    fun setRememberCredentials(remember: Boolean) {
        normalPrefs.edit().putBoolean("remember_credentials", remember).apply()
    }
    
    fun shouldRememberCredentials(): Boolean {
        return normalPrefs.getBoolean("remember_credentials", false)
    }
    
    fun saveCredentials(username: String, password: String) {
        if (shouldRememberCredentials()) {
            encryptedPrefs.edit()
                .putString("username", username)
                .putString("password", password)
                .apply()
        }
    }
    
    fun getUsername(): String? {
        return if (shouldRememberCredentials()) {
            encryptedPrefs.getString("username", null)
        } else null
    }
    
    fun getPassword(): String? {
        return if (shouldRememberCredentials()) {
            encryptedPrefs.getString("password", null)
        } else null
    }
    
    fun clearCredentials() {
        encryptedPrefs.edit()
            .remove("username")
            .remove("password")
            .apply()
    }
    
    // OAuth (GitHub device flow)
    fun setOAuthClientId(clientId: String) {
        normalPrefs.edit().putString("git_oauth_client_id", clientId.trim()).apply()
    }
    
    fun getOAuthClientId(): String? {
        return normalPrefs.getString("git_oauth_client_id", null)
    }
    
    /**
     * Stores the token obtained through the OAuth device flow. Kept separately
     * from [saveCredentials] so it does not depend on the "remember credentials"
     * switch, and so signing out falls back to any saved username/password.
     */
    fun saveOAuth(token: String, login: String) {
        encryptedPrefs.edit()
            .putString("oauth_token", token)
            .putString("oauth_login", login)
            .apply()
    }
    
    fun getOAuthToken(): String? {
        return encryptedPrefs.getString("oauth_token", null)
    }
    
    fun getOAuthLogin(): String? {
        return encryptedPrefs.getString("oauth_login", null)
    }
    
    fun clearOAuth() {
        encryptedPrefs.edit()
            .remove("oauth_token")
            .remove("oauth_login")
            .apply()
    }
}
