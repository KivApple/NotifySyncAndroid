package id.pineapple.notifysync.plugins

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import id.pineapple.notifysync.NLService
import id.pineapple.notifysync.NotificationItem
import id.pineapple.notifysync.net.BaseNotification
import id.pineapple.notifysync.net.ProtocolServer
import id.pineapple.notifysync.net.RemoteDevice
import java.io.ByteArrayOutputStream

class NotificationListPlugin: BasePlugin, NLService.OnUpdateListener {
	private lateinit var context: Context
	
	override fun init(context: Context) {
		this.context = context
		NLService.addListener(this)
	}
	
	override fun destroy() {
		NLService.removeListener(this)
	}
	
	@Synchronized
	override fun start(conn: RemoteDevice.ConnectionHandler) {
		Handler(Looper.getMainLooper()).post {
			val notifications = NLService.notifications.map {
				PostedNotificationInfo(it)
			}
			Log.v(this::class.java.simpleName, "Sending ${notifications.size} notifications to new client")
			conn.sendNotification(*notifications.toTypedArray())
		}
	}
	
	override fun handleData(conn: RemoteDevice.ConnectionHandler, type: String, data: JsonObject): Boolean {
		if (type != "notification") return false
		val key = data["key"].asString
		if (data.has("actionIndex")) {
			val actionIndex = data["actionIndex"].asInt
			val actionTextJson = data["actionText"]
			val actionText = if (!actionTextJson.isJsonNull) actionTextJson.asString else null
			Handler(Looper.getMainLooper()).post {
				val notification = NLService.findNotificationByKey(key)
				if (notification != null) {
					val sbn = notification.originalNotification
					val actions = sbn.notification.actions ?: emptyArray()
					if (actionIndex < actions.size) {
						val action = actions[actionIndex]
						val intent = action.actionIntent
						if (intent != null) {
							if (actionText == null) {
								try {
									intent.send()
								} catch (e: PendingIntent.CanceledException) {
								}
							} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
								val localIntent = Intent()
								localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
								val localBundle = Bundle()
								action.remoteInputs.forEach { remoteInput ->
									localBundle.putCharSequence(remoteInput.resultKey, actionText)
								}
								RemoteInput.addResultsToIntent(action.remoteInputs, localIntent, localBundle)
								try {
									intent.send(context, 0, localIntent)
								} catch (e: PendingIntent.CanceledException) {
								}
							}
						}
					}
				}
			}
		} else {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				NLService.instance?.cancelNotification(key)
			} else {
				val parts = key.split("|", limit = 3)
				if (parts.size == 3) {
					val packageName = parts[0]
					val id = parts[1].toInt()
					val tag = if (parts[2].isNotEmpty()) parts[2] else null
					NLService.instance?.cancelNotification(packageName, tag, id)
				}
			}
		}
		return true
	}
	
	@Synchronized
	override fun onNotificationPosted(item: NotificationItem) {
		ProtocolServer.instance?.sendNotification(PostedNotificationInfo(item))
	}
	
	@Synchronized
	override fun onNotificationRemoved(item: NotificationItem) {
		ProtocolServer.instance?.sendNotification(RemovedNotificationInfo(item))
	}
	
	open class NotificationInfo(
		val action: String,
		val key: String
	): BaseNotification("notification")
	
	class PostedNotificationInfo(item: NotificationItem): NotificationInfo("posted", item.key) {
		val timestamp = item.timestamp
		val appName = item.appName.take(128)
		val title = item.title.take(128)
		val message = item.message.take(2048)
		val icon = item.icon?.let { icon ->
			ByteArrayOutputStream().use { stream ->
				icon.compress(Bitmap.CompressFormat.PNG, 100, stream)
				Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
			}
		}
		val actions = item.actions.take(4).map { Action(it) }
		
		class Action(action: NotificationItem.Action) {
			val index = action.index
			val title = action.title.take(128)
			val isTextInput = action.isTextInput
		}
	}
	
	class RemovedNotificationInfo(item: NotificationItem): NotificationInfo("removed", item.key)
}
