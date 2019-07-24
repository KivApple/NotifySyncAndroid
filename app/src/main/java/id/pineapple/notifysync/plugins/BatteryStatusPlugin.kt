package id.pineapple.notifysync.plugins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import id.pineapple.notifysync.net.BaseNotification
import id.pineapple.notifysync.net.ProtocolServer
import id.pineapple.notifysync.net.RemoteDevice

class BatteryStatusPlugin: BroadcastReceiver(), BasePlugin {
	override fun onReceive(context: Context, intent: Intent) {
		currentLevel = (intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).toFloat() /
				intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) * 100).toInt()
		charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { status ->
			status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
		}
		ProtocolServer.instance?.sendNotification(makeNotification())
	}
	
	override fun init(context: Context) {
		val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
		onReceive(context, context.registerReceiver(null, filter)!!)
		context.registerReceiver(this, filter)
	}
	
	override fun start(conn: RemoteDevice.Connection) {
		conn.sendNotification(makeNotification())
	}
	
	private fun makeNotification(): BatteryNotification = BatteryNotification(currentLevel, charging)
	
	class BatteryNotification(
		val level: Int,
		val charging: Boolean
	): BaseNotification("battery")
	
	companion object {
		private var currentLevel = 100
		private var charging = false
	}
}
