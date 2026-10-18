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

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.ignore.IgnoreNode
import org.eclipse.jgit.attributes.AttributesNode
import org.eclipse.jgit.attributes.AttributesNodeProvider
import java.io.File
import java.io.FileInputStream
import com.pickaxe.jcode.utils.*
import com.pickaxe.jcode.utils.PreferencesManager

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class GitManager(private val projectPath: String) {
    
    private var git: Git? = null
    private var repository: Repository? = null
    private var ignoreNode: IgnoreNode? = null
    private var attributesNode: AttributesNode? = null

    /** Message of the last failed operation, surfaced to the UI. */
    var lastError: String? = null
        private set
    
    fun initRepository(initialBranchName: String = "main"): Boolean {
        return try {
            val projectDir = File(projectPath)
            if (!projectDir.exists()) {
                projectDir.mkdirs()
            }
            
            git = Git.init().setDirectory(projectDir).call()
            repository = git?.repository
            
            if (initialBranchName != "master") {
                try {
                    git?.branchRename()?.setOldName("master")?.setNewName(initialBranchName)?.call()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            loadGitIgnore()
            loadGitAttributes()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun openRepository(): Boolean {
        return try {
            val gitDir = File(projectPath, ".git")
            if (!gitDir.exists()) return false
            
            repository = FileRepositoryBuilder()
                .setGitDir(gitDir)
                .readEnvironment()
                .findGitDir()
                .build()
            
            git = Git(repository)
            loadGitIgnore()
            loadGitAttributes()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private fun loadGitIgnore() {
        try {
            val gitignoreFile = File(projectPath, ".gitignore")
            if (gitignoreFile.exists()) {
                ignoreNode = IgnoreNode()
                FileInputStream(gitignoreFile).use { input ->
                    ignoreNode?.parse(input)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun loadGitAttributes() {
        try {
            val gitattributesFile = File(projectPath, ".gitattributes")
            if (gitattributesFile.exists()) {
                attributesNode = AttributesNode()
                FileInputStream(gitattributesFile).use { input ->
                    attributesNode?.parse(input)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun isIgnored(path: String): Boolean {
        return try {
            val workTree = repository?.workTree ?: return false
            val file = File(workTree, path)
            val relativePath = file.relativeTo(workTree).path.replace(File.separatorChar, '/')
            
            ignoreNode?.isIgnored(relativePath, file.isDirectory) == IgnoreNode.MatchResult.IGNORED
        } catch (e: Exception) {
            false
        }
    }
    
    fun getStatus(): Status? {
        return try {
            git?.status()?.call()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun setUserConfig(name: String, email: String): Boolean {
        return try {
            val config = repository?.config
            config?.setString("user", null, "name", name)
            config?.setString("user", null, "email", email)
            config?.save()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getUserConfig(): Pair<String, String>? {
        return try {
            val config = repository?.config
            val name = config?.getString("user", null, "name") ?: "User"
            val email = config?.getString("user", null, "email") ?: "user@example.com"
            Pair(name, email)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun getChangedFiles(): List<FileChange> {
        val changes = mutableListOf<FileChange>()
        val status = getStatus() ?: return changes
        
        status.added.forEach {
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.ADDED, isStaged = true))
            }
        }
        
        status.changed.forEach {
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.MODIFIED, isStaged = true))
            }
        }
        
        status.removed.forEach {
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.DELETED, isStaged = true))
            }
        }
        
        status.modified.forEach {
            if (!status.changed.contains(it) && !isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.MODIFIED, isStaged = false))
            }
        }
        
        status.missing.forEach {
            if (!status.removed.contains(it) && !isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.DELETED, isStaged = false))
            }
        }
        
        status.untracked.forEach { 
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.UNTRACKED, isStaged = false))
            }
        }
        
        status.conflicting.forEach {
            if (!isIgnored(it)) {
                changes.add(FileChange(it, ChangeType.CONFLICTING, isStaged = false))
            }
        }
        
        return changes
    }
    
    fun stageFile(filePath: String): Boolean {
        return try {
            if (isIgnored(filePath)) {
                // Force add ignored files if explicitly staged
                git?.add()?.addFilepattern(filePath)?.setUpdate(false)?.call()
            } else {
                git?.add()?.addFilepattern(filePath)?.call()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun stageAllFiles(): Boolean {
        return try {
            git?.add()?.addFilepattern(".")?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun unstageFile(filePath: String): Boolean {
        return try {
            git?.reset()?.addPath(filePath)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun commit(message: String, author: String = "User", email: String = "user@example.com"): Boolean {
        return try {
            git?.commit()
                ?.setMessage(message)
                ?.setAuthor(PersonIdent(author, email))
                ?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getCommitHistory(maxCount: Int = 100): List<CommitInfo> {
        val commits = mutableListOf<CommitInfo>()
        try {
            val logs = git?.log()?.setMaxCount(maxCount)?.call()
            logs?.forEach { commit ->
                commits.add(
                    CommitInfo(
                        hash = commit.name,
                        shortHash = commit.name.substring(0, 7),
                        message = commit.fullMessage,
                        author = commit.authorIdent.name,
                        email = commit.authorIdent.emailAddress,
                        timestamp = commit.commitTime.toLong() * 1000
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return commits
    }
    
    fun getCurrentBranch(): String? {
        return try {
            repository?.branch
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun getAllBranches(): List<String> {
        val branches = mutableListOf<String>()
        try {
            git?.branchList()?.call()?.forEach { ref ->
                branches.add(ref.name.removePrefix("refs/heads/"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return branches
    }
    
    fun createBranch(branchName: String): Boolean {
        return try {
            git?.branchCreate()?.setName(branchName)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun checkoutBranch(branchName: String): Boolean {
        return try {
            git?.checkout()?.setName(branchName)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun deleteBranch(branchName: String): Boolean {
        return try {
            git?.branchDelete()?.setBranchNames(branchName)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getFileDiff(filePath: String): String {
        return try {
            val repo = repository ?: return ""
            val head = repo.resolve(Constants.HEAD)
            
            RevWalk(repo).use { revWalk ->
                val commit = revWalk.parseCommit(head)
                val tree = commit.tree
                
                val oldTreeParser = CanonicalTreeParser()
                repo.newObjectReader().use { reader ->
                    oldTreeParser.reset(reader, tree)
                }
                
                val diffs = git?.diff()
                    ?.setOldTree(oldTreeParser)
                    ?.setPathFilter(PathFilter.create(filePath))
                    ?.call()
                
                val diffBuilder = StringBuilder()
                diffs?.forEach { diff ->
                    diffBuilder.append("${diff.changeType}: ${diff.newPath}\n")
                }
                diffBuilder.toString()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
    
    fun discardChanges(filePath: String): Boolean {
        return try {
            git?.checkout()?.addPath(filePath)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun reloadGitIgnoreAndAttributes() {
        loadGitIgnore()
        loadGitAttributes()
    }
    
    fun close() {
        git?.close()
        repository?.close()
    }

    fun addRemote(name: String, url: String): Boolean {
        return try {
            val remoteAddCommand = git?.remoteAdd()
            remoteAddCommand?.setName(name)
            remoteAddCommand?.setUri(org.eclipse.jgit.transport.URIish(url))
            remoteAddCommand?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun removeRemote(name: String): Boolean {
        return try {
            git?.remoteRemove()?.setRemoteName(name)?.call()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getRemotes(): List<RemoteInfo> {
        val remotes = mutableListOf<RemoteInfo>()
        try {
            git?.remoteList()?.call()?.forEach { remote ->
                remotes.add(
                    RemoteInfo(
                        name = remote.name,
                        fetchUrl = remote.urIs.firstOrNull()?.toString() ?: "",
                        pushUrl = remote.urIs.firstOrNull()?.toString() ?: ""
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return remotes
    }
    
    fun push(
        remoteName: String = "origin",
        branchName: String? = null,
        username: String? = null,
        password: String? = null,
        prefs: PreferencesManager? = null
    ): PushResult {
        return try {
            val pushCommand = git?.push()
            pushCommand?.setRemote(remoteName)
            
            if (branchName != null) {
                pushCommand?.setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/$branchName:refs/heads/$branchName"))
            }
            
            credentialProviderFor(prefs, username, password)?.let { pushCommand?.setCredentialsProvider(it) }
            if (prefs != null) {
                GitConfigHelper.configureProxy(prefs)
                GitSshSessionFactory.install(prefs)
                pushCommand?.let { GitConfigHelper.setTimeouts(it, prefs) }
            }
            
            val results = pushCommand?.call()
            val success = results?.all { result ->
                result.remoteUpdates.all { update ->
                    update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK ||
                    update.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE
                }
            } ?: false
            
            PushResult(success, if (success) "Push successful" else "Push failed")
        } catch (e: Exception) {
            e.printStackTrace()
            PushResult(false, e.message ?: "Push failed")
        }
    }
    
    fun pull(
        remoteName: String = "origin",
        branchName: String? = null,
        username: String? = null,
        password: String? = null,
        prefs: PreferencesManager? = null
    ): PullResult {
        return try {
            val pullCommand = git?.pull()
            pullCommand?.setRemote(remoteName)
            
            if (branchName != null) {
                pullCommand?.setRemoteBranchName(branchName)
            }
            
            credentialProviderFor(prefs, username, password)?.let { pullCommand?.setCredentialsProvider(it) }
            if (prefs != null) {
                GitConfigHelper.configureProxy(prefs)
                GitSshSessionFactory.install(prefs)
                pullCommand?.let { GitConfigHelper.setTimeouts(it, prefs) }
            }
            
            val result = pullCommand?.call()
            val success = result?.isSuccessful ?: false
            
            PullResult(success, if (success) "Pull successful" else "Pull failed")
        } catch (e: Exception) {
            e.printStackTrace()
            PullResult(false, e.message ?: "Pull failed")
        }
    }
    
    fun fetch(
        remoteName: String = "origin",
        username: String? = null,
        password: String? = null,
        prefs: PreferencesManager? = null
    ): FetchResult {
        return try {
            val fetchCommand = git?.fetch()
            fetchCommand?.setRemote(remoteName)
            
            credentialProviderFor(prefs, username, password)?.let { fetchCommand?.setCredentialsProvider(it) }
            if (prefs != null) {
                GitConfigHelper.configureProxy(prefs)
                GitSshSessionFactory.install(prefs)
                fetchCommand?.let { GitConfigHelper.setTimeouts(it, prefs) }
            }
            
            fetchCommand?.call()
            FetchResult(true, "Fetch successful")
        } catch (e: Exception) {
            e.printStackTrace()
            FetchResult(false, e.message ?: "Fetch failed")
        }
    }
    
    /**
     * Builds a CredentialsProvider: explicit args win, else fall back to globally
     * stored credentials so private repos work without re-entering a token. When
     * nothing is available we return null so JGit attempts anonymous access (an
     * empty Basic auth header can break public repos on strict servers).
     */
    private fun credentialProviderFor(
        prefs: PreferencesManager?,
        username: String?,
        password: String?
    ): org.eclipse.jgit.transport.CredentialsProvider? {
        if (!username.isNullOrBlank() || !password.isNullOrBlank()) {
            return org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                username ?: "",
                password ?: ""
            )
        }
        return prefs?.let { GitConfigHelper.credentialsProvider(it) }
    }
    
    fun clone(
        remoteUrl: String,
        localPath: String,
        username: String? = null,
        password: String? = null,
        prefs: PreferencesManager? = null
    ): Boolean {
        lastError = null
        val url = GitUrlUtils.normalizeCloneUrl(remoteUrl)
        if (!GitUrlUtils.isValidRemoteUrl(url)) {
            lastError = "Unsupported repository URL: $remoteUrl"
            return false
        }
        return try {
            val targetDir = File(localPath)
            if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
                cloneIntoNonEmptyDir(url, targetDir, username, password, prefs)
            } else {
                targetDir.mkdirs()
                git = buildCloneCommand(url, targetDir, username, password, prefs).call()
                repository = git?.repository
                loadGitIgnore()
                loadGitAttributes()
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /** Builds a clone command with the configured credentials/proxy/SSH. */
    private fun buildCloneCommand(
        url: String,
        directory: File,
        username: String?,
        password: String?,
        prefs: PreferencesManager?
    ): org.eclipse.jgit.api.CloneCommand {
        val cloneCommand = Git.cloneRepository()
        var effectiveUrl = url
        prefs?.let {
            effectiveUrl = GitConfigHelper.applyGitHubMirror(effectiveUrl, it)
        }
        cloneCommand.setURI(effectiveUrl)
        cloneCommand.setDirectory(directory)

        if (prefs != null) {
            GitConfigHelper.credentialsProvider(prefs)?.let { provider ->
                cloneCommand.setCredentialsProvider(provider)
            }
            GitConfigHelper.configureProxy(prefs)
            GitSshSessionFactory.install(prefs)
            GitConfigHelper.setTimeouts(cloneCommand, prefs)
        } else if (!username.isNullOrBlank() || !password.isNullOrBlank()) {
            cloneCommand.setCredentialsProvider(
                org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                    username ?: "",
                    password ?: ""
                )
            )
        }
        return cloneCommand
    }

    /**
     * Clones into a directory that already holds files (the sidebar clones into
     * the currently open project). JGit refuses a non-empty destination, so we
     * clone into a sibling temp directory, move the resulting `.git` into place
     * and copy across any files that do not yet exist, leaving the project's own
     * files untouched.
     */
    private fun cloneIntoNonEmptyDir(
        url: String,
        targetDir: File,
        username: String?,
        password: String?,
        prefs: PreferencesManager?
    ): Boolean {
        if (File(targetDir, ".git").exists()) {
            lastError = "The project already contains a Git repository"
            return false
        }
        val parent = targetDir.parentFile ?: targetDir
        val tempDir = File(parent, ".clone-${System.currentTimeMillis()}")
        if (!tempDir.mkdirs()) {
            lastError = "Cannot create a temporary clone directory"
            return false
        }
        try {
            val cloned = buildCloneCommand(url, tempDir, username, password, prefs).call()
            cloned.close()

            val tempGit = File(tempDir, ".git")
            if (!tempGit.exists()) {
                lastError = "Cloned repository has no .git directory"
                return false
            }
            if (!tempGit.renameTo(File(targetDir, ".git"))) {
                lastError = "Failed to move the cloned repository into the project"
                return false
            }

            copyMissingFiles(tempDir, targetDir)
        } finally {
            tempDir.deleteRecursively()
        }

        git = Git.open(targetDir)
        repository = git?.repository
        loadGitIgnore()
        loadGitAttributes()
        return true
    }

    /** Copies files from [from] to [to], skipping paths that already exist. */
    private fun copyMissingFiles(from: File, to: File) {
        from.listFiles()?.forEach { source ->
            val destination = File(to, source.name)
            if (source.isDirectory) {
                if (destination.mkdirs() || destination.isDirectory) {
                    copyMissingFiles(source, destination)
                }
            } else if (!destination.exists()) {
                runCatching { source.copyTo(destination, overwrite = false) }
            }
        }
    }
    
    fun hasRemote(): Boolean {
        return try {
            git?.remoteList()?.call()?.isNotEmpty() ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    fun isRepositoryInitialized(): Boolean {
        return try {
            val gitDir = File(projectPath, ".git")
            gitDir.exists() && gitDir.isDirectory
        } catch (e: Exception) {
            false
        }
    }

}

data class FileChange(
    val path: String,
    val changeType: ChangeType,
    val isStaged: Boolean = false
)

enum class ChangeType {
    UNTRACKED,
    MODIFIED,
    ADDED,
    DELETED,
    CONFLICTING
}

data class CommitInfo(
    val hash: String,
    val shortHash: String,
    val message: String,
    val author: String,
    val email: String,
    val timestamp: Long
)

data class RemoteInfo(
    val name: String,
    val fetchUrl: String,
    val pushUrl: String
)

data class PushResult(
    val success: Boolean,
    val message: String
)

data class PullResult(
    val success: Boolean,
    val message: String
)

data class FetchResult(
    val success: Boolean,
    val message: String
)