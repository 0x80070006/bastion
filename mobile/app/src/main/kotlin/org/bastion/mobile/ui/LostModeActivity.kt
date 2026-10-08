package org.bastion.mobile.ui

import android.app.KeyguardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.bastion.core.designsystem.theme.BastionTheme
import org.bastion.feature.protection.LostModeScreen
import org.bastion.mobile.data.AgentRepository
import org.bastion.mobile.service.LostAnnouncer

/**
 * Shown above the lock screen while Lost mode is on, like a full-screen call alert. It displays
 * the owner's contact card and announces it aloud (siren + speech); dismissing it requires
 * unlocking the phone.
 */
@AndroidEntryPoint
class LostModeActivity : ComponentActivity() {
    @Inject lateinit var repository: AgentRepository
    private val announcer by lazy { LostAnnouncer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val settings = repository.state.value.settings
        announcer.start(settings.contactMessage, settings.contactPhone)
        setContent {
            BastionTheme {
                val state by repository.state.collectAsStateWithLifecycle()
                if (!state.settings.lostMode && state.settings.contactMessage.isEmpty()) finish()
                LostModeScreen(
                    message = state.settings.contactMessage,
                    phone = state.settings.contactPhone,
                    email = state.settings.contactEmail,
                    onOwner = ::dismissAsOwner,
                )
            }
        }
    }

    override fun onDestroy() {
        announcer.stop()
        super.onDestroy()
    }

    private fun dismissAsOwner() {
        announcer.stop()
        val keyguard = getSystemService<KeyguardManager>()
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            },
        )
    }
}
