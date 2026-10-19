package com.example

import com.example.backend.Warden
import com.example.contracts.ActorKind
import com.example.contracts.Capability
import com.example.contracts.CatalogueValidator
import com.example.contracts.CommandCatalogue
import com.example.contracts.CommandDescriptor
import com.example.contracts.EnvelopeKind
import com.example.contracts.ErrorCode
import com.example.contracts.KeystoneArchitectureAuditor
import com.example.contracts.Lane
import com.example.contracts.OutboundPurpose
import com.example.contracts.OutboundRequestDescriptor
import com.example.contracts.ProfileCatalogue
import com.example.contracts.ResourceRef
import com.example.contracts.SlotCatalogue
import com.example.contracts.SlotContributionSpec
import com.example.contracts.TrustTier
import com.example.data.engine.CommonMarkDocumentEngine
import com.example.data.engine.GitGraphLayoutEngine
import com.example.data.local.AuditChainDao
import com.example.data.local.AuditChainEntity
import com.example.data.remote.GitHubRestClient
import com.example.domain.model.GraphSegmentRole
import com.example.domain.model.MarkdownAstBlock
import com.example.domain.model.RawDocumentPayload
import com.example.domain.model.RepoFileNode
import com.example.domain.usecase.ComputeVisibleTreeNodesUseCase
import com.example.kernel.KeystoneKernel
import com.example.kernel.PluginHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun keystoneArchitectureAuditor_verifiesAllTenLawsAndCatalogues() = runTest {
        val report = CatalogueValidator.validateAll()
        assertTrue("Catalogue violations: ${report.violations}", report.isValid)
        assertTrue(report.commandCount >= 20)
        assertTrue(report.slotCount >= 6)
        assertTrue(report.capabilityCount >= 6)

        val kernel = KeystoneKernel(parentScope = backgroundScope)
        val pluginHost = PluginHost(kernel = kernel)

        val auditReport = KeystoneArchitectureAuditor.runAudit(
            kernel = kernel,
            pluginHost = pluginHost,
            adapterCapabilities = listOf(GitHubRestClient().declaredCapabilities),
            auditChainIntact = true,
            journalCount = 4,
            auditEntryCount = 6
        )
        assertTrue("Audit report failed checks: ${auditReport.checks.filterNot { it.passed }}", auditReport.passedAll)
        assertEquals(12, auditReport.passedCount)
        assertEquals(12, auditReport.totalCount)
    }

    @Test
    fun keystoneKernel_stampsCallContext_deduplicatesRedactsAndPassesByReference() = runTest {
        val kernel = KeystoneKernel(parentScope = backgroundScope)
        kernel.registerSender(
            senderId = "crown.arbiter",
            actorKind = ActorKind.USER,
            trustTier = TrustTier.CORE,
            scopeId = "scope:crown"
        )

        var receivedByFoundation = 0
        kernel.bindFoundationArbiterHandler { incoming ->
            receivedByFoundation++
            // Law 5: Keystone-stamped context is USER, regardless of what payload claims
            assertEquals(ActorKind.USER, incoming.context.actorKind)
            assertEquals("crown.arbiter", incoming.context.actorId)
            null
        }

        val reply = kernel.dispatchToFoundation(
            senderId = "crown.arbiter",
            topic = CommandCatalogue.CREDENTIAL_SAVE,
            payload = mapOf(
                "providerId" to "github",
                "token" to "ghp_super_secret_token_value",
                "actorKind" to "SYSTEM_SPOOF_ATTEMPT"
            )
        )
        assertEquals(EnvelopeKind.REPLY, reply.kind)
        assertEquals(1, receivedByFoundation)

        // Verify trace log redacts secret token values (Section 16)
        val traces = kernel.traceLog.value
        assertTrue(traces.isNotEmpty())
        val commandTrace = traces.first { it.kind == EnvelopeKind.COMMAND }
        assertTrue(commandTrace.redactedPayloadKeys.any { it == "token=[REDACTED]" })
        assertFalse(commandTrace.redactedPayloadKeys.any { it.contains("ghp_super_secret") })

        // Verify large content reference handle store (Section 7 Rule #7)
        kernel.putLargeContentByReference("ref:test:1", listOf("ast-block-1", "ast-block-2"))
        val resolvedRef = kernel.getLargeContentByReference<List<String>>("ref:test:1")
        assertEquals(listOf("ast-block-1", "ast-block-2"), resolvedRef)

        // Verify reserved core namespace protection (Section 8)
        val badExtCmd = CommandDescriptor(
            id = "repository.hijack",
            title = "Hijack",
            iconName = "warning",
            defaultShortcut = "",
            requiredCapability = Capability.DATA_READ,
            lane = Lane.INTERACTIVE,
            inputSchema = emptyList(),
            outputSchema = emptyList(),
            possibleErrors = emptyList(),
            callableByAutomatedActors = false
        )
        val regResult = kernel.registerExtensionCommand("ext.untrusted", badExtCmd)
        assertTrue(regResult.isFailure)
    }

    @Test
    fun pluginHost_enforcesDualOwnerLifetimeAndSupervision() = runTest {
        val kernel = KeystoneKernel(parentScope = backgroundScope)
        val pluginHost = PluginHost(kernel = kernel)

        // Mount a contribution bound to a view scope
        val spec = SlotContributionSpec(
            contributionId = "contrib.custom.reader",
            slotId = SlotCatalogue.READER_STATUS_BAR,
            label = "View-Scoped Telemetry"
        )
        val mountRes = pluginHost.mountContribution(
            extensionId = "ext.word-count",
            spec = spec,
            mountOwnerScopeId = "scope:view:reader:README.md"
        )
        assertTrue(mountRes.isSuccess)
        assertTrue(
            pluginHost.mountedContributions.value.any {
                it.mountOwnerScopeId == "scope:view:reader:README.md"
            }
        )

        // Closing the owning view scope removes its mounted contribution immediately (Section 11.2)
        pluginHost.onMountOwnerClosed("scope:view:reader:README.md")
        assertFalse(
            pluginHost.mountedContributions.value.any {
                it.mountOwnerScopeId == "scope:view:reader:README.md"
            }
        )

        // Supervision auto-disables an extension after 3 failures (Section 12.3)
        pluginHost.reportContributionFailure("ext.commit-stats", "c1", "err 1")
        pluginHost.reportContributionFailure("ext.commit-stats", "c1", "err 2")
        val autoDisabled = pluginHost.reportContributionFailure("ext.commit-stats", "c1", "err 3")
        assertTrue(autoDisabled)
        val extState = pluginHost.extensionsFlow.value.first { it.manifest.extensionId == "ext.commit-stats" }
        assertFalse(extState.enabled)
    }

    @Test
    fun warden_enforcesCapabilitiesLoopGuardBoundApprovalsAndAuditChain() = runTest {
        val inMemoryAudit = mutableListOf<AuditChainEntity>()
        val fakeAuditDao = object : AuditChainDao {
            override suspend fun getLatestAuditEntry(): AuditChainEntity? = inMemoryAudit.lastOrNull()
            override fun observeRecentAuditEntries(): Flow<List<AuditChainEntity>> = MutableStateFlow(inMemoryAudit)
            override suspend fun getAllAuditEntriesOrdered(): List<AuditChainEntity> = inMemoryAudit.toList()
            override suspend fun insertAuditEntry(entity: AuditChainEntity): Long {
                val nextSeq = (inMemoryAudit.size + 1).toLong()
                inMemoryAudit.add(entity.copy(seq = nextSeq))
                return nextSeq
            }
        }

        val kernel = KeystoneKernel(parentScope = backgroundScope)
        kernel.registerSender("actor.sync", ActorKind.AUTOMATED_ACTOR, TrustTier.CORE, "scope:actor")
        val warden = Warden(auditChainDao = fakeAuditDao)

        // 1. Automated Actor requesting DATA_DELETE always requires bound human approval (Section 10.2)
        val deleteEnvelope = com.example.contracts.Envelope(
            kind = EnvelopeKind.COMMAND,
            topic = CommandCatalogue.REPOSITORY_REMOVE,
            payload = mapOf("repoId" to "git/git"),
            context = kernel.stampCallContext("actor.sync")
        )
        val decision = warden.evaluateCall(
            envelope = deleteEnvelope,
            requiredCapability = Capability.DATA_DELETE,
            resolvedResourceId = "repo:git/git",
            currentResourceHash = "hash_v1",
            currentResourceVersion = 1L,
            activeWorkspaceGeneration = kernel.currentWorkspaceGeneration()
        )
        assertTrue(decision is Warden.PolicyDecision.RequiresApproval)
        val approval = (decision as Warden.PolicyDecision.RequiresApproval).approval

        // 2. If resource state changes before approval is consumed, revalidation rejects it (Section 10.4)
        val invalidReval = warden.consumeAndRevalidateApproval(
            approvalId = approval.approvalId,
            currentResourceHash = "hash_v2_diverged",
            currentResourceVersion = 2L,
            activeWorkspaceGeneration = kernel.currentWorkspaceGeneration()
        )
        assertTrue(invalidReval.isFailure)

        // 3. Replay of an already-consumed approvalId is rejected
        val replayAttempt = warden.consumeAndRevalidateApproval(
            approvalId = approval.approvalId,
            currentResourceHash = "hash_v1",
            currentResourceVersion = 1L,
            activeWorkspaceGeneration = kernel.currentWorkspaceGeneration()
        )
        assertTrue(replayAttempt.isFailure)

        // 4. Outbound Policy blocks network in Air-Gapped Profile (Section 10.5 & 13)
        warden.applyProfile(ProfileCatalogue.OFFLINE_AIRGAPPED)
        val outboundCheck = warden.checkOutboundAllowed(
            OutboundRequestDescriptor(
                purpose = OutboundPurpose.FETCH_GIT_TREE,
                destinationHost = "api.github.com",
                url = "https://api.github.com/repos/git/git",
                workspaceId = "git/git",
                sensitivityLabel = com.example.contracts.DataSensitivityLabel.PUBLIC
            )
        )
        assertTrue(outboundCheck.isFailure)

        // 5. Verify tamper-evident hash chain integrity (Section 10.3 #6)
        assertTrue(warden.verifyAuditChainIntegrity())
    }

    @Test
    fun parseAndExtractGitHubCoordinates_handlesShareTextAndUrls() {
        assertEquals(
            "git" to "git",
            GitHubRestClient.parseGitHubCoordinates("git/git")
        )
        assertEquals(
            "JetBrains" to "kotlin",
            GitHubRestClient.extractGitHubCoordinatesFromSharedText(
                "Check out this repo shared from browser: https://github.com/JetBrains/kotlin/tree/master!"
            )
        )
        assertEquals(
            "rust-lang" to "mdBook",
            GitHubRestClient.parseGitHubCoordinates("git@github.com:rust-lang/mdBook.git")
        )
    }

    @Test
    fun gitGraphLayoutEngine_computes1000CommitsWithZeroDanglingStubs() = runTest {
        val engine = GitGraphLayoutEngine()
        val commits = engine.generateHighScaleMultiBranchDag(
            repoSlug = "git/git",
            defaultBranch = "master",
            targetCommitCount = 1050,
            targetBranchCount = 112
        )
        assertEquals(1050, commits.size)

        val graph = engine.computeCommitGraphLayout(
            repoId = "git/git",
            repoSlug = "git/git",
            activeBranch = "master",
            commits = commits,
            totalBranchesCount = 112,
            isHighScaleBenchmarkMode = true,
            isLiveFromGitHub = false
        )

        assertEquals(1050, graph.rows.size)
        assertTrue(graph.totalBranchesRepresented >= 100)
        assertTrue(graph.maxConcurrentLanes >= 4)

        val row0 = graph.rows[0]
        val row1 = graph.rows[1]
        assertEquals("c514088", row0.commit.shortSha)
        assertEquals("a3059f0", row1.commit.shortSha)

        for (i in 0 until graph.rows.size - 1) {
            val currentRow = graph.rows[i]
            val nextRow = graph.rows[i + 1]

            val exitingBottomLanes = currentRow.segments.filter {
                it.role == GraphSegmentRole.FULL_PASS_THROUGH ||
                    it.role == GraphSegmentRole.CENTER_TO_BOTTOM ||
                    it.role == GraphSegmentRole.CENTER_TO_BOTTOM_CURVE
            }.map { it.toLane }.toSet()

            val enteringTopLanes = nextRow.segments.filter {
                it.role == GraphSegmentRole.FULL_PASS_THROUGH ||
                    it.role == GraphSegmentRole.TOP_TO_CENTER ||
                    it.role == GraphSegmentRole.TOP_TO_CENTER_CURVE
            }.map { it.fromLane }.toSet()

            for (lane in exitingBottomLanes) {
                assertTrue(
                    "Row $i exits bottom at lane $lane, but row ${i + 1} does not receive lane $lane at top",
                    enteringTopLanes.contains(lane)
                )
            }
        }
    }

    @Test
    fun optimizedInRepoSearch_ranksExactPrefixAndFuzzyMatches() = runTest {
        val searchUseCase = ComputeVisibleTreeNodesUseCase()
        val nodes = listOf(
            RepoFileNode("1", "r", "README.md", "README.md", "", false, 100L, "s1", 0),
            RepoFileNode("2", "r", "docs/architecture-guide.md", "architecture-guide.md", "docs", false, 200L, "s2", 1),
            RepoFileNode("3", "r", "src/main/ParserCore.kt", "ParserCore.kt", "src/main", false, 300L, "s3", 2)
        )

        val docsArch = searchUseCase(nodes, emptySet(), "docs arch", markdownOnly = false)
        assertEquals(1, docsArch.size)
        assertEquals("docs/architecture-guide.md", docsArch.first().path)

        val fuzzyReadme = searchUseCase(nodes, emptySet(), "rmd", markdownOnly = false)
        assertTrue(fuzzyReadme.any { it.name == "README.md" })
    }

    @Test
    fun commonMarkEngine_parsesHeadingsTablesAndCodeFiles() = runTest {
        val engine = CommonMarkDocumentEngine()
        val mdNode = RepoFileNode(
            id = "repo:README.md",
            repoId = "repo",
            path = "README.md",
            name = "README.md",
            parentPath = "",
            isDirectory = false,
            sizeBytes = 256L,
            sha = "sha1",
            depth = 0
        )
        val payload = RawDocumentPayload(
            repoId = "repo",
            repoSlug = "git/git",
            branch = "master",
            path = "README.md",
            fileName = "README.md",
            rawContent = """
                # Main Title
                
                | Col A | Col B |
                | :--- | ---: |
                | Val 1 | Val 2 |
                
                - [x] Done task
            """.trimIndent(),
            sizeBytes = 256L,
            sha = "sha1",
            fetchedLive = true
        )

        val parsed = engine.parseToVirtualizedAst(payload, mdNode)
        assertEquals(1, parsed.tableOfContents.size)
        assertEquals("Main Title", parsed.tableOfContents.first().title)
        assertTrue(parsed.blocks.any { it is MarkdownAstBlock.TableBlockNode })
        assertTrue(parsed.blocks.any { it is MarkdownAstBlock.BulletListBlock })

        val csNode = mdNode.copy(id = "repo:Engine.cs", path = "src/Engine.cs", name = "Engine.cs")
        val csPayload = payload.copy(
            path = "src/Engine.cs",
            fileName = "Engine.cs",
            rawContent = "public class Engine {\n    public void Run() {}\n}"
        )
        val parsedCs = engine.parseToVirtualizedAst(csPayload, csNode)
        assertNotNull(parsedCs)
        assertTrue(parsedCs.isWrappedSourceFile)
        assertTrue(parsedCs.blocks.first() is MarkdownAstBlock.FileMetaBannerBlock)
    }
}
