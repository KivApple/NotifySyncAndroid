package id.pineapple.notifysync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager

class MyBroadcastReceiver: BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			Intent.ACTION_PACKAGE_REPLACED ->
				if (context.packageName == intent.data?.schemeSpecificPart) {
					BackgroundService.networkChanged(context, false)
				}
			Intent.ACTION_BOOT_COMPLETED,
			WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION, WifiManager.WIFI_STATE_CHANGED_ACTION,
			ConnectivityManager.CONNECTIVITY_ACTION ->
				BackgroundService.networkChanged(context, true)
			Intent.ACTION_SCREEN_ON ->
				BackgroundService.networkChanged(context, false)
		}
	}
}
