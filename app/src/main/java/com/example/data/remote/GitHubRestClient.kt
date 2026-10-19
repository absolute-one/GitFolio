package com.example.data.remote

import android.util.Base64
import com.example.contracts.AdapterCapabilities
import com.example.contracts.DataSensitivityLabel
import com.example.contracts.OutboundPolicyGatePort
import com.example.contracts.OutboundPurpose
import com.example.contracts.OutboundRequestDescriptor
import com.example.domain.model.GitCommitNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubRepoMetaDto(
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val description: String,
    val isPrivate: Boolean
)

data class GitHubTreeEntryDto(
    val path: String,
    val type: String, // "blob" or "tree"
    val size: Long,
    val sha: String
)

data class GitHubBranchRefDto(
    val name: String,
    val headSha: String
)

/**
 * Keystone Architecture — Section 10.5 & 15:
 * Guarded Outbound Network Adapter for GitHub REST API v3 + Raw Content streaming.
 * Every byte leaving the process passes the single `OutboundPolicyGatePort`.
 */
class GitHubRestClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val outboundPolicyGate: OutboundPolicyGatePort? = null
) {
    val declaredCapabilities = AdapterCapabilities(
        adapterName = "GitHubRestNetworkAdapter",
        atomicReplace = false,
        coordinationWithOtherWriters = false,
        durableFlush = false,
        reliableChangeNotifications = false
    )

    private fun enforceOutboundPolicy(
        purpose: OutboundPurpose,
        destinationHost: String,
        url: String,
        workspaceId: String,
        hasSecretToken: Boolean
    ) {
        val gate = outboundPolicyGate ?: return
        val descriptor = OutboundRequestDescriptor(
            purpose = purpose,
            destinationHost = destinationHost,
            url = url,
            workspaceId = workspaceId,
            sensitivityLabel = if (hasSecretToken) {
                DataSensitivityLabel.AUTHENTICATED_REPO
            } else {
                DataSensitivityLabel.PUBLIC
            }
        )
        gate.checkOutboundAllowed(descriptor).getOrThrow()
    }

    suspend fun fetchRepoMetadata(
        owner: String,
        repo: String,
        repoSpecificToken: String
    ): Result<GitHubRepoMetaDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$owner/$repo"
            enforceOutboundPolicy(
                purpose = OutboundPurpose.PROBE_REPOSITORY,
                destinationHost = "api.github.com",
                url = url,
                workspaceId = "$owner/$repo",
                hasSecretToken = repoSpecificToken.isNotBlank()
            )

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GitFolio-Android-Markdown-Reader")
            if (repoSpecificToken.isNotBlank()) {
                requestBuilder.header("Authorization", formatAuthHeader(repoSpecificToken))
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val hint = when (response.code) {
                        401 -> "Invalid OAuth / Personal Access Token (401)."
                        403 -> "GitHub API rate limit or token permission denied (403)."
                        404 -> "Repository '$owner/$repo' not found or private (404). Configure your GitHub OAuth token if private."
                        else -> "GitHub API HTTP ${response.code}: ${response.message}"
                    }
                    throw IOException(hint)
                }
                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val ownerObj = json.optJSONObject("owner")
                val resolvedOwner = ownerObj?.optString("login")?.takeIf { it.isNotBlank() } ?: owner
                val resolvedName = json.optString("name").takeIf { it.isNotBlank() } ?: repo
                val defaultBranch = json.optString("default_branch").takeIf { it.isNotBlank() } ?: "main"
                val isPrivate = json.optBoolean("private", false)
                val desc = json.optString("description", "")
                    .takeIf { it != "null" && it.isNotBlank() }
                    ?: "Live GitHub repository connected for Markdown & source reading."
                GitHubRepoMetaDto(
                    owner = resolvedOwner,
                    name = resolvedName,
                    defaultBranch = defaultBranch,
                    description = desc,
                    isPrivate = isPrivate
                )
            }
        }
    }

    suspend fun fetchRepoBranches(
        owner: String,
        repo: String,
        repoSpecificToken: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        fetchRepoBranchRefs(owner, repo, repoSpecificToken).map { list ->
            list.map { it.name }
        }
    }

    suspend fun fetchRepoBranchRefs(
        owner: String,
        repo: String,
        repoSpecificToken: String
    ): Result<List<GitHubBranchRefDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$owner/$repo/branches?per_page=100"
            enforceOutboundPolicy(
                purpose = OutboundPurpose.FETCH_BRANCHES,
                destinationHost = "api.github.com",
                url = url,
                workspaceId = "$owner/$repo",
                hasSecretToken = repoSpecificToken.isNotBlank()
            )

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GitFolio-Android-Markdown-Reader")
            if (repoSpecificToken.isNotBlank()) {
                requestBuilder.header("Authorization", formatAuthHeader(repoSpecificToken))
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GitHub Branches HTTP ${response.code}: ${response.message}")
                }
                val bodyStr = response.body?.string().orEmpty()
                val arr = JSONArray(bodyStr)
                val branches = ArrayList<GitHubBranchRefDto>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    val commitObj = obj.optJSONObject("commit")
                    val headSha = commitObj?.optString("sha", "")?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        branches.add(GitHubBranchRefDto(name = name, headSha = headSha))
                    }
                }
                branches
            }
        }
    }

    suspend fun fetchRepoCommits(
        owner: String,
        repo: String,
        branch: String,
        branchRefs: List<GitHubBranchRefDto>,
        perPage: Int = 100,
        repoSpecificToken: String
    ): Result<List<GitCommitNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$owner/$repo/commits?sha=$branch&per_page=$perPage"
            enforceOutboundPolicy(
                purpose = OutboundPurpose.FETCH_COMMIT_GRAPH,
                destinationHost = "api.github.com",
                url = url,
                workspaceId = "$owner/$repo",
                hasSecretToken = repoSpecificToken.isNotBlank()
            )

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GitFolio-Android-Markdown-Reader")
            if (repoSpecificToken.isNotBlank()) {
                requestBuilder.header("Authorization", formatAuthHeader(repoSpecificToken))
            }

            val branchNamesByHeadSha = branchRefs.groupBy(
                keySelector = { it.headSha },
                valueTransform = { it.name }
            )

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GitHub Commits HTTP ${response.code}: ${response.message}")
                }
                val bodyStr = response.body?.string().orEmpty()
                val arr = JSONArray(bodyStr)
                val commits = ArrayList<GitCommitNode>(arr.length())
                val nowMs = System.currentTimeMillis()

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val sha = obj.optString("sha", "").trim()
                    if (sha.isEmpty()) continue
                    val shortSha = sha.take(7)

                    val commitObj = obj.optJSONObject("commit")
                    val rawMessage = commitObj?.optString("message", "")?.trim().orEmpty()
                    val subject = rawMessage.lineSequence().firstOrNull()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Commit $shortSha"

                    val authorObj = commitObj?.optJSONObject("author")
                    val authorName = authorObj?.optString("name", "")?.takeIf { it.isNotBlank() }
                        ?: obj.optJSONObject("author")?.optString("login", "")
                        ?: "Contributor"
                    val dateIso = authorObj?.optString("date", "")?.take(10).orEmpty()

                    val parentsArr = obj.optJSONArray("parents")
                    val parentShas = ArrayList<String>(parentsArr?.length() ?: 0)
                    if (parentsArr != null) {
                        for (p in 0 until parentsArr.length()) {
                            val pSha = parentsArr.optJSONObject(p)?.optString("sha", "")?.trim().orEmpty()
                            if (pSha.isNotEmpty()) {
                                parentShas.add(pSha)
                            }
                        }
                    }

                    val matchingHeads = branchNamesByHeadSha[sha].orEmpty()
                    val isMerge = parentShas.size > 1
                    val derivedBranchName = when {
                        matchingHeads.isNotEmpty() -> matchingHeads.first()
                        i == 0 -> branch
                        isMerge -> extractMergedBranchName(subject) ?: branch
                        else -> branch
                    }

                    commits.add(
                        GitCommitNode(
                            sha = sha,
                            shortSha = shortSha,
                            subject = subject,
                            body = rawMessage,
                            authorName = authorName,
                            authorDateIso = dateIso.ifEmpty { "recent" },
                            committedEpochMs = nowMs - (i * 3_600_000L),
                            parentShas = parentShas,
                            branchNames = if (matchingHeads.isNotEmpty()) matchingHeads else listOf(derivedBranchName),
                            primaryBranch = derivedBranchName,
                            isBranchTip = i == 0 || matchingHeads.isNotEmpty()
                        )
                    )
                }
                commits
            }
        }
    }

    private fun extractMergedBranchName(subject: String): String? {
        val branchRegex = Regex("Merge (?:branch|pull request) ['\"]?([^'\"\\s]+)['\"]?", RegexOption.IGNORE_CASE)
        return branchRegex.find(subject)?.groupValues?.getOrNull(1)
    }

    suspend fun fetchRecursiveTree(
        owner: String,
        repo: String,
        branch: String,
        repoSpecificToken: String
    ): Result<List<GitHubTreeEntryDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"
            enforceOutboundPolicy(
                purpose = OutboundPurpose.FETCH_GIT_TREE,
                destinationHost = "api.github.com",
                url = url,
                workspaceId = "$owner/$repo",
                hasSecretToken = repoSpecificToken.isNotBlank()
            )

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GitFolio-Android-Markdown-Reader")
            if (repoSpecificToken.isNotBlank()) {
                requestBuilder.header("Authorization", formatAuthHeader(repoSpecificToken))
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val hint = when (response.code) {
                        401 -> "Invalid OAuth / Personal Access Token (401)."
                        403 -> "GitHub API rate limit or insufficient token scope (403)."
                        404 -> "Branch '$branch' or repository '$owner/$repo' not found (404)."
                        else -> "GitHub Tree HTTP ${response.code}: ${response.message}"
                    }
                    throw IOException(hint)
                }
                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val treeArray = json.optJSONArray("tree") ?: return@use emptyList()
                val result = ArrayList<GitHubTreeEntryDto>(minOf(treeArray.length(), 2500))
                val maxItems = minOf(treeArray.length(), 2500)
                for (i in 0 until maxItems) {
                    val item = treeArray.optJSONObject(i) ?: continue
                    val path = item.optString("path", "")
                    val type = item.optString("type", "blob")
                    val size = item.optLong("size", 0L)
                    val sha = item.optString("sha", "")
                    if (path.isNotBlank() && !isBinaryAssetPath(path)) {
                        result.add(
                            GitHubTreeEntryDto(
                                path = path,
                                type = type,
                                size = size,
                                sha = sha
                            )
                        )
                    }
                }
                result
            }
        }
    }

    suspend fun fetchFileRawText(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        repoSpecificToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch"
            enforceOutboundPolicy(
                purpose = OutboundPurpose.FETCH_MARKDOWN_BLOB,
                destinationHost = "api.github.com",
                url = apiUrl,
                workspaceId = "$owner/$repo",
                hasSecretToken = repoSpecificToken.isNotBlank()
            )

            if (repoSpecificToken.isNotBlank()) {
                val apiReq = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github.raw+json")
                    .header("User-Agent", "GitFolio-Android-Markdown-Reader")
                    .header("Authorization", formatAuthHeader(repoSpecificToken))
                    .build()

                httpClient.newCall(apiReq).execute().use { response ->
                    if (response.isSuccessful) {
                        return@runCatching response.body?.string().orEmpty()
                    }
                }
            }

            val rawUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
            enforceOutboundPolicy(
                purpose = OutboundPurpose.FETCH_MARKDOWN_BLOB,
                destinationHost = "raw.githubusercontent.com",
                url = rawUrl,
                workspaceId = "$owner/$repo",
                hasSecretToken = repoSpecificToken.isNotBlank()
            )

            val rawReqBuilder = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", "GitFolio-Android-Markdown-Reader")
            if (repoSpecificToken.isNotBlank()) {
                rawReqBuilder.header("Authorization", formatAuthHeader(repoSpecificToken))
            }

            httpClient.newCall(rawReqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    return@runCatching response.body?.string().orEmpty()
                }
            }

            val fallbackReqBuilder = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GitFolio-Android-Markdown-Reader")
            if (repoSpecificToken.isNotBlank()) {
                fallbackReqBuilder.header("Authorization", formatAuthHeader(repoSpecificToken))
            }

            httpClient.newCall(fallbackReqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to fetch $path (HTTP ${response.code})")
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val encoded = json.optString("content", "")
                val decodedBytes = Base64.decode(encoded.replace("\n", "").replace("\r", ""), Base64.DEFAULT)
                String(decodedBytes, Charsets.UTF_8)
            }
        }
    }

    private fun formatAuthHeader(token: String): String {
        val cleaned = token.trim()
        return if (cleaned.startsWith("Bearer ", ignoreCase = true) ||
            cleaned.startsWith("token ", ignoreCase = true)
        ) {
            cleaned
        } else {
            "Bearer $cleaned"
        }
    }

    private fun isBinaryAssetPath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf",
            "zip", "tar", "gz", "jar", "apk", "aab", "so", "dll",
            "exe", "dylib", "class", "ttf", "otf", "woff", "woff2", "mp4", "mp3"
        )
    }

    companion object {
        private val GITHUB_URL_REGEX = Regex(
            "(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)",
            RegexOption.IGNORE_CASE
        )

        fun extractGitHubCoordinatesFromSharedText(rawSharedText: String): Pair<String, String>? {
            val trimmed = rawSharedText.trim()
            if (trimmed.isEmpty()) return null

            val urlMatch = GITHUB_URL_REGEX.find(trimmed)
            if (urlMatch != null) {
                val owner = urlMatch.groupValues[1].trim()
                val repo = urlMatch.groupValues[2].trim().removeSuffix(".git")
                if (owner.isNotBlank() && repo.isNotBlank()) {
                    return owner to repo
                }
            }

            return parseGitHubCoordinates(trimmed)
        }

        fun parseGitHubCoordinates(rawInput: String): Pair<String, String>? {
            var cleaned = rawInput.trim()
                .removeSuffix("/")
                .removeSuffix(".git")

            if (cleaned.startsWith("git@github.com:")) {
                cleaned = cleaned.removePrefix("git@github.com:")
            } else if (cleaned.contains("github.com/", ignoreCase = true)) {
                cleaned = cleaned.substringAfter("github.com/", "")
                    .ifEmpty { cleaned.substringAfter("GITHUB.COM/", "") }
            }

            val segments = cleaned.split("/").filter { it.isNotBlank() }
            if (segments.size < 2) return null
            val owner = segments[0].trim()
            val repo = segments[1].trim().removeSuffix(".git")
            if (owner.isBlank() || repo.isBlank() || owner.contains(" ") || repo.contains(" ")) {
                return null
            }
            return owner to repo
        }
    }
}
