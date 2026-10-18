package com.example.data.engine

import com.example.domain.model.GitCommitNode
import com.example.domain.model.GitGraphCommitRow
import com.example.domain.model.GitRepoCommitGraph
import com.example.domain.model.GitRowGraphSegment
import com.example.domain.model.GraphSegmentRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * High-Performance O(V * L) Topological Git Commit DAG Layout Engine.
 *
 * Includes zombie-lane garbage collection and bounded lane packing so that
 * even across 1,000+ commits and 100+ branches, the active visual lanes stay
 * compact (5-6 concurrent lanes max), leaving generous horizontal room for
 * commit messages without ever squeezing or overlapping UI text.
 */
class GitGraphLayoutEngine {

    suspend fun computeCommitGraphLayout(
        repoId: String,
        repoSlug: String,
        activeBranch: String,
        commits: List<GitCommitNode>,
        totalBranchesCount: Int,
        isHighScaleBenchmarkMode: Boolean,
        isLiveFromGitHub: Boolean
    ): GitRepoCommitGraph = withContext(Dispatchers.Default) {
        if (commits.isEmpty()) {
            return@withContext GitRepoCommitGraph(
                repoId = repoId,
                repoSlug = repoSlug,
                activeBranch = activeBranch,
                totalCommits = 0,
                totalBranchesRepresented = totalBranchesCount,
                maxConcurrentLanes = 1,
                rows = emptyList(),
                isHighScaleBenchmarkMode = isHighScaleBenchmarkMode,
                isLiveFromGitHub = isLiveFromGitHub
            )
        }

        // Map every commit SHA to its row index so we never keep zombie lanes open
        // for parent SHAs outside the loaded commit window.
        val rowIndexBySha = HashMap<String, Int>(commits.size * 2)
        for ((idx, c) in commits.withIndex()) {
            rowIndexBySha[c.sha] = idx
        }

        val activeTargetShas = ArrayList<String?>(MAX_VISUAL_LANES)
        val activeLaneColors = ArrayList<Int>(MAX_VISUAL_LANES)
        val rows = ArrayList<GitGraphCommitRow>(commits.size)
        var maxConcurrentLanes = 1
        var nextColorSeed = 0

        fun allocateLane(targetSha: String, preferredColor: Int): Int {
            val freeIdx = activeTargetShas.indexOfFirst { it == null }
            return if (freeIdx >= 0) {
                activeTargetShas[freeIdx] = targetSha
                activeLaneColors[freeIdx] = preferredColor
                freeIdx
            } else if (activeTargetShas.size < MAX_VISUAL_LANES) {
                val newIdx = activeTargetShas.size
                activeTargetShas.add(targetSha)
                activeLaneColors.add(preferredColor)
                newIdx
            } else {
                // Recycle the rightmost lane if MAX_VISUAL_LANES is reached
                val recycledIdx = MAX_VISUAL_LANES - 1
                activeTargetShas[recycledIdx] = targetSha
                activeLaneColors[recycledIdx] = preferredColor
                recycledIdx
            }
        }

        fun trimAndCollectStaleLanes(currentRowIndex: Int) {
            for (i in activeTargetShas.indices) {
                val targetSha = activeTargetShas[i] ?: continue
                val targetRow = rowIndexBySha[targetSha]
                // Free any lane whose target commit isn't below the current row
                // or is too far away (> 18 rows) to prevent long-lived rail clutter
                if (targetRow == null || targetRow <= currentRowIndex || (targetRow - currentRowIndex) > 18) {
                    activeTargetShas[i] = null
                }
            }
            while (activeTargetShas.isNotEmpty() && activeTargetShas.last() == null) {
                activeTargetShas.removeAt(activeTargetShas.lastIndex)
                activeLaneColors.removeAt(activeLaneColors.lastIndex)
            }
        }

        for ((rowIndex, commit) in commits.withIndex()) {
            val segments = ArrayList<GitRowGraphSegment>(8)

            // 1. Identify which incoming lanes from the row above target this commit's SHA
            val matchingIncomingLanes = ArrayList<Int>(4)
            for (laneIdx in activeTargetShas.indices) {
                if (activeTargetShas[laneIdx] == commit.sha) {
                    matchingIncomingLanes.add(laneIdx)
                }
            }

            // 2. Determine this commit's lane and color
            val commitLane: Int
            val commitColor: Int

            if (matchingIncomingLanes.isNotEmpty()) {
                commitLane = matchingIncomingLanes.first()
                commitColor = branchColorForCommit(commit, activeLaneColors[commitLane])
            } else {
                val assignedColor = branchColorForCommit(commit, nextColorSeed++)
                commitLane = allocateLane(commit.sha, assignedColor)
                commitColor = assignedColor
            }

            // 3. All other active lanes pass straight through this row from top to bottom
            for (laneIdx in activeTargetShas.indices) {
                val shaInLane = activeTargetShas[laneIdx] ?: continue
                if (shaInLane != commit.sha && laneIdx != commitLane) {
                    segments.add(
                        GitRowGraphSegment(
                            fromLane = laneIdx,
                            toLane = laneIdx,
                            colorIndex = activeLaneColors[laneIdx],
                            role = GraphSegmentRole.FULL_PASS_THROUGH
                        )
                    )
                }
            }

            // 4. Connect incoming lanes that converged on this commit (from child commits above)
            if (matchingIncomingLanes.isNotEmpty()) {
                for (incomingLane in matchingIncomingLanes) {
                    val incomingColor = activeLaneColors[incomingLane]
                    if (incomingLane == commitLane) {
                        segments.add(
                            GitRowGraphSegment(
                                fromLane = commitLane,
                                toLane = commitLane,
                                colorIndex = incomingColor,
                                role = GraphSegmentRole.TOP_TO_CENTER
                            )
                        )
                    } else {
                        segments.add(
                            GitRowGraphSegment(
                                fromLane = incomingLane,
                                toLane = commitLane,
                                colorIndex = incomingColor,
                                role = GraphSegmentRole.TOP_TO_CENTER_CURVE
                            )
                        )
                        activeTargetShas[incomingLane] = null
                    }
                }
            }

            // 5. Filter parents to those actually present below this row in the loaded list
            val validParents = commit.parentShas.filter { parentSha ->
                val pIdx = rowIndexBySha[parentSha]
                pIdx != null && pIdx > rowIndex
            }

            if (validParents.isEmpty()) {
                // If this isn't the very last row and there's a next row, connect trunk smoothly
                if (rowIndex < commits.lastIndex && commitLane == 0) {
                    val nextSha = commits[rowIndex + 1].sha
                    activeTargetShas[commitLane] = nextSha
                    activeLaneColors[commitLane] = commitColor
                    segments.add(
                        GitRowGraphSegment(
                            fromLane = commitLane,
                            toLane = commitLane,
                            colorIndex = commitColor,
                            role = GraphSegmentRole.CENTER_TO_BOTTOM
                        )
                    )
                } else {
                    activeTargetShas[commitLane] = null
                }
            } else {
                val firstParentSha = validParents[0]
                val existingFirstParentLane = activeTargetShas.indexOfFirst {
                    it == firstParentSha
                }.let { idx -> if (idx == commitLane) -1 else idx }

                if (existingFirstParentLane >= 0) {
                    segments.add(
                        GitRowGraphSegment(
                            fromLane = commitLane,
                            toLane = existingFirstParentLane,
                            colorIndex = commitColor,
                            role = GraphSegmentRole.CENTER_TO_BOTTOM_CURVE
                        )
                    )
                    activeTargetShas[commitLane] = null
                } else {
                    activeTargetShas[commitLane] = firstParentSha
                    activeLaneColors[commitLane] = commitColor
                    segments.add(
                        GitRowGraphSegment(
                            fromLane = commitLane,
                            toLane = commitLane,
                            colorIndex = commitColor,
                            role = GraphSegmentRole.CENTER_TO_BOTTOM
                        )
                    )
                }

                // Additional merge parents
                for (pIdx in 1 until validParents.size) {
                    val mergeParentSha = validParents[pIdx]
                    val existingMergeLane = activeTargetShas.indexOfFirst { it == mergeParentSha }
                    if (existingMergeLane >= 0) {
                        segments.add(
                            GitRowGraphSegment(
                                fromLane = commitLane,
                                toLane = existingMergeLane,
                                colorIndex = activeLaneColors[existingMergeLane],
                                role = GraphSegmentRole.CENTER_TO_BOTTOM_CURVE
                            )
                        )
                    } else {
                        val sideColor = (commitColor + pIdx + 1) % TOTAL_LANE_COLORS
                        val newMergeLane = allocateLane(mergeParentSha, sideColor)
                        segments.add(
                            GitRowGraphSegment(
                                fromLane = commitLane,
                                toLane = newMergeLane,
                                colorIndex = sideColor,
                                role = GraphSegmentRole.CENTER_TO_BOTTOM_CURVE
                            )
                        )
                    }
                }
            }

            trimAndCollectStaleLanes(rowIndex)

            val highestSegmentLane = segments.maxOfOrNull { max(it.fromLane, it.toLane) } ?: commitLane
            val rowLaneCount = max(max(activeTargetShas.size, commitLane + 1), highestSegmentLane + 1)
                .coerceAtMost(MAX_VISUAL_LANES)
            maxConcurrentLanes = max(maxConcurrentLanes, rowLaneCount)

            val badgeText = when {
                commit.branchNames.isNotEmpty() -> commit.branchNames.first()
                commit.primaryBranch.isNotBlank() -> commit.primaryBranch
                else -> commit.shortSha
            }

            rows.add(
                GitGraphCommitRow(
                    rowIndex = rowIndex,
                    commit = commit,
                    commitLane = commitLane.coerceAtMost(MAX_VISUAL_LANES - 1),
                    commitColorIndex = commitColor,
                    badgeLabel = badgeText,
                    isHollowHeadNode = rowIndex == 0 || commit.isBranchTip,
                    segments = segments,
                    activeLaneCountInRow = rowLaneCount
                )
            )
        }

        GitRepoCommitGraph(
            repoId = repoId,
            repoSlug = repoSlug,
            activeBranch = activeBranch,
            totalCommits = rows.size,
            totalBranchesRepresented = max(totalBranchesCount, countDistinctBranches(commits)),
            maxConcurrentLanes = maxConcurrentLanes.coerceIn(2, MAX_VISUAL_LANES),
            rows = rows,
            isHighScaleBenchmarkMode = isHighScaleBenchmarkMode,
            isLiveFromGitHub = isLiveFromGitHub
        )
    }

    private fun countDistinctBranches(commits: List<GitCommitNode>): Int {
        val set = HashSet<String>()
        for (c in commits) {
            if (c.primaryBranch.isNotBlank()) set.add(c.primaryBranch)
            set.addAll(c.branchNames)
        }
        return set.size.coerceAtLeast(1)
    }

    private fun branchColorForCommit(commit: GitCommitNode, fallbackIndex: Int): Int {
        val branch = commit.primaryBranch.lowercase()
        return when {
            branch == "main" || branch == "master" -> 0 // Azure Blue (Lane 0)
            branch == "next" || branch.startsWith("release") -> 1 // Hot Magenta (Lane 1)
            branch == "seen" || branch.startsWith("feat") -> 2 // Neon Green (Lane 2)
            branch == "maint" || branch.startsWith("fix") || branch.startsWith("hotfix") -> 3 // Warm Amber (Lane 3)
            branch == "todo" || branch.startsWith("perf") || branch.startsWith("refactor") -> 4 // Electric Purple (Lane 4)
            else -> abs(fallbackIndex) % TOTAL_LANE_COLORS
        }
    }

    /**
     * Generates a high-scale 1,050-commit / 112-branch topological Git DAG
     * where every branch cleanly forks and merges within 2-5 lanes so the visual graph
     * stays crisp, structured, and never overflows horizontally.
     */
    fun generateHighScaleMultiBranchDag(
        repoSlug: String,
        defaultBranch: String,
        targetCommitCount: Int = 1050,
        targetBranchCount: Int = 112
    ): List<GitCommitNode> {
        val result = ArrayList<GitCommitNode>(targetCommitCount)
        val nowMs = System.currentTimeMillis()
        val baseTrunkShaPrefix = "c000"

        // Top 13 commits (M down to A) crafted with exact parent pointers to form the
        // multi-lane parallel S-curve topology shown in the user's reference image:
        val showcaseSpecs = listOf(
            ShowcaseSpec(
                code = "M",
                sha = "m000012",
                parents = listOf("c000002"),
                branch = defaultBranch,
                colorHintBranch = "main",
                isTip = true,
                subject = "Merge tag 'v2.47.1' into $defaultBranch head trunk",
                author = "Junio C Hamano"
            ),
            ShowcaseSpec(
                code = "L",
                sha = "l000011",
                parents = listOf("k000010", "h000007", "j000009"),
                branch = "next",
                colorHintBranch = "next",
                isTip = true,
                subject = "Integrate 'seen' and 'maint' topic branches into next",
                author = "Junio C Hamano"
            ),
            ShowcaseSpec(
                code = "K",
                sha = "k000010",
                parents = listOf("f000005"),
                branch = "next",
                colorHintBranch = "next",
                isTip = false,
                subject = "revision.c: optimize topological commit walk lane packing",
                author = "Patrick Steinhardt"
            ),
            ShowcaseSpec(
                code = "J",
                sha = "j000009",
                parents = listOf("i000008", "g000006"),
                branch = "maint",
                colorHintBranch = "maint",
                isTip = false,
                subject = "Merge branch 'ps/reftable-compaction' into maint",
                author = "Patrick Steinhardt"
            ),
            ShowcaseSpec(
                code = "I",
                sha = "i000008",
                parents = listOf("c000002"),
                branch = "maint",
                colorHintBranch = "maint",
                isTip = false,
                subject = "commit-graph: verify bloom filter chunk alignment",
                author = "Taylor Blau"
            ),
            ShowcaseSpec(
                code = "H",
                sha = "h000007",
                parents = listOf("e000004"),
                branch = "seen",
                colorHintBranch = "seen",
                isTip = false,
                subject = "pack-bitmap: accelerate reachability bitmap traversal",
                author = "Jeff King"
            ),
            ShowcaseSpec(
                code = "G",
                sha = "g000006",
                parents = listOf("f000005"),
                branch = "todo",
                colorHintBranch = "todo",
                isTip = false,
                subject = "Documentation/git-log: document graph S-curve rendering",
                author = "brian m. carlson"
            ),
            ShowcaseSpec(
                code = "F",
                sha = "f000005",
                parents = listOf("c000002", "e000004"),
                branch = "next",
                colorHintBranch = "next",
                isTip = false,
                subject = "Merge branch 'jk/pack-bitmap-optim' into next",
                author = "Junio C Hamano"
            ),
            ShowcaseSpec(
                code = "E",
                sha = "e000004",
                parents = listOf("d000003"),
                branch = "seen",
                colorHintBranch = "seen",
                isTip = false,
                subject = "refs: introduce zero-copy reference transaction iterator",
                author = "Karthik Nayak"
            ),
            ShowcaseSpec(
                code = "D",
                sha = "d000003",
                parents = listOf("b000001"),
                branch = "seen",
                colorHintBranch = "seen",
                isTip = false,
                subject = "sha256: streamline loose object header parsing",
                author = "brian m. carlson"
            ),
            ShowcaseSpec(
                code = "C",
                sha = "c000002",
                parents = listOf("b000001"),
                branch = defaultBranch,
                colorHintBranch = "main",
                isTip = false,
                subject = "Git 2.47 release notes and documentation tree sync",
                author = "Junio C Hamano"
            ),
            ShowcaseSpec(
                code = "B",
                sha = "b000001",
                parents = listOf("a000000"),
                branch = defaultBranch,
                colorHintBranch = "main",
                isTip = false,
                subject = "tree-walk.c: fast-path hierarchical directory traversal",
                author = "Elijah Newren"
            ),
            ShowcaseSpec(
                code = "A",
                sha = "a000000",
                parents = listOf("${baseTrunkShaPrefix}_13"),
                branch = defaultBranch,
                colorHintBranch = "main",
                isTip = false,
                subject = "Base multi-branch convergence checkpoint ($repoSlug)",
                author = "Linus Torvalds"
            )
        )

        showcaseSpecs.forEachIndexed { idx, spec ->
            result.add(
                GitCommitNode(
                    sha = spec.sha,
                    shortSha = spec.code,
                    subject = spec.subject,
                    body = "Repository: $repoSlug\nBranch: ${spec.branch}\nCommit SHA: ${spec.sha}\nParents: ${spec.parents.joinToString(", ")}",
                    authorName = spec.author,
                    authorDateIso = "2026-10-18",
                    committedEpochMs = nowMs - (idx * 3_600_000L),
                    parentShas = spec.parents,
                    branchNames = listOf(spec.branch),
                    primaryBranch = spec.colorHintBranch,
                    isBranchTip = spec.isTip
                )
            )
        }

        val subsystems = listOf(
            "commit-graph", "reftable", "pack-objects", "revision", "diffcore",
            "fsmonitor", "sparse-checkout", "scalar", "worktree", "submodule",
            "upload-pack", "fetch-pack", "index-pack", "bloom-filter", "doc-tree"
        )
        val authors = listOf(
            "Junio C Hamano", "Patrick Steinhardt", "Taylor Blau", "Jeff King",
            "Elijah Newren", "brian m. carlson", "Johannes Schindelin", "Derrick Stolee"
        )
        val colorFamilies = listOf("next", "seen", "maint", "todo", "feat/graph", "fix/tree", "perf/idx")

        val remainingCount = (targetCommitCount - result.size).coerceAtLeast(0)
        // Repeat a clean 6-commit cycle:
        // Pos 0: Trunk merge commit (parents: Pos 3 trunk + Pos 1 topic branch)
        // Pos 1: Topic branch A commit (parent: Pos 2 topic branch A)
        // Pos 2: Topic branch A base (parent: Pos 5 trunk base, plus merge from Pos 4 topic B)
        // Pos 3: Trunk commit (parent: Pos 5 trunk base)
        // Pos 4: Topic branch B commit (parent: Pos 5 trunk base)
        // Pos 5: Trunk convergence commit (parent: Pos 6 next cycle start)
        for (i in 0 until remainingCount) {
            val globalIdx = i + 13
            val maxGlobalIdx = 13 + remainingCount - 1
            val currentSha = "${baseTrunkShaPrefix}_$globalIdx"
            val cyclePos = i % 6
            val cycleNum = i / 6

            val branchNumber = ((cycleNum * 2 + (cyclePos % 2)) % targetBranchCount) + 1
            val subsystem = subsystems[(cycleNum + cyclePos) % subsystems.size]
            val author = authors[(i + cycleNum) % authors.size]

            fun shaAt(targetGlobal: Int): String =
                "${baseTrunkShaPrefix}_${targetGlobal.coerceAtMost(maxGlobalIdx)}"

            val isTrunk = (cyclePos == 0 || cyclePos == 3 || cyclePos == 5)
            val branchLabel = if (isTrunk) {
                defaultBranch
            } else {
                "topic/b$branchNumber-$subsystem"
            }

            val colorHint = if (isTrunk) {
                "main"
            } else {
                colorFamilies[(branchNumber + cyclePos) % colorFamilies.size]
            }

            val parents: List<String> = if (globalIdx >= maxGlobalIdx) {
                emptyList()
            } else {
                when (cyclePos) {
                    0 -> listOfNotNull(
                        shaAt(globalIdx + 3),
                        if (globalIdx + 1 <= maxGlobalIdx) shaAt(globalIdx + 1) else null
                    ).distinct()
                    1 -> listOf(shaAt(globalIdx + 1))
                    2 -> listOfNotNull(
                        shaAt(globalIdx + 3),
                        if (globalIdx + 2 <= maxGlobalIdx) shaAt(globalIdx + 2) else null
                    ).distinct()
                    3 -> listOf(shaAt(globalIdx + 2))
                    4 -> listOf(shaAt(globalIdx + 1))
                    else -> listOf(shaAt(globalIdx + 1))
                }
            }

            val shortCode = String.format("%07x", (0x8a10000 + globalIdx))
            val subject = when (cyclePos) {
                0 -> "Merge branch '$branchLabel' into $defaultBranch"
                1 -> "$subsystem: refine DAG traversal batch #$globalIdx"
                2 -> "$subsystem: integrate topic branch #$branchNumber checkpoints"
                3 -> "$subsystem: update $defaultBranch index table #$globalIdx"
                4 -> "$subsystem: optimize branch #$branchNumber lookup cache"
                else -> "$subsystem: release checkpoint #$globalIdx on $defaultBranch"
            }

            result.add(
                GitCommitNode(
                    sha = currentSha,
                    shortSha = shortCode,
                    subject = subject,
                    body = "Commit $shortCode on branch $branchLabel ($repoSlug).\nAuthor: $author\nParents: ${parents.joinToString(", ")}",
                    authorName = author,
                    authorDateIso = "2026-10-${18 - (i % 17)}",
                    committedEpochMs = nowMs - (globalIdx * 3_600_000L),
                    parentShas = parents,
                    branchNames = listOf(branchLabel),
                    primaryBranch = colorHint,
                    isBranchTip = false
                )
            )
        }

        return result
    }

    private data class ShowcaseSpec(
        val code: String,
        val sha: String,
        val parents: List<String>,
        val branch: String,
        val colorHintBranch: String,
        val isTip: Boolean,
        val subject: String,
        val author: String
    )

    companion object {
        const val TOTAL_LANE_COLORS = 8
        const val MAX_VISUAL_LANES = 5
    }
}
