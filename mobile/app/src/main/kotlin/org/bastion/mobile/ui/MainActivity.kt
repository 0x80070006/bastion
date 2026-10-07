package org.bastion.mobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.bastion.core.designsystem.component.PrimaryButton
import org.bastion.core.designsystem.theme.BastionTheme
import org.bastion.core.designsystem.tokens.SpacingTokens
import org.bastion.feature.onboarding.PairingScreen
import org.bastion.feature.onboarding.SasScreen
import org.bastion.feature.protection.HealthId
import org.bastion.feature.protection.ProtectionScreen
import org.bastion.feature.protection.UnpairedScreen
import org.bastion.mobile.R
import org.bastion.mobile.service.DeviceState
import org.bastion.mobile.service.ProtectionService

/**
 * Single activity. Protected by an app lock (biometrics or device credential) so a thief with
 * an unlocked phone cannot simply disable the protection (THREAT_MODEL.md T-28).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var unlocked by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var cameraGranted by mutableStateOf(false)
    private var pendingLink by mutableStateOf<String?>(null)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = granted(Manifest.permission.CAMERA)
        viewModel.refresh()
    }
    private val systemScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide content from screenshots and the recents thumbnail (THREAT_MODEL.md T-13).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        cameraGranted = granted(Manifest.permission.CAMERA)
        pendingLink = pairingLink(intent)
        setContent { BastionTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairingLink(intent)?.let { pendingLink = it }
    }

    // A bastion://pair/v1#… deep link, processed only once the app is unlocked and confirmed
    // with the SAS, exactly like a scanned QR code.
    private fun pairingLink(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.takeIf {
            it.startsWith("bastion://pair/")
        }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
        if (!unlocked) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) unlocked = false
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun authenticate() {
        if (getSystemService<KeyguardManager>()?.isDeviceSecure != true) {
            // No screen lock: nothing to authenticate against; the Health list flags it.
            unlocked = true
            return
        }
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_title))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is the core use case of an anti-theft agent that must
    // stay reachable (docs/PERMISSIONS.md); the app is distributed outside the Play Store.
    @SuppressLint("BatteryLife")
    private fun fix(id: HealthId) {
        val device = DeviceState(this)
        when (id) {
            HealthId.SCREEN_LOCK -> systemScreen.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))

            HealthId.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }

            HealthId.LOCATION -> permissions.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )

            HealthId.BACKGROUND_LOCATION -> if (device.hasLocation()) {
                permissions.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            } else {
                fix(HealthId.LOCATION)
            }

            HealthId.DEVICE_ADMIN -> systemScreen.launch(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, device.adminComponent)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation)),
            )

            HealthId.BATTERY -> systemScreen.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()),
            )

            HealthId.RELAY -> Unit
        }
        ProtectionService.start(this)
    }

    @Composable
    private fun Root() {
        if (!unlocked) {
            LockedScreen(::authenticate)
            return
        }
        val home by viewModel.home.collectAsStateWithLifecycle()
        val pairing by viewModel.pairing.collectAsStateWithLifecycle()
        // A pairing deep link received while unpaired: open the pairing flow and use it.
        LaunchedEffect(pendingLink, home) {
            val link = pendingLink
            if (link != null && home == HomeState.Unpaired) {
                pendingLink = null
                scanning = true
                viewModel.pair(link) { scanning = false }
            }
        }
        when (val state = home) {
            HomeState.Unpaired -> if (scanning) {
                PairingScreen(
                    state = pairing,
                    cameraGranted = cameraGranted,
                    onRequestCamera = { permissions.launch(arrayOf(Manifest.permission.CAMERA)) },
                    onLink = { link -> viewModel.pair(link) { scanning = false } },
                    onBack = {
                        scanning = false
                        viewModel.resetPairing()
                    },
                )
            } else {
                UnpairedScreen(onPair = { scanning = true })
            }

            is HomeState.VerifySas -> SasScreen(
                sas = state.sas,
                controllerLabel = state.controller,
                awaitingRemote = state.awaitingRemote,
                onConfirm = { viewModel.confirm(true) },
                onReject = { viewModel.confirm(false) },
            )

            is HomeState.Protected -> ProtectionScreen(
                state = state.ui,
                onFix = ::fix,
                onUnpair = viewModel::unpair,
            )
        }
    }
}

@Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    val colors = BastionTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(SpacingTokens.Lg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.lock_title), style = BastionTheme.typography.title, color = colors.textPrimary)
        PrimaryButton(stringResource(R.string.lock_unlock), onUnlock)
    }
}
