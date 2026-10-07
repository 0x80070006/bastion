package org.bastion.mobile.data

import android.os.Build
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bastion.core.agent.InviteParse
import org.bastion.core.agent.PhoneAgent
import org.bastion.core.agent.state.AgentState
import org.bastion.core.agent.state.JournalEntry
import org.bastion.core.crypto.Identity
import org.bastion.core.crypto.Sodium
import org.bastion.protocol.v1.CommandResult
import org.bastion.protocol.v1.Event

/** Result of a pairing attempt. */
sealed interface PairOutcome {
    data object Enrolled : PairOutcome

    data class Invalid(val reason: InviteParse.Reason) : PairOutcome

    data object RelayRefused : PairOutcome

    data object RelayUnreachable : PairOutcome

    data object PinMismatch : PairOutcome
}

/**
 * Single owner of the agent state. Every transition is applied under a mutex and persisted
 * before its effects (network sends, command execution) happen.
 */
@Singleton
class AgentRepository @Inject constructor(
    private val store: SecureStateStore,
    private val agent: PhoneAgent,
    private val sodium: Sodium,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(store.read())
    val state: StateFlow<AgentState> = _state.asStateFlow()
    private val _relayOk = MutableStateFlow<Boolean?>(null)

    /** `null` until the first relay round-trip. */
    val relayOk: StateFlow<Boolean?> = _relayOk.asStateFlow()

    private var cachedClient: Pair<String, RelayClient>? = null

    fun reportRelay(ok: Boolean) {
        _relayOk.value = ok
    }

    /** Applies [transition] atomically and persists the result before returning [T]. */
    suspend fun <T> transact(transition: (AgentState) -> Pair<AgentState, T>): T = mutex.withLock {
        val (next, value) = transition(_state.value)
        if (next != _state.value) {
            store.write(next)
            _state.value = next
        }
        value
    }

    fun client(): RelayClient? {
        val current = _state.value
        if (!current.hasPairing()) return null
        val pairing = current.pairing
        val key = pairing.relayUrl + Identity.deviceId(sodium, pairing.identitySeed.toByteArray()).contentHashCode()
        cachedClient?.let { (cachedKey, client) -> if (cachedKey == key) return client }
        val client =
            RelayClient(pairing.relayUrl, pairing.relayPin.toByteArray(), sodium, pairing.identitySeed.toByteArray())
        cachedClient = key to client
        return client
    }

    fun controllerId(): ByteArray? = _state.value.takeIf { it.hasPairing() }?.let {
        Identity.deviceId(sodium, it.pairing.controllerIdentity.toByteArray())
    }

    /** Scans an invitation, enrolls on the relay and stores the pending pairing. */
    suspend fun pair(uri: String): PairOutcome {
        val now = System.currentTimeMillis()
        val invite = when (val parsed = agent.parseInvite(uri, now)) {
            is InviteParse.Invalid -> return PairOutcome.Invalid(parsed.reason)
            is InviteParse.Valid -> parsed.invite
        }
        val label = PhoneAgent.sanitizeLabel("${Build.MANUFACTURER} ${Build.MODEL}")
        val enrollment = agent.enroll(invite, label, now, _state.value.toBuilder().clearPairing().build())
        val client = RelayClient(
            enrollment.state.pairing.relayUrl,
            enrollment.state.pairing.relayPin.toByteArray(),
            sodium,
            enrollment.state.pairing.identitySeed.toByteArray(),
        )
        val response = try {
            client.enroll(enrollment.request)
        } catch (e: RelayException) {
            Log.w(TAG, "enrollment failed: ${e.message}")
            return when (e) {
                is RelayException.PinMismatch -> PairOutcome.PinMismatch
                is RelayException.Unreachable -> PairOutcome.RelayUnreachable
                else -> PairOutcome.RelayRefused
            }
        }
        val enrolled = agent.markEnrolled(enrollment.state, response) ?: return PairOutcome.RelayRefused
        transact { _ -> journal(enrolled, "pairing.enrolled") to Unit }
        return PairOutcome.Enrolled
    }

    /** Local SAS decision; refusing forgets the pairing. */
    suspend fun confirm(accept: Boolean) {
        val controller = controllerId() ?: return
        val envelope = transact { s ->
            val (next, envelope) = agent.confirm(s, accept, System.currentTimeMillis())
            journal(next, if (accept) "pairing.confirmed" else "pairing.rejected") to envelope
        }
        runCatching { client()?.send(controller, envelope) }
            .onFailure { Log.w(TAG, "confirmation not delivered: ${it.javaClass.simpleName}") }
        if (!accept) forget(revokeOnRelay = true)
    }

    /** Erases the pairing (keys included). */
    suspend fun forget(revokeOnRelay: Boolean) {
        if (revokeOnRelay) {
            val ownId = _state.value.takeIf { agent.isPaired(it) }?.let { agent.deviceId(it) }
            if (ownId != null) {
                runCatching { client()?.revoke(ownId) }
                    .onFailure { Log.w(TAG, "relay revocation failed: ${it.javaClass.simpleName}") }
            }
        }
        transact { s ->
            val cleared = s.toBuilder().clearPairing().setSettings(s.settings.toBuilder().clear()).build()
            journal(cleared, "pairing.removed") to Unit
        }
        cachedClient = null
    }

    suspend fun sendEvent(event: Event): Boolean {
        val controller = controllerId() ?: return false
        val envelope = transact { s ->
            agent.event(s, event, System.currentTimeMillis()) ?: (s to null)
        } ?: return false
        return deliver(controller, envelope)
    }

    suspend fun sendResult(commandId: ByteArray, status: CommandResult.Status, reason: String = ""): Boolean {
        val controller = controllerId() ?: return false
        val envelope = transact { s ->
            agent.result(s, commandId, status, reason, System.currentTimeMillis()) ?: (s to null)
        } ?: return false
        return deliver(controller, envelope)
    }

    fun shouldRotate(): Boolean = agent.shouldRotate(_state.value, System.currentTimeMillis())

    /** Rotates the X25519 key and notifies the controller (PROTOCOL.md §4). */
    suspend fun rotateKeys(): Boolean {
        val controller = controllerId() ?: return false
        val envelope = transact { s ->
            agent.rotateExchangeKey(s, System.currentTimeMillis())?.let { (st, env) -> st to (env as ByteArray?) }
                ?: (s to null)
        } ?: return false
        val delivered = deliver(controller, envelope)
        if (delivered) log("keys.rotated")
        return delivered
    }

    suspend fun deliver(recipient: ByteArray, envelope: ByteArray): Boolean = runCatching {
        client()?.send(recipient, envelope)
        true
    }.getOrElse {
        Log.w(TAG, "send failed: ${it.javaClass.simpleName}")
        false
    }

    suspend fun log(kind: String, detail: String = "") {
        transact { s -> journal(s, kind, detail) to Unit }
    }

    companion object {
        private const val TAG = "AgentRepository"
        private const val MAX_JOURNAL = 300

        fun journal(state: AgentState, kind: String, detail: String = ""): AgentState {
            val entries = (
                state.journalList + JournalEntry.newBuilder()
                    .setTimeMs(System.currentTimeMillis())
                    .setKind(kind)
                    .setDetail(detail)
                    .build()
                ).takeLast(MAX_JOURNAL)
            return state.toBuilder().clearJournal().addAllJournal(entries).build()
        }
    }
}
