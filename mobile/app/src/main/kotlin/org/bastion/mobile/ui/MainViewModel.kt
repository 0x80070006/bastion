package org.bastion.mobile.ui

import android.app.Application
import android.text.format.DateUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.bastion.core.agent.InviteParse
import org.bastion.core.agent.PhoneAgent
import org.bastion.core.crypto.Pairing
import org.bastion.feature.onboarding.PairingUiState
import org.bastion.feature.protection.ProtectionUiState
import org.bastion.mobile.R
import org.bastion.mobile.data.AgentRepository
import org.bastion.mobile.data.PairOutcome
import org.bastion.mobile.service.DeviceState
import org.bastion.mobile.service.ProtectionService

/** What the home route shows. */
sealed interface HomeState {
    data object Unpaired : HomeState

    data class VerifySas(val sas: String, val controller: String, val awaitingRemote: Boolean) : HomeState

    data class Protected(val ui: ProtectionUiState) : HomeState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val repository: AgentRepository,
    private val agent: PhoneAgent,
) : AndroidViewModel(application) {
    private val device = DeviceState(application)
    private val refresh = MutableStateFlow(0)
    private val _pairing = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairing: StateFlow<PairingUiState> = _pairing.asStateFlow()

    val home: StateFlow<HomeState> = combine(repository.state, repository.relayOk, refresh) { state, relayOk, _ ->
        when {
            !agent.isPaired(state) -> HomeState.Unpaired

            !state.pairing.localConfirmed || !state.pairing.remoteConfirmed -> HomeState.VerifySas(
                sas = Pairing.formatSas(state.pairing.sas),
                controller = state.pairing.controllerLabel,
                awaitingRemote = state.pairing.localConfirmed,
            )

            else -> HomeState.Protected(
                ProtectionUiState(
                    controllerLabel = state.pairing.controllerLabel,
                    relayUrl = state.pairing.relayUrl,
                    lastContact = state.pairing.lastContactMs.takeIf { it > 0 }?.let {
                        DateUtils.getRelativeTimeSpanString(it).toString()
                    },
                    active = true,
                    lostMode = state.settings.lostMode,
                    health = device.health(relayOk),
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeState.Unpaired)

    /** Re-reads permissions after returning from a system screen. */
    fun refresh() {
        refresh.value += 1
    }

    fun pair(link: String, onDone: () -> Unit) {
        if (_pairing.value is PairingUiState.Working) return
        _pairing.value = PairingUiState.Working
        viewModelScope.launch {
            val app = getApplication<Application>()
            val outcome = repository.pair(link)
            _pairing.value = when (outcome) {
                PairOutcome.Enrolled -> {
                    ProtectionService.start(app)
                    onDone()
                    PairingUiState.Idle
                }

                is PairOutcome.Invalid -> PairingUiState.Failed(
                    app.getString(
                        when (outcome.reason) {
                            InviteParse.Reason.EXPIRED -> R.string.pair_error_expired
                            InviteParse.Reason.INSECURE_ENDPOINT -> R.string.pair_error_insecure
                            InviteParse.Reason.BAD_SIGNATURE -> R.string.pair_error_signature
                            else -> R.string.pair_error_malformed
                        },
                    ),
                )

                PairOutcome.PinMismatch -> PairingUiState.Failed(app.getString(R.string.pair_error_pin))

                PairOutcome.RelayUnreachable -> PairingUiState.Failed(app.getString(R.string.pair_error_unreachable))

                PairOutcome.RelayRefused -> PairingUiState.Failed(app.getString(R.string.pair_error_refused))
            }
        }
    }

    fun resetPairing() {
        _pairing.value = PairingUiState.Idle
    }

    fun confirm(accept: Boolean) {
        viewModelScope.launch {
            repository.confirm(accept)
            ProtectionService.start(getApplication())
        }
    }

    fun unpair() {
        viewModelScope.launch { repository.forget(revokeOnRelay = true) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
