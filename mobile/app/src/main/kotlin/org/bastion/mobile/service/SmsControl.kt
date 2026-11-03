package org.bastion.mobile.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.bastion.protocol.v1.SmsMessage
import org.bastion.protocol.v1.SmsPage

/**
 * Sends and reads text messages for the paired owner, over the phone's own SIM. Needs the owner's
 * SEND_SMS / READ_SMS grants; without them every call reports it instead of failing silently.
 * Nothing is cached: messages are read from the system provider on each request.
 */
class SmsController(private val context: Context, private val contacts: ContactsReader) {
    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canSend(): Boolean = granted(Manifest.permission.SEND_SMS)

    fun canRead(): Boolean = granted(Manifest.permission.READ_SMS)

    /** Sends [body] to [to]. Returns null on success, else a machine-readable reason. */
    @Suppress("ReturnCount")
    fun send(to: String, body: String): String? {
        if (!canSend()) return "sms_permission_denied"
        if (!to.matches(DIALABLE) || to.length > MAX_NUMBER || body.isBlank() || body.length > MAX_BODY) {
            return "bad_number"
        }
        val manager = context.getSystemService(SmsManager::class.java) ?: return "unsupported"
        return runCatching {
            val parts = manager.divideMessage(body)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(to, null, parts, null, null)
            } else {
                manager.sendTextMessage(to, null, body, null, null)
            }
            null
        }.getOrElse {
            Log.w(TAG, "sms send failed: ${it.javaClass.simpleName}")
            "failed"
        }
    }

    /** Latest message of each conversation ([peer] empty) or the thread with [peer]; null without READ_SMS. */
    fun page(peer: String, limit: Int): SmsPage? {
        if (!canRead()) return null
        val wanted = digits(peer)
        val rows = readRecent()
        val chosen = if (wanted.isEmpty()) {
            val seen = HashSet<String>()
            rows.filter { seen.add(key(it.address)) }
        } else {
            rows.filter { key(it.address) == key(wanted) }
        }.take(limit)
        return SmsPage.newBuilder()
            .setPeer(peer)
            .addAllMessages(chosen.map { it.toProto() })
            .build()
    }

    /** A message that just arrived, for the live event. */
    fun incoming(address: String, body: String, atMs: Long): SmsMessage =
        Row(address, body, atMs, outgoing = false).toProto()

    private class Row(val address: String, val body: String, val atMs: Long, val outgoing: Boolean)

    private fun Row.toProto(): SmsMessage = SmsMessage.newBuilder()
        .setAddress(address.take(MAX_NUMBER))
        .setName(contacts.nameFor(address))
        .setBody(body.filterNot { it.isISOControl() && it != '\n' }.take(MAX_BODY))
        .setAtMs(atMs)
        .setOutgoing(outgoing)
        .build()

    private fun readRecent(): List<Row> {
        val rows = ArrayList<Row>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < MAX_SCAN) {
                    val address = cursor.getString(0).orEmpty()
                    if (address.isEmpty()) continue
                    rows.add(
                        Row(
                            address,
                            cursor.getString(1).orEmpty(),
                            cursor.getLong(2),
                            outgoing = cursor.getInt(3) != Telephony.Sms.MESSAGE_TYPE_INBOX,
                        ),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "sms query failed: ${it.javaClass.simpleName}") }
        return rows
    }

    private fun digits(value: String) = value.filter { it.isDigit() }

    // Numbers are compared on their last digits so "+33 6 12…" and "06 12…" match the same peer.
    private fun key(value: String) = digits(value).takeLast(KEY_DIGITS)

    private companion object {
        const val TAG = "SmsController"
        const val MAX_NUMBER = 32
        const val MAX_BODY = 1000
        const val MAX_SCAN = 6000
        const val KEY_DIGITS = 9
        val DIALABLE = Regex("[0-9+*#]+")
    }
}
