package com.example.frontend

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.contracts.AuditLogEntry
import com.example.contracts.CommandDescriptor
import com.example.contracts.InstalledExtensionDescriptor
import com.example.contracts.JournaledOperation
import com.example.contracts.KeystoneArchitectureAuditReport
import com.example.contracts.MountedSlotContribution
import com.example.contracts.PendingApproval
import com.example.contracts.ProfileCatalogue
import com.example.contracts.SystemProfile
import com.example.contracts.TraceLogEntry
import com.example.ui.theme.JetBrainsMonoFontFamily

private enum class KeystoneInspectorTab(val label: String) {
    AUDIT("10-Law Audit"),
    TRACE("Keystone Trace"),
    COMMANDS("Commands"),
    JOURNAL("Journal & Undo"),
    WARDEN("Warden & Audit"),
    PROFILES("Profiles & Slots")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoneInspectorSheet(
    auditReport: KeystoneArchitectureAuditReport?,
    traceLog: List<TraceLogEntry>,
    commands: List<CommandDescriptor>,
    journaledOperations: List<JournaledOperation>,
    auditLog: List<AuditLogEntry>,
    pendingApprovals: List<PendingApproval>,
    activeProfile: SystemProfile,
    extensions: List<InstalledExtensionDescriptor>,
    mountedContributions: List<MountedSlotContribution>,
    onRefreshAudit: () -> Unit,
    onExecuteCommand: (String) -> Unit,
    onApproveOperation: (String) -> Unit,
    onRejectOperation: (String) -> Unit,
    onUndoLastMutation: () -> Unit,
    onRebuildDerivedCaches: () -> Unit,
    onRunAutomatedSyncActor: () -> Unit,
    onSwitchProfile: (String) -> Unit,
    onToggleExtension: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember {
        mutableStateOf(
            if (pendingApprovals.isNotEmpty()) KeystoneInspectorTab.WARDEN else KeystoneInspectorTab.AUDIT
        )
    }
    var commandQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(640.dp)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .testTag("keystone_inspector_sheet")
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Keystone Architecture Kernel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Crown ⇄ Keystone ⇄ Foundation · Profile: ${activeProfile.displayName}",
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close Keystone Inspector")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Action Bar (Re-Audit, Undo, Rebuild Derived Caches, Automated Actor)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    OutlinedButton(
                        onClick = onRefreshAudit,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("keystone_run_audit_button")
                    ) {
                        Icon(imageVector = Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Run Audit", fontSize = 12.sp)
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onUndoLastMutation,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("keystone_undo_button")
                    ) {
                        Icon(imageVector = Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Conditional Undo", fontSize = 12.sp)
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onRebuildDerivedCaches,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("keystone_rebuild_cache_button")
                    ) {
                        Icon(imageVector = Icons.Default.Build, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Rebuild Derived Caches", fontSize = 12.sp)
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onRunAutomatedSyncActor,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("keystone_run_actor_button")
                    ) {
                        Icon(imageVector = Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Run Sync Actor", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tab Selector
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(KeystoneInspectorTab.entries) { tab ->
                    val badgeText = when (tab) {
                        KeystoneInspectorTab.AUDIT -> auditReport?.let { "${it.passedCount}/${it.totalCount}" }
                        KeystoneInspectorTab.TRACE -> traceLog.size.toString()
                        KeystoneInspectorTab.COMMANDS -> commands.size.toString()
                        KeystoneInspectorTab.JOURNAL -> journaledOperations.size.toString()
                        KeystoneInspectorTab.WARDEN -> pendingApprovals.size.toString()
                        KeystoneInspectorTab.PROFILES -> mountedContributions.size.toString()
                    }
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = {
                            Text(
                                text = if (!badgeText.isNullOrBlank() && badgeText != "0") {
                                    "${tab.label} ($badgeText)"
                                } else {
                                    tab.label
                                },
                                fontSize = 12.sp
                            )
                        }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Tab Body
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    KeystoneInspectorTab.AUDIT -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            auditReport?.let { report ->
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (report.passedAll) {
                                            Color(0xFF00E639).copy(alpha = 0.12f)
                                        } else {
                                            MaterialTheme.colorScheme.errorContainer
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (report.passedAll) Color(0xFF00E639) else MaterialTheme.colorScheme.error
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (report.passedAll) {
                                                        "KEYSTONE ARCHITECTURE AUDIT: ${report.passedCount}/${report.totalCount} PASSED"
                                                    } else {
                                                        "KEYSTONE AUDIT VIOLATIONS DETECTED"
                                                    },
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = if (report.passedAll) Color(0xFF00E639) else MaterialTheme.colorScheme.error
                                                )
                                                Text(
                                                    text = "Catalogues valid=${report.catalogueReport.isValid} · Audit Hash-Chain intact=${report.auditChainIntact}",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontSize = 10.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = if (report.passedAll) Color(0xFF00E639) else MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }

                                items(report.checks, key = { it.id }) { check ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "[${check.id}] ${check.title}",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.5.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = if (check.passed) "PASS · ${check.sectionRef}" else "FAIL",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    color = if (check.passed) Color(0xFF00E639) else MaterialTheme.colorScheme.error
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = check.verificationDetail,
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    KeystoneInspectorTab.TRACE -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(traceLog, key = { it.seq }) { entry ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "#${entry.seq} [${entry.kind}] ${entry.topic}",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "${entry.lane} · gen=${entry.workspaceGeneration}",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = "actor=${entry.actorId} · corr=${entry.correlationId.take(8)} ${entry.operationId?.let { "· op=${it.take(10)}" }.orEmpty()}",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 10.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (entry.redactedPayloadKeys.isNotEmpty()) {
                                            Text(
                                                text = entry.redactedPayloadKeys.joinToString(", "),
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontSize = 10.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    KeystoneInspectorTab.COMMANDS -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = commandQuery,
                                onValueChange = { commandQuery = it },
                                placeholder = { Text("Search unified command registry (e.g. repository., graph., ui.)…") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val filtered = remember(commands, commandQuery) {
                                if (commandQuery.isBlank()) commands
                                else commands.filter {
                                    it.id.contains(commandQuery, ignoreCase = true) ||
                                        it.title.contains(commandQuery, ignoreCase = true)
                                }
                            }
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(filtered, key = { it.id }) { cmd ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onExecuteCommand(cmd.id) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = cmd.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "${cmd.id} · cap=${cmd.requiredCapability.id} · lane=${cmd.lane}",
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    fontSize = 10.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(onClick = { onExecuteCommand(cmd.id) }) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "Execute ${cmd.id}",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    KeystoneInspectorTab.JOURNAL -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(journaledOperations, key = { it.operationId }) { op ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "${op.commandTopic} [${op.state}]",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = op.operationId.take(12),
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = "resource=${op.resourceId} · preHash=${op.expectedPreconditionHash} → postHash=${op.targetResultHash}",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 10.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (!op.resultSummary.isNullOrBlank()) {
                                            Text(
                                                text = op.resultSummary,
                                                fontSize = 11.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    KeystoneInspectorTab.WARDEN -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (pendingApprovals.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "PENDING BOUND APPROVALS (${pendingApprovals.size})",
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                items(pendingApprovals, key = { it.approvalId }) { appr ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                text = appr.humanReadableSummary,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Bound hash=${appr.expectedContentHash} · v=${appr.expectedVersion} · policyV=${appr.policyVersion}",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontSize = 10.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = { onApproveOperation(appr.approvalId) },
                                                    modifier = Modifier.testTag("approve_${appr.approvalId}")
                                                ) {
                                                    Text("Approve & Revalidate")
                                                }
                                                OutlinedButton(
                                                    onClick = { onRejectOperation(appr.approvalId) }
                                                ) {
                                                    Text("Reject")
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Text(
                                    text = "TAMPER-EVIDENT AUDIT HASH CHAIN (${auditLog.size})",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            items(auditLog, key = { it.seq }) { entry ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "#${entry.seq} ${entry.decision} · ${entry.topic}",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.5.sp
                                            )
                                            Text(
                                                text = entry.capability,
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = "actor=${entry.actorId} (${entry.actorKind}) · res=${entry.resourceId}",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "chain: ${entry.previousHash.take(10)}… → ${entry.entryHash}",
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 10.sp,
                                            color = Color(0xFF00E639)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    KeystoneInspectorTab.PROFILES -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                Text(
                                    text = "COMPOSITION PROFILES (VARIATION BY SLOTS)",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(ProfileCatalogue.AllProfiles, key = { it.profileId }) { profile ->
                                val isSelected = profile.profileId == activeProfile.profileId
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    },
                                    border = BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSwitchProfile(profile.profileId) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = profile.displayName,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = profile.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Active profile",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "INSTALLED SLOT EXTENSIONS (${extensions.size})",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            items(extensions, key = { it.manifest.extensionId }) { ext ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${ext.manifest.name} (${ext.manifest.version})",
                                                fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = "id=${ext.manifest.extensionId} · tier=${ext.manifest.trustTier} · lane=${ext.manifest.lane}",
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontSize = 10.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = ext.enabled,
                                            onCheckedChange = { onToggleExtension(ext.manifest.extensionId) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
