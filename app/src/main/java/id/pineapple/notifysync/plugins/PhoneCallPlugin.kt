package id.pineapple.notifysync.plugins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import id.pineapple.notifysync.net.BaseNotification
import id.pineapple.notifysync.net.ProtocolServer

class PhoneCallPlugin: BasePlugin {
	override fun init(context: Context) {
		context.registerReceiver(PhoneCallReceiver(), IntentFilter("android.intent.action.PHONE_STATE"))
	}
	
	class PhoneCallNotification(
		val number: String,
		val displayName: String?
	): BaseNotification("phone-call")
	
	class PhoneCallEndedNotification: BaseNotification("phone-call-ended")
	
	class PhoneCallReceiver: BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
			val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
			if (state == TelephonyManager.EXTRA_STATE_RINGING && number != null) {
				var displayName: String? = null
				try {
					context.contentResolver.query(
						Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
						arrayOf(BaseColumns._ID, ContactsContract.PhoneLookup.DISPLAY_NAME),
						null,
						null,
						null
					)?.use { cursor ->
						if (cursor.count > 0) {
							cursor.moveToNext()
							displayName = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME))
						}
					}
				} catch (e: SecurityException) {
				}
				ProtocolServer.instance?.sendNotification(PhoneCallNotification(number, displayName))
			} else {
				ProtocolServer.instance?.sendNotification(PhoneCallEndedNotification())
			}
		}
	}
}
