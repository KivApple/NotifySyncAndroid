package id.pineapple.notifysync.plugins

import android.content.Context
import com.google.gson.JsonObject
import id.pineapple.notifysync.net.RemoteDevice

interface BasePlugin {
	fun init(context: Context) {
	}
	
	fun destroy() {
	}
	
	fun start(conn: RemoteDevice.ConnectionHandler) {
	}
	
	fun handleData(conn: RemoteDevice.ConnectionHandler, type: String, data: JsonObject): Boolean {
		return false
	}
}
