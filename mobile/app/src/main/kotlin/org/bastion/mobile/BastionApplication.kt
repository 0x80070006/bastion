package org.bastion.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import org.bastion.mobile.data.AgentRepository
import org.bastion.mobile.service.Notifications
import org.bastion.mobile.service.ProtectionService

@HiltAndroidApp
class BastionApplication : Application() {
    @Inject lateinit var repository: AgentRepository

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        if (repository.state.value.hasPairing()) ProtectionService.start(this)
    }
}
