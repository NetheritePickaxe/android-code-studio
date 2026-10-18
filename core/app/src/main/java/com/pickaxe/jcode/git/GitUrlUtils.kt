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

import java.io.File

/**
 * Helpers for handling user-entered Git remote URLs.
 *
 * The previous clone code appended ".git" to every URL that did not already
 * end with it, which turned "https://host/org/repo/" into "…/repo/.git" and
 * broke scp-like (git@host:org/repo.git) and query-carrying URLs. It also
 * derived the local directory name with a naive substringAfterLast('/').
 */
object GitUrlUtils {

    private val SCP_LIKE = Regex("^[\\w.-]+@[\\w.-]+:.+$")

    /** Returns true when [raw] looks like a remote URL JGit can transport. */
    fun isValidRemoteUrl(raw: String): Boolean {
        val url = raw.trim()
        if (url.isEmpty()) return false
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("git://", ignoreCase = true) ||
            url.startsWith("ssh://", ignoreCase = true) ||
            url.startsWith("git+ssh://", ignoreCase = true) ||
            SCP_LIKE.matches(url)
    }

    /**
     * Normalizes a remote URL for cloning:
     * - adds an implicit "https://" when the user pasted "host/org/repo"
     * - strips trailing slashes and appends ".git" only for scheme URLs whose
     *   last path segment looks like a repository name
     * - passes scp-like ("git@host:org/repo.git"), ssh:// and URLs carrying a
     *   query or fragment through untouched
     */
    fun normalizeCloneUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url

        if (!url.contains("://") && !SCP_LIKE.matches(url) && url.contains('/')) {
            url = "https://$url"
        }
        if (!isValidRemoteUrl(url) || SCP_LIKE.matches(url)) return url

        while (url.length > "https://x".length && url.endsWith("/")) {
            url = url.dropLast(1)
        }
        if (url.contains('?') || url.contains('#')) return url

        val lastSegment = url.substringAfterLast('/')
        if (!lastSegment.contains(':') && !lastSegment.endsWith(".git")) {
            url += ".git"
        }
        return url
    }

    /** Derives a safe local directory name from a remote URL. */
    fun deriveRepoName(raw: String): String {
        val url = raw.trim().trimEnd('/')
        val last = if (SCP_LIKE.matches(url)) {
            url.substringAfterLast(':')
        } else {
            url.substringAfterLast('/')
        }
        val name = last
            .removeSuffix(".git")
            .substringBefore('?')
            .substringBefore('#')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
        return name.ifBlank { "repo" }
    }

    /**
     * Picks a clone target directory that does not collide with a previous
     * (possibly partial) clone: "repo", "repo-1", "repo-2", ...
     */
    fun uniqueTargetDir(baseDir: File, repoName: String): File {
        var target = File(baseDir, repoName)
        var counter = 1
        while (target.exists() && target.list()?.isNotEmpty() == true) {
            target = File(baseDir, "$repoName-$counter")
            counter++
        }
        return target
    }
}
