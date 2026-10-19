package com.example.kernel

import com.example.contracts.ActorKind
import com.example.contracts.CallContext
import com.example.contracts.CommandCatalogue
import com.example.contracts.CommandDescriptor
import com.example.contracts.DataSensitivityLabel
import com.example.contracts.Envelope
import com.example.contracts.EnvelopeKind
import com.example.contracts.ErrorCode
import com.example.contracts.ErrorPayload
import com.example.contracts.KeystonePort
import com.example.contracts.Lane
import com.example.contracts.ResourceRef
import com.example.contracts.SlotCatalogue
import com.example.contracts.SlotDescriptor
import com.example.contracts.TopicCatalogue
import com.example.contracts.TraceLogEntry
import com.example.contracts.TrustTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Keystone Architecture — Section 1, 4, 5, 7, 8 & 12:
 * The single payload-agnostic Keystone between Crown (frontend) and Foundation (backend).
 *
 * Law 1: All traffic between Crown and Foundation passes through the Keystone as Envelopes.
 * Law 2: The Keystone knows nothing about feature payload types or business rules.
 */
class KeystoneKernel(
    parentScope: CoroutineScope
) : KeystonePort {
    override val kernelScope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    data class RegisteredSender(
        val senderId: String,
        val actorKind: ActorKind,
        val trustTier: TrustTier,
        val scopeId: String
    )

    private val registeredSenders = ConcurrentHashMap<String, RegisteredSender>()
    private val commandRegistry = ConcurrentHashMap<String, CommandDescriptor>()
    private val slotRegistry = ConcurrentHashMap<String, SlotDescriptor>()

    private val _commandsFlow = MutableStateFlow<List<CommandDescriptor>>(emptyList())
    override val commandsFlow: StateFlow<List<CommandDescriptor>> = _commandsFlow.asStateFlow()

    // Active workspace tracking for CallContext stamping (Section 10.1)
    private val workspaceGenerationCounter = AtomicLong(1L)
    @Volatile
    private var activeWorkspaceId: String = "workspace:home"

    // Deduplication set bounded to latest 2,048 eventIds (Section 7 — Delivery rules #1)
    private val seenEventIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(2048, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 2048
            }
        })
    )

    // Large content reference store bounded to latest 64 handles (Section 7 — Delivery rules #7)
    private val largeContentReferences: MutableMap<String, Any> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Any>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean {
                return size > 64
            }
        }
    )

    // Bounded Lane Queues & Fairness Concurrency Semaphores (Section 12.2)
    private val interactiveQueueDepth = AtomicInteger(0)
    private val backgroundQueueDepth = AtomicInteger(0)
    private val interactiveSemaphore = Semaphore(permits = 4)
    private val backgroundSemaphore = Semaphore(permits = 2)
    private val maxInteractiveQueue = 64
    private val maxBackgroundQueue = 128

    // Coalescing buffer for progress/status topics (Section 7 — Delivery rules #6)
    private val coalescedLatestByTopic = ConcurrentHashMap<String, Envelope>()

    // Redacted Trace Log (Section 16)
    private val traceSeq = AtomicLong(0L)
    private val traceMutex = Mutex()
    private val _traceLog = MutableStateFlow<List<TraceLogEntry>>(emptyList())
    override val traceLog: StateFlow<List<TraceLogEntry>> = _traceLog.asStateFlow()

    // Cross-side buses
    private val _toFoundation = MutableSharedFlow<Envelope>(extraBufferCapacity = 128)
    override val toFoundation: SharedFlow<Envelope> = _toFoundation.asSharedFlow()

    private val _toCrown = MutableSharedFlow<Envelope>(extraBufferCapacity = 128)
    override val toCrown: SharedFlow<Envelope> = _toCrown.asSharedFlow()

    // Topic-level handler table registered by Foundation Arbiter
    private var foundationHandler: (suspend (Envelope) -> Envelope?)? = null

    init {
        for (cmd in CommandCatalogue.AllCoreCommands) {
            commandRegistry[cmd.id] = cmd
        }
        for (slot in SlotCatalogue.AllSlots) {
            slotRegistry[slot.slotId] = slot
        }
        refreshCommandSnapshot()
    }

    override fun registerSender(
        senderId: String,
        actorKind: ActorKind,
        trustTier: TrustTier,
        scopeId: String
    ) {
        registeredSenders[senderId] = RegisteredSender(
            senderId = senderId,
            actorKind = actorKind,
            trustTier = trustTier,
            scopeId = scopeId
        )
    }

    override fun unregisterSenderScope(scopeId: String) {
        val toRemove = registeredSenders.values.filter { it.scopeId == scopeId }.map { it.senderId }
        toRemove.forEach { registeredSenders.remove(it) }
    }

    override fun switchWorkspace(newWorkspaceId: String): Long {
        activeWorkspaceId = newWorkspaceId
        return workspaceGenerationCounter.incrementAndGet()
    }

    override fun currentWorkspaceId(): String = activeWorkspaceId
    override fun currentWorkspaceGeneration(): Long = workspaceGenerationCounter.get()

    override fun registerExtensionCommand(
        extensionId: String,
        descriptor: CommandDescriptor
    ): Result<Unit> {
        if (commandRegistry.containsKey(descriptor.id)) {
            return Result.failure(IllegalArgumentException("Command ID already exists: ${descriptor.id}"))
        }
        val usesCoreNamespace = CommandCatalogue.ReservedCorePrefixes.any { prefix ->
            descriptor.id.startsWith(prefix)
        }
        if (usesCoreNamespace) {
            return Result.failure(
                IllegalArgumentException("Extension $extensionId cannot register reserved core namespace: ${descriptor.id}")
            )
        }
        commandRegistry[descriptor.id] = descriptor
        refreshCommandSnapshot()
        return Result.success(Unit)
    }

    override fun unregisterExtensionCommands(commandIds: Collection<String>) {
        commandIds.forEach { commandRegistry.remove(it) }
        refreshCommandSnapshot()
    }

    override fun getCommandDescriptor(commandId: String): CommandDescriptor? = commandRegistry[commandId]
    override fun getSlotDescriptor(slotId: String): SlotDescriptor? = slotRegistry[slotId]

    private fun refreshCommandSnapshot() {
        _commandsFlow.value = commandRegistry.values.sortedBy { it.id }
    }

    override fun bindFoundationArbiterHandler(handler: suspend (Envelope) -> Envelope?) {
        foundationHandler = handler
    }

    override fun stampCallContext(
        senderId: String,
        workspaceOverride: String?,
        generationOverride: Long?,
        sensitivityLabel: DataSensitivityLabel
    ): CallContext {
        val reg = registeredSenders[senderId] ?: RegisteredSender(
            senderId = senderId,
            actorKind = ActorKind.USER,
            trustTier = TrustTier.CORE,
            scopeId = "scope:unregistered"
        )
        return CallContext(
            actorId = reg.senderId,
            actorKind = reg.actorKind,
            trustTier = reg.trustTier,
            scopeId = reg.scopeId,
            workspaceId = workspaceOverride ?: activeWorkspaceId,
            workspaceGeneration = generationOverride ?: workspaceGenerationCounter.get(),
            sensitivityLabel = sensitivityLabel
        )
    }

    override suspend fun dispatchToFoundation(
        senderId: String,
        topic: String,
        kind: EnvelopeKind,
        payload: Map<String, String>,
        operationId: String?,
        resource: ResourceRef?,
        correlationId: String?,
        causationId: String?,
        workspaceOverride: String?,
        generationOverride: Long?,
        sensitivityLabel: DataSensitivityLabel
    ): Envelope {
        val cmdDescriptor = commandRegistry[topic]
        val lane = cmdDescriptor?.lane ?: Lane.INTERACTIVE
        val stampedContext = stampCallContext(
            senderId = senderId,
            workspaceOverride = workspaceOverride,
            generationOverride = generationOverride,
            sensitivityLabel = sensitivityLabel
        )

        val eventId = UUID.randomUUID().toString()
        val envelope = Envelope(
            eventId = eventId,
            kind = kind,
            topic = topic,
            payload = payload,
            context = stampedContext,
            correlationId = correlationId ?: eventId,
            causationId = causationId,
            operationId = operationId ?: if (cmdDescriptor?.mutatesDurableState == true) {
                "op-${UUID.randomUUID()}"
            } else {
                null
            },
            resource = resource,
            lane = lane,
            coalescable = TopicCatalogue.CoalescableTopics.contains(topic)
        )

        return routeEnvelopeToFoundation(envelope)
    }

    override suspend fun routeEnvelopeToFoundation(envelope: Envelope): Envelope {
        if (!seenEventIds.add(envelope.eventId)) {
            return createErrorReply(
                request = envelope,
                code = ErrorCode.OperationIdReused,
                messageKey = "error.duplicate_event_id"
            )
        }

        recordTrace(envelope)

        if (System.currentTimeMillis() > envelope.deadlineEpochMs) {
            val timeoutReply = createErrorReply(
                request = envelope,
                code = ErrorCode.Timeout,
                messageKey = "error.request_deadline_exceeded",
                retryable = true
            )
            recordTrace(timeoutReply)
            return timeoutReply
        }

        val queueCounter = if (envelope.lane == Lane.INTERACTIVE) interactiveQueueDepth else backgroundQueueDepth
        val maxQueue = if (envelope.lane == Lane.INTERACTIVE) maxInteractiveQueue else maxBackgroundQueue
        val semaphore = if (envelope.lane == Lane.INTERACTIVE) interactiveSemaphore else backgroundSemaphore

        val currentDepth = queueCounter.incrementAndGet()
        if (currentDepth > maxQueue) {
            queueCounter.decrementAndGet()
            val busyReply = createErrorReply(
                request = envelope,
                code = ErrorCode.Busy,
                messageKey = "error.lane_queue_saturated",
                params = mapOf("lane" to envelope.lane.name),
                retryable = true
            )
            recordTrace(busyReply)
            return busyReply
        }

        return try {
            semaphore.withPermit {
                _toFoundation.tryEmit(envelope)
                val handler = foundationHandler
                val reply = if (handler != null) {
                    handler(envelope) ?: Envelope(
                        kind = EnvelopeKind.REPLY,
                        topic = "${envelope.topic}.ack",
                        context = envelope.context,
                        correlationId = envelope.correlationId,
                        causationId = envelope.eventId,
                        operationId = envelope.operationId,
                        lane = envelope.lane
                    )
                } else {
                    createErrorReply(
                        request = envelope,
                        code = ErrorCode.Internal,
                        messageKey = "error.foundation_arbiter_unattached"
                    )
                }
                recordTrace(reply)
                _toCrown.tryEmit(reply)
                reply
            }
        } finally {
            queueCounter.decrementAndGet()
        }
    }

    override suspend fun publishEventToCrown(
        senderId: String,
        topic: String,
        payload: Map<String, String>,
        resource: ResourceRef?,
        correlationId: String?,
        causationId: String?,
        operationId: String?,
        lane: Lane
    ) {
        val eventId = UUID.randomUUID().toString()
        val coalescable = TopicCatalogue.CoalescableTopics.contains(topic)
        val envelope = Envelope(
            eventId = eventId,
            kind = EnvelopeKind.EVENT,
            topic = topic,
            payload = payload,
            context = stampCallContext(senderId),
            correlationId = correlationId ?: eventId,
            causationId = causationId,
            operationId = operationId,
            resource = resource,
            lane = lane,
            coalescable = coalescable
        )

        if (!seenEventIds.add(envelope.eventId)) return
        if (coalescable) {
            coalescedLatestByTopic[topic] = envelope
        }
        recordTrace(envelope)
        _toCrown.emit(envelope)
    }

    override fun createErrorReply(
        request: Envelope,
        code: ErrorCode,
        messageKey: String,
        params: Map<String, String>,
        retryable: Boolean
    ): Envelope {
        return Envelope(
            kind = EnvelopeKind.ERROR,
            topic = "${request.topic}.error",
            payload = params,
            context = request.context,
            correlationId = request.correlationId,
            causationId = request.eventId,
            operationId = request.operationId,
            resource = request.resource,
            lane = request.lane,
            error = ErrorPayload(
                code = code,
                messageKey = messageKey,
                params = params,
                retryable = retryable
            )
        )
    }

    override fun putLargeContentByReference(refId: String, value: Any) {
        largeContentReferences[refId] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getLargeContentByReference(refId: String): T? {
        return largeContentReferences[refId] as? T
    }

    private suspend fun recordTrace(envelope: Envelope) {
        val redactedKeys = envelope.payload.keys.map { key ->
            val lower = key.lowercase()
            if (lower.contains("token") || lower.contains("secret") || lower.contains("password") || lower.contains("auth")) {
                "$key=[REDACTED]"
            } else {
                val v = envelope.payload[key].orEmpty().take(28)
                "$key=$v"
            }
        }
        val entry = TraceLogEntry(
            seq = traceSeq.incrementAndGet(),
            eventId = envelope.eventId,
            correlationId = envelope.correlationId,
            causationId = envelope.causationId,
            operationId = envelope.operationId,
            kind = envelope.kind,
            topic = envelope.topic,
            lane = envelope.lane,
            actorId = envelope.context.actorId,
            workspaceGeneration = envelope.context.workspaceGeneration,
            redactedPayloadKeys = redactedKeys,
            errorCode = envelope.error?.code,
            timestampEpochMs = envelope.timestampEpochMs
        )
        traceMutex.withLock {
            _traceLog.update { current ->
                (listOf(entry) + current).take(120)
            }
        }
    }
}
