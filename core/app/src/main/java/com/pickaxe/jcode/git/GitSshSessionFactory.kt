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

import com.jcraft.jsch.JSch
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.pickaxe.jcode.utils.PreferencesManager
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.io.IsolatedOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * JGit SSH transport backed by JSch.
 *
 * JGit 6.x no longer ships a built-in SSH adapter (the old
 * `JschConfigSessionFactory` was removed and the core jar contains no JSch or
 * sshd code), so `SshSessionFactory` has no working implementation out of the
 * box. This class reinstates SSH support using JSch while honouring the
 * user-configured private key, optional passphrase and known_hosts file.
 *
 * The [RemoteSession]/[Process] bridging is a faithful port of JGit's former
 * `JschSession`/`JschSession.JschProcess`, because JGit's SSH client expects
 * `exec()` to hand back a real `java.lang.Process` whose stdout is the git
 * protocol stream.
 */
class GitSshSessionFactory(
    private val keyPath: String?,
    private val passphrase: String?,
    private val knownHostsPath: String?
) : SshSessionFactory() {

    override fun getType(): String = "jsch"

    override fun getSession(
        uri: URIish,
        credentialsProvider: CredentialsProvider?,
        fs: FS?,
        timeout: Int
    ): RemoteSession {
        val jsch = JSch()

        // Optional known_hosts. An empty/missing file still gets registered so
        // JSch can persist newly accepted keys; strict verification is only
        // enabled once the file already holds entries (otherwise the very first
        // connection would always be rejected).
        var knownHosts: File? = null
        if (!knownHostsPath.isNullOrBlank()) {
            val file = File(knownHostsPath)
            if (!file.exists()) {
                runCatching { file.parentFile?.mkdirs(); file.createNewFile() }
            }
            if (file.exists()) knownHosts = file
        }
        knownHosts?.let { jsch.setKnownHosts(it.absolutePath) }
        val strictHostKeyChecking = if ((knownHosts?.length() ?: 0L) > 0L) "yes" else "accept-new"

        // Optional private key (with or without passphrase). When no key is
        // configured JSch still falls back to its default identity lookup.
        if (!keyPath.isNullOrBlank() && File(keyPath).exists()) {
            if (passphrase.isNullOrEmpty()) {
                jsch.addIdentity(keyPath)
            } else {
                jsch.addIdentity(keyPath, passphrase)
            }
        }

        val host = uri.host
            ?: throw TransportException(uri, "SSH URL has no host: $uri")
        val user = uri.user
            ?: credentialsProvider?.let { askUsername(it, uri) }
            ?: DEFAULT_SSH_USER
        val port = if (uri.port > 0) uri.port else DEFAULT_SSH_PORT

        val session = jsch.getSession(user, host, port)
        session.setConfig("StrictHostKeyChecking", strictHostKeyChecking)
        // Prefer public-key auth when a key is present; password auth remains
        // available as a fallback via the credentials provider.
        if (!keyPath.isNullOrBlank() && File(keyPath).exists()) {
            session.setConfig("PreferredAuthentications", "publickey")
        }

        if (credentialsProvider != null) {
            session.userInfo = object : com.jcraft.jsch.UserInfo {
                override fun getPassphrase(): String? = passphrase
                override fun getPassword(): String? = askPassword(credentialsProvider, uri)
                override fun promptYesNo(message: String?): Boolean = true
                override fun promptPassphrase(message: String?): Boolean = true
                override fun promptPassword(message: String?): Boolean = true
                override fun showMessage(message: String?) {}
            }
        }

        session.connect(if (timeout > 0) timeout else 0)
        return GitSshSession(session, uri)
    }

    private fun askUsername(provider: CredentialsProvider, uri: URIish): String? {
        val item = CredentialItem.Username()
        return if (runCatching { provider.get(uri, item) }.getOrNull() == true) {
            item.value?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    private fun askPassword(provider: CredentialsProvider, uri: URIish): String? {
        val item = CredentialItem.Password()
        return if (runCatching { provider.get(uri, item) }.getOrNull() == true) {
            item.value?.let { String(it) }
        } else {
            null
        }
    }

    companion object {
        private const val DEFAULT_SSH_USER = "git"
        private const val DEFAULT_SSH_PORT = 22

        /**
         * Installs a factory configured from [prefs]. Called before any SSH
         * remote operation so the configured private key is honoured.
         */
        fun install(prefs: PreferencesManager) {
            SshSessionFactory.setInstance(
                GitSshSessionFactory(
                    keyPath = prefs.getSshKeyPath(),
                    passphrase = prefs.getSshKeyPassphrase(),
                    knownHostsPath = prefs.getSshKnownHostsPath()
                )
            )
        }
    }
}

/** A live SSH channel wrapped so JGit can drive it like a local process. */
private class GitSshSession(
    private val sock: Session,
    private val uri: URIish
) : RemoteSession {

    override fun exec(command: String, timeout: Int): Process =
        GitSshProcess(sock, uri, command, timeout)

    override fun disconnect() {
        if (sock.isConnected) {
            sock.disconnect()
        }
    }
}

/**
 * Bridges a JSch exec channel to [java.lang.Process]. Ported from JGit's
 * former `JschSession.JschProcess` (removed in 6.x) so the SSH pack transport
 * behaves identically: stdout carries the git protocol, stderr is exposed, and
 * exit status is surfaced once the channel closes.
 */
private class GitSshProcess(
    private val sock: Session,
    private val uri: URIish,
    command: String,
    private val timeout: Int
) : Process() {

    private val channel: ChannelExec
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var errStream: InputStream? = null

    init {
        channel = sock.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        setupStreams()
        try {
            channel.connect(if (timeout > 0) timeout * 1000 else 0)
        } catch (e: com.jcraft.jsch.JSchException) {
            closeOutputStream()
            throw TransportException(uri, e.message ?: "SSH connection failed", e)
        }
        if (!channel.isConnected) {
            closeOutputStream()
            throw TransportException(uri, JGitText.get().connectionFailed)
        }
    }

    private fun closeOutputStream() {
        try {
            outputStream?.close()
        } catch (ignored: IOException) {
        }
    }

    @Throws(IOException::class)
    private fun setupStreams() {
        inputStream = channel.inputStream
        val raw = channel.outputStream
        outputStream = if (timeout > 0) {
            BufferedOutputStream(IsolatedOutputStream(raw), 16384)
        } else {
            raw
        }
        errStream = channel.errStream
    }

    override fun getInputStream(): InputStream = inputStream!!

    override fun getOutputStream(): OutputStream = outputStream!!

    override fun getErrorStream(): InputStream = errStream!!

    override fun exitValue(): Int {
        if (isRunning) {
            throw IllegalStateException()
        }
        return channel.exitStatus
    }

    private val isRunning: Boolean
        get() = channel.exitStatus < 0 && channel.isConnected

    override fun destroy() {
        if (channel.isConnected) {
            channel.disconnect()
        }
        closeOutputStream()
    }

    override fun waitFor(): Int {
        while (isRunning) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return exitValue()
    }
}
