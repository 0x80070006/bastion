package org.bastion.mobile.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.bastion.protocol.v1.CallControl
import org.bastion.protocol.v1.CallState
import org.bastion.protocol.v1.Contact
import org.bastion.protocol.v1.ContactsPage

/**
 * Reads the address book for the paired owner. Needs the owner's READ_CONTACTS grant; without it
 * [page] returns null and the controller is told so. Nothing is cached: each request re-reads the
 * provider, and the controller keeps the result in memory only.
 */
class ContactsReader(private val context: Context) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** One page of contacts matching [query], sorted by name, or null without the permission. */
    fun page(query: String, offset: Int, limit: Int): ContactsPage? {
        if (!hasPermission()) return null
        val needle = query.trim().lowercase()
        val digits = needle.filter { it.isDigit() }
        val matches = readAll().filter { contact ->
            needle.isEmpty() ||
                contact.name.lowercase().contains(needle) ||
                (digits.isNotEmpty() && contact.numbersList.any { n -> n.filter { it.isDigit() }.contains(digits) })
        }
        val start = offset.coerceIn(0, matches.size)
        return ContactsPage.newBuilder()
            .setOffset(start)
            .setTotal(matches.size)
            .setQuery(query)
            .addAllContacts(matches.subList(start, minOf(matches.size, start + limit)))
            .build()
    }

    /** Display name of the contact owning [number], or "" when unknown or not permitted. */
    fun nameFor(number: String): String {
        if (!hasPermission() || number.isEmpty()) return ""
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
        }.getOrNull().orEmpty().take(MAX_NAME)
    }

    private fun readAll(): List<Contact> {
        val byId = LinkedHashMap<Long, Pair<String, MutableList<String>>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(2)?.trim().orEmpty().take(MAX_NUMBER)
                    if (number.isEmpty()) continue
                    val entry = byId.getOrPut(cursor.getLong(0)) {
                        cursor.getString(1).orEmpty().filterNot { it.isISOControl() }.take(MAX_NAME) to
                            mutableListOf()
                    }
                    if (entry.second.size < MAX_NUMBERS && number !in entry.second) entry.second.add(number)
                }
            }
        }.onFailure { Log.w(TAG, "contacts query failed: ${it.javaClass.simpleName}") }
        return byId.values
            .sortedBy { it.first.lowercase() }
            .map { (name, numbers) -> Contact.newBuilder().setName(name).addAllNumbers(numbers).build() }
    }

    private companion object {
        const val TAG = "ContactsReader"
        const val MAX_NAME = 128
        const val MAX_NUMBER = 32
        const val MAX_NUMBERS = 5
    }
}

/**
 * Places and drives calls on the phone's own SIM for the paired owner, and reports the call state.
 * The call audio never leaves the phone: Android gives third-party apps no way to capture or inject
 * it. Placing a call does not unlock the device. Every operation needs a runtime grant the owner
 * gave on the phone; without it the controller is told with a machine-readable reason.
 */
@Suppress("TooManyFunctions", "DEPRECATION")
class CallController(
    private val context: Context,
    private val contacts: ContactsReader,
    private val onState: (CallState) -> Unit,
) {
    private val telecom = context.getSystemService<TelecomManager>()
    private val telephony = context.getSystemService<TelephonyManager>()
    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null
    private var speaker = false
    private var lastNumber = ""
    private var phase = CallState.State.STATE_IDLE

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Starts listening to the phone's call state; a no-op without READ_PHONE_STATE. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun start() {
        val manager = telephony ?: return
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return
        if (modernCallback != null || legacyListener != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = changed(state)
            }
            runCatching { manager.registerTelephonyCallback(context.mainExecutor, callback) }
                .onSuccess { modernCallback = callback }
        } else {
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = changed(state)
            }
            manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            legacyListener = listener
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let { runCatching { telephony?.unregisterTelephonyCallback(it) } }
        } else {
            legacyListener?.let { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        modernCallback = null
        legacyListener = null
    }

    private fun changed(state: Int) {
        phase = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallState.State.STATE_RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.State.STATE_ACTIVE
            else -> CallState.State.STATE_IDLE
        }
        if (phase == CallState.State.STATE_IDLE) {
            speaker = false
            lastNumber = ""
        }
        report(null)
    }

    /** Applies [control]; failures are reported as a [CallState] carrying a reason code. */
    fun handle(control: CallControl) {
        when (control.action) {
            CallControl.Action.ACTION_DIAL -> dial(control.number)
            CallControl.Action.ACTION_ANSWER -> answer()
            CallControl.Action.ACTION_HANGUP -> hangup()
            CallControl.Action.ACTION_SPEAKER_ON -> setSpeaker(true)
            CallControl.Action.ACTION_SPEAKER_OFF -> setSpeaker(false)
            else -> report("failed")
        }
    }

    @Suppress("MissingPermission")
    private fun dial(number: String) {
        if (!number.matches(DIALABLE) || number.length > MAX_NUMBER) return report("bad_number")
        if (!granted(Manifest.permission.CALL_PHONE)) return report("call_permission_denied")
        val manager = telecom ?: return report("unsupported")
        start()
        lastNumber = number
        runCatching { manager.placeCall(Uri.fromParts("tel", number, null), null) }
            .onSuccess {
                phase = CallState.State.STATE_DIALING
                report(null)
            }
            .onFailure {
                Log.w(TAG, "placeCall failed: ${it.javaClass.simpleName}")
                lastNumber = ""
                report("failed")
            }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun answer() {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return report("call_permission_denied")
        if (phase != CallState.State.STATE_RINGING) return report("no_call")
        runCatching { telecom?.acceptRingingCall() }.onFailure { report("failed") }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun hangup() {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return report("call_permission_denied")
        if (phase == CallState.State.STATE_IDLE) return report("no_call")
        val ended = runCatching { telecom?.endCall() }.getOrNull() == true
        if (!ended) report("failed")
    }

    /**
     * Toggles the speakerphone by pressing the in-call screen's own button through the accessibility
     * service: Android lets only the default dialer reroute call audio directly.
     */
    private fun setSpeaker(on: Boolean) {
        if (phase == CallState.State.STATE_IDLE) return report("no_call")
        if (speaker == on) return report(null)
        val service = RemoteInputService.instance ?: return report("unsupported")
        if (service.pressInCallControl(SPEAKER_LABELS)) {
            speaker = on
            report(null)
        } else {
            report("unsupported")
        }
    }

    private fun report(error: String?) {
        val builder = CallState.newBuilder()
            .setState(phase)
            .setNumber(lastNumber)
            .setName(contacts.nameFor(lastNumber))
            .setSpeaker(speaker)
            .setAtMs(System.currentTimeMillis())
        if (error != null) builder.setError(error)
        onState(builder.build())
    }

    private companion object {
        const val TAG = "CallController"
        const val MAX_NUMBER = 32
        val DIALABLE = Regex("[0-9+*#]+")
        val SPEAKER_LABELS = listOf("speaker", "haut-parleur", "haut parleur")
    }
}
