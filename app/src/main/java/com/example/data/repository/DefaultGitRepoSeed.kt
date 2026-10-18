package com.example.data.repository

import com.example.data.local.CachedDocumentEntity
import com.example.data.local.RepoTreeNodeEntity
import com.example.data.local.RepositoryEntity
import com.example.domain.model.BentoTileStyle
import com.example.domain.model.GitHostProvider

/**
 * Provides the one-time initial welcoming gesture repository: "git/git"
 * (The official Git Source Code Mirror on GitHub).
 *
 * This is only seeded once on initial install; if the user removes it,
 * their repository shelf remains completely empty.
 */
object DefaultGitRepoSeed {

    const val GIT_REPO_ID = "git/git"

    fun createGitRepositoryEntity(): RepositoryEntity {
        val branches = listOf("master", "main", "next", "seen", "maint", "todo")
        val nodes = createGitTreeNodes()
        val files = nodes.filter { !it.isDirectory }
        val mdFiles = files.count {
            val lower = it.name.lowercase()
            lower.endsWith(".md") || lower.endsWith(".txt") || lower.startsWith("readme")
        }

        return RepositoryEntity(
            id = GIT_REPO_ID,
            providerId = GitHostProvider.GITHUB.providerId,
            owner = "git",
            name = "git",
            defaultBranch = "master",
            branchesCsv = branches.joinToString(","),
            description = "Git Source Code Mirror — Fast, scalable, distributed revision control system.",
            totalFilesCount = files.size,
            markdownFilesCount = mdFiles,
            lastSyncedEpochMs = System.currentTimeMillis(),
            tileStyleName = BentoTileStyle.HERO_WIDE.name,
            accentIndex = 0
        )
    }

    fun createGitTreeNodes(): List<RepoTreeNodeEntity> {
        val rawPaths = listOf(
            Triple("README.md", false, 4820L),
            Triple("COPYING", false, 18420L),
            Triple("CodingGuidelines", false, 15240L),
            Triple("SubmittingPatches", false, 22480L),
            Triple("Documentation", true, 0L),
            Triple("Documentation/git.md", false, 12840L),
            Triple("Documentation/gittutorial.md", false, 14920L),
            Triple("Documentation/git-commit-graph.md", false, 9840L),
            Triple("Documentation/RelNotes", true, 0L),
            Triple("Documentation/RelNotes/2.47.0.md", false, 19400L),
            Triple("git.c", false, 26100L),
            Triple("commit.c", false, 42800L),
            Triple("revision.c", false, 84300L),
            Triple("tree-walk.c", false, 31900L)
        )

        return rawPaths.mapIndexed { idx, (path, isDir, size) ->
            val segments = path.split("/")
            val name = segments.last()
            val parent = if (segments.size > 1) segments.dropLast(1).joinToString("/") else ""
            RepoTreeNodeEntity(
                id = "$GIT_REPO_ID:$path",
                repoId = GIT_REPO_ID,
                path = path,
                name = name,
                parentPath = parent,
                isDirectory = isDir,
                sizeBytes = size,
                sha = "git-seed-sha-$idx",
                depth = segments.size - 1
            )
        }
    }

    fun createGitInitialDocuments(): List<CachedDocumentEntity> {
        val now = System.currentTimeMillis()
        val docs = mapOf(
            "README.md" to """
                # Git - Fast, Scalable, Distributed Revision Control

                [![Git Source Mirror](https://github.com/git/git)](https://github.com/git/git)

                Git is a fast, scalable, distributed revision control system with an unusually rich command set that provides both high-level operations and full access to internals.

                > [!NOTE]
                > Welcome to **GitFolio**! `git/git` is preloaded as a welcoming gesture. You can explore its **Files Tree** or switch to the **Git Graph** tab above to inspect 1,000+ commits across 100+ branches—or swipe left on the home screen tile to remove it at any time.

                ## Architectural Highlights

                | Subsystem | Source File | Responsibility |
                | :--- | :--- | ---: |
                | Command Dispatcher | `git.c` | Built-in command table & environment setup |
                | Commit DAG Engine | `commit.c` | Parent-child commit graph traversal |
                | Revision Walker | `revision.c` | Topological sorting & lane filtering |
                | Tree Walker | `tree-walk.c` | Hierarchical tree object parsing |

                ## Building & Reading Documentation

                - Read `Documentation/gittutorial.md` to get started with core Git concepts.
                - Read `Documentation/git-commit-graph.md` for details on how Git accelerates commit graph walks.
                - Inspect `COPYING` for the GNU General Public License v2.0 terms.

                ```c
                int cmd_main(int argc, const char **argv) {
                    const char *cmd = argv[0] ? argv[0] : "git-help";
                    return run_builtin(cmd, argc, argv);
                }
                ```
            """.trimIndent(),
            "COPYING" to """
                # GNU GENERAL PUBLIC LICENSE (Version 2)

                Note that the only valid version of the GPL as far as this project is concerned is _this_ particular version of the license (ie v2, not v2.2 or v3.x or whatever), unless explicitly otherwise stated.

                ```text
                Copyright (C) 2005-2026 Linus Torvalds and the Git Contributors.
                Everyone is permitted to copy and distribute verbatim copies
                of this license document, but changing it is not allowed.
                ```
            """.trimIndent(),
            "Documentation/git-commit-graph.md" to """
                # git-commit-graph(1) Manual Page

                ## NAME
                `git-commit-graph` - Write and verify Git commit-graph files for high-speed topological DAG walks.

                ## SYNOPSIS
                ```bash
                git commit-graph verify [--object-dir <dir>] [--shallow]
                git commit-graph write [--object-dir <dir>] [--append] [--split]
                ```

                ## PERFORMANCE AT SCALE (1,000+ COMMITS / 100+ BRANCHES)
                By storing generation numbers and parent indices in a compact binary table, Git avoids decompressing loose commit objects during `git log --graph` traversal.
            """.trimIndent()
        )

        return docs.entries.mapIndexed { idx, (path, content) ->
            val fileName = path.substringAfterLast('/')
            CachedDocumentEntity(
                id = "$GIT_REPO_ID:$path",
                repoId = GIT_REPO_ID,
                path = path,
                fileName = fileName,
                rawContent = content,
                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                sha = "git-seed-sha-$idx",
                cachedAtEpochMs = now
            )
        }
    }
}
