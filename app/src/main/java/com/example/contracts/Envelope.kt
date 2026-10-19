package com.example.contracts

import java.security.MessageDigest
import java.util.UUID

/**
 * Keystone Architecture — Section 7 & Section 9:
 * Plain, serializable data contracts shared across Crown (frontend), Keystone (kernel),
 * and Foundation (backend).
 */

enum class EnvelopeKind {
    COMMAND,
    REQUEST,
    REPLY,
    EVENT,
    ERROR
}

enum class Lane {
    INTERACTIVE,
    BACKGROUND
}

enum class ActorKind {
    USER,
    AUTOMATED_ACTOR,
    EXTENSION,
    SYSTEM
}

enum class TrustTier {
    CORE,
    COMMUNITY,
    REMOTE
}

enum class DataSensitivityLabel {
    PUBLIC,
    AUTHENTICATED_REPO,
    SECRET_CREDENTIAL
}

/**
 * Stamped exclusively by the Keystone from the scope in which the sender was registered.
 * Payload content can never spoof or override this authority context (Law 5 & Section 10.1).
 */
data class CallContext(
    val actorId: String,
    val actorKind: ActorKind,
    val trustTier: TrustTier,
    val scopeId: String,
    val workspaceId: String,
    val workspaceGeneration: Long,
    val sensitivityLabel: DataSensitivityLabel = DataSensitivityLabel.PUBLIC
)

/**
 * Identifies a durable resource along with its monotonic version and mandatory content hash precondition.
 */
data class ResourceRef(
    val resourceId: String,
    val version: Long,
    val contentHash: String
) {
    companion object {
        const val ABSENT_HASH = "ABSENT"

        fun computeHash(content: String): String {
            if (content.isEmpty()) return ABSENT_HASH
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            return bytes.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Standardized Error Catalogue (Section 7 — Errors).
 */
enum class ErrorCode {
    Timeout,
    Busy,
    Conflict,
    PermissionDenied,
    ApprovalRequired,
    ApprovalInvalid,
    StaleWorkspace,
    OperationIdReused,
    NotFound,
    StorageUnavailable,
    Cancelled,
    Internal
}

data class ErrorPayload(
    val code: ErrorCode,
    val messageKey: String,
    val params: Map<String, String> = emptyMap(),
    val retryable: Boolean = false
)

/**
 * The Envelope (Section 7):
 * Every cross-side message is an Envelope of plain, serializable data.
 * Payloads are opaque key-value maps to the Keystone so the Keystone never knows feature types (Law 2).
 */
data class Envelope(
    val eventId: String = UUID.randomUUID().toString(),
    val kind: EnvelopeKind,
    val topic: String,
    val payload: Map<String, String> = emptyMap(),
    val context: CallContext,
    val correlationId: String = eventId,
    val causationId: String? = null,
    val operationId: String? = null,
    val resource: ResourceRef? = null,
    val lane: Lane = Lane.INTERACTIVE,
    val deadlineEpochMs: Long = System.currentTimeMillis() + 15_000L,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val coalescable: Boolean = false,
    val error: ErrorPayload? = null
)

/**
 * Operation lifecycle states in the Mutation Protocol (Section 9.2).
 */
enum class OperationState {
    RECEIVED,
    REJECTED,
    AWAITING_APPROVAL,
    AUTHORIZED,
    CONFLICTED,
    PREPARED,
    APPLIED,
    COMMITTED,
    FAILED;

    val isTerminal: Boolean
        get() = this == COMMITTED || this == REJECTED || this == CONFLICTED || this == FAILED
}

/**
 * Durable Operation Journal record (Section 9.2 & 9.4).
 */
data class JournaledOperation(
    val operationId: String,
    val commandTopic: String,
    val resourceId: String,
    val expectedPreconditionHash: String,
    val targetResultHash: String,
    val argumentsHash: String,
    val argumentsJson: String,
    val beforeSnapshotJson: String?,
    val afterSnapshotJson: String?,
    val state: OperationState,
    val actorId: String,
    val actorKind: ActorKind,
    val workspaceId: String,
    val workspaceGeneration: Long,
    val resultSummary: String?,
    val updatedAtEpochMs: Long
)

/**
 * Bound approval record managed by the Warden (Section 10.4).
 */
data class PendingApproval(
    val approvalId: String,
    val operationId: String,
    val commandTopic: String,
    val resourceId: String,
    val expectedContentHash: String,
    val expectedVersion: Long,
    val promptSummaryHash: String,
    val humanReadableSummary: String,
    val policyVersion: Int,
    val scopeId: String,
    val workspaceId: String,
    val workspaceGeneration: Long,
    val expiresAtEpochMs: Long,
    val OriginalEnvelope: Envelope
)

/**
 * Tamper-evident append-only audit entry (Section 10.3).
 */
data class AuditLogEntry(
    val seq: Long,
    val operationId: String,
    val actorId: String,
    val actorKind: ActorKind,
    val capability: String,
    val topic: String,
    val resourceId: String,
    val decision: String,
    val timestampEpochMs: Long,
    val previousHash: String,
    val entryHash: String
)

/**
 * Trace log entry recorded by the Keystone for every envelope crossing (Section 16).
 */
data class TraceLogEntry(
    val seq: Long,
    val eventId: String,
    val correlationId: String,
    val causationId: String?,
    val operationId: String?,
    val kind: EnvelopeKind,
    val topic: String,
    val lane: Lane,
    val actorId: String,
    val workspaceGeneration: Long,
    val redactedPayloadKeys: List<String>,
    val errorCode: ErrorCode? = null,
    val timestampEpochMs: Long
)
