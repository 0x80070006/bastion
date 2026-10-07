package org.bastion.mobile.di

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bastion.core.agent.PhoneAgent
import org.bastion.core.crypto.LazySodiumAdapter
import org.bastion.core.crypto.Sodium
import org.bastion.mobile.data.AgentRepository

/** Process-wide scope for work that must outlive a screen or a broadcast. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun sodium(): Sodium = LazySodiumAdapter(LazySodiumAndroid(SodiumAndroid()))

    @Provides
    @Singleton
    fun agent(sodium: Sodium): PhoneAgent = PhoneAgent(sodium)

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Access for components Hilt cannot inject (broadcast receivers created by the system). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun repository(): AgentRepository

    @ApplicationScope
    fun scope(): CoroutineScope
}
