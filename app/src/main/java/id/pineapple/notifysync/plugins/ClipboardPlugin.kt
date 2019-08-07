package id.pineapple.notifysync.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import id.pineapple.notifysync.net.BaseNotification
import id.pineapple.notifysync.net.ProtocolServer
import id.pineapple.notifysync.net.RemoteDevice
import java.util.*

class ClipboardPlugin: BasePlugin, ClipboardManager.OnPrimaryClipChangedListener {
	private lateinit var clipboardManager: ClipboardManager
	private var lastNotification: ClipboardNotification? = null
	
	override fun init(context: Context) {
		clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		clipboardManager.addPrimaryClipChangedListener(this)
	}
	
	override fun start(conn: RemoteDevice.Connection) {
		val lastNotification = synchronized(this) {
			lastNotification
		}
		if (lastNotification != null) {
			conn.sendPacket(lastNotification)
		}
	}
	
	override fun handleData(conn: RemoteDevice.Connection, type: String, data: JsonObject): Boolean {
		if (type != "clipboard") return false
		val timestamp = data.get("timestamp").asLong
		val text = data.get("text").asString
		synchronized(this) {
			if (lastNotification != null && lastNotification!!.timestamp > timestamp) {
				return true
			}
			lastNotification = ClipboardNotification(text, timestamp)
		}
		Handler(Looper.getMainLooper()).post {
			clipboardManager.setPrimaryClip(ClipData.newPlainText("text", text))
		}
		return true
	}
	
	override fun onPrimaryClipChanged() {
		val text = clipboardManager.text.toString()
		val notification = ClipboardNotification(text, Date())
		synchronized(this) {
			if (text == lastNotification?.text) {
				return
			}
			lastNotification = notification
		}
		ProtocolServer.instance?.sendNotification(notification)
	}
	
	class ClipboardNotification(
		text: String,
		val timestamp: Long
	): BaseNotification("clipboard") {
		val text = text.take(5120)
		
		constructor(text: String, timestamp: Date): this(text, timestamp.time)
	}
}
