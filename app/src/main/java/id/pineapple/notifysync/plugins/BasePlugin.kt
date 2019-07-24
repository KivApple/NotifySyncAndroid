package id.pineapple.notifysync.plugins

import android.content.Context
import com.google.gson.JsonObject
import id.pineapple.notifysync.net.RemoteDevice

interface BasePlugin {
	fun init(context: Context) {
	}
	
	fun destroy() {
	}
	
	fun start(conn: RemoteDevice.Connection) {
	}
	
	fun stop(conn: RemoteDevice.Connection) {
	}
	
	fun handleData(conn: RemoteDevice.Connection, type: String, data: JsonObject): Boolean {
		return false
	}
}
