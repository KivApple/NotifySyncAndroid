package id.pineapple.notifysync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.preference.PreferenceManager

class Utils {
	companion object {
		@JvmStatic
		fun openNotificationAccessSettings(context: Context) {
			val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
				Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
			else
				"android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
			context.startActivity(Intent(action))
			PreferenceManager.getDefaultSharedPreferences(context)
				.edit()
				.putBoolean("notification_access_settings_accessed", true)
				.apply()
		}
		
		@JvmStatic
		fun isNotificationAccessEnabled(context: Context): Boolean =
			PreferenceManager.getDefaultSharedPreferences(context).getBoolean("notification_access_settings_accessed", false)
	}
}

fun ByteArray.toHexString(): String = joinToString("") {
	String.format("%02x", it)
}
