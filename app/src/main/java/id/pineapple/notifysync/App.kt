package id.pineapple.notifysync

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceManager
import id.pineapple.notifysync.net.ProtocolServer
import id.pineapple.notifysync.plugins.BatteryStatusPlugin
import id.pineapple.notifysync.plugins.NotificationListPlugin

class App: Application() {
	private lateinit var preferences: SharedPreferences
	private lateinit var protocolServer: ProtocolServer
	
	override fun onCreate() {
		super.onCreate()
		preferences = PreferenceManager.getDefaultSharedPreferences(this)
		protocolServer = ProtocolServer(
			preferences.getString("device_name", "NoName")!!,
			preferences.getStringSet("paired_devices", emptySet())!!.map {
				val parts = it.split(':', limit = 2)
				Pair(Base64.decode(parts[0], Base64.DEFAULT), parts[1])
			}
		)
		protocolServer.registerPlugin(this, BatteryStatusPlugin())
		protocolServer.registerPlugin(this, NotificationListPlugin())
	}
	
	override fun onTerminate() {
		protocolServer.shutdown()
		super.onTerminate()
	}
}
