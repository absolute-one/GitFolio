package com.example.data.remote

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubRepoMetaDto(
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val description: String
)

data class GitHubTreeEntryDto(
    val path: String,
    val type: String, // "blob" or "tree"
    val size: Long,
    val sha: String
)

/**
 * Clean REST client for GitHub API v3 + Raw Content streaming.
 * Attaches the repository-specific OAuth / Personal Access Token per request.
 */
class GitHubRestClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    suspend fun fetchRepoMetadata(
        owner: String,
        repo: String,
        repoSpecificToken: String
    ): Result<GitHubRepoMetaDto> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBuilder = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo")
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
                        404 -> "Repository '$owner/$repo' not found or private (404)."
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
                val desc = json.optString("description", "")
                    .takeIf { it != "null" && it.isNotBlank() }
                    ?: "Live GitHub repository connected for Markdown & source reading."
                GitHubRepoMetaDto(
                    owner = resolvedOwner,
                    name = resolvedName,
                    defaultBranch = defaultBranch,
                    description = desc
                )
            }
        }
    }

    suspend fun fetchRecursiveTree(
        owner: String,
        repo: String,
        branch: String,
        repoSpecificToken: String
    ): Result<List<GitHubTreeEntryDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBuilder = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1")
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
            // 1. Try GitHub API contents endpoint first if token is provided (supports private repos)
            if (repoSpecificToken.isNotBlank()) {
                val apiReq = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch")
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

            // 2. Try raw.githubusercontent.com for fast direct text streaming
            val rawUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
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

            // 3. Fallback to JSON contents endpoint (Base64 encoded)
            val fallbackReqBuilder = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch")
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

        /**
         * Extracts a valid "owner/repo" slug from arbitrary shared text or URLs from 3rd-party apps.
         */
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

        /**
         * Parses user input such as:
         * - "commonmark/commonmark-spec"
         * - "https://github.com/JetBrains/kotlin"
         * - "https://github.com/rust-lang/mdBook/tree/master"
         * - "git@github.com:torvalds/linux.git"
         */
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
