package id.pineapple.notifysync

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

class NLService: NotificationListenerService() {
	override fun onCreate() {
		super.onCreate()
		instance = this
		activeNotifications?.forEach {
			onNotificationPosted(it)
		}
	}
	
	override fun onDestroy() {
		instance = null
		super.onDestroy()
	}
	
	override fun onNotificationPosted(sbn: StatusBarNotification) {
		if (
			sbn.notification.flags and NotificationCompat.FLAG_FOREGROUND_SERVICE != 0 ||
			sbn.notification.flags and NotificationCompat.FLAG_ONGOING_EVENT != 0 ||
			sbn.notification.flags and NotificationCompat.FLAG_LOCAL_ONLY != 0 ||
			sbn.notification.flags and NotificationCompat.FLAG_GROUP_SUMMARY != 0
		) {
			return
		}
		if (sbn.packageName == packageName) return
		val key = extractNotificationKey(sbn)
		val title = extractNotificationTitle(sbn)
		val text = extractNotificationText(sbn) ?: return
		val appName = applicationContext.packageManager.let { packageManager ->
			packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
		}
		/* val timeOffset = GregorianCalendar().let { calendar ->
			val timeZone = calendar.timeZone
			timeZone.rawOffset + if (timeZone.inDaylightTime(Date())) timeZone.dstSavings else 0
		} */
		val item = synchronized(Companion) {
			val oldItem = notificationsMap[key]?.let { notificationsSet.remove(it); it }
			val item = NotificationItem(
				key,
				sbn.notification.`when`,
				appName,
				title,
				text,
				oldItem?.icon ?: extractNotificationIcon(sbn),
				extractNotificationActions(sbn),
				sbn
			)
			notificationsMap[item.key] = item
			notificationsSet.add(item)
			Log.i(this::class.java.simpleName, "Notification posted (${notificationsSet.size})")
			item
		}
		listeners.forEach {
			it.onNotificationPosted(item)
		}
	}
	
	override fun onNotificationRemoved(sbn: StatusBarNotification) {
		val item = synchronized(Companion) {
			notificationsMap.remove(extractNotificationKey(sbn))?.let { item ->
				notificationsSet.remove(item)
				Log.i(this::class.java.simpleName, "Notification removed (${notificationsSet.size})")
				item
			}
		}
		if (item != null) {
			listeners.forEach {
				it.onNotificationRemoved(item)
			}
		}
	}
	
	private fun extractNotificationKey(sbn: StatusBarNotification): String =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && sbn.key != null)
			sbn.key
		else
			"${sbn.packageName}|${sbn.id}|${sbn.tag ?: ""}"
	
	private fun extractNotificationTitle(sbn: StatusBarNotification): String =
		if (sbn.notification.extras.containsKey(NotificationCompat.EXTRA_CONVERSATION_TITLE))
			sbn.notification.extras.get(NotificationCompat.EXTRA_CONVERSATION_TITLE)?.toString() ?: ""
		else
			sbn.notification.extras.get(NotificationCompat.EXTRA_TITLE)?.toString() ?: ""
	
	private fun extractNotificationText(sbn: StatusBarNotification): String? =
		when {
			sbn.notification.extras.containsKey(NotificationCompat.EXTRA_MESSAGES) -> {
				sbn.notification.extras.getParcelableArray(NotificationCompat.EXTRA_MESSAGES)?.joinToString("\n") {
					val bundle = it as Bundle
					val sender = bundle.getString("sender", "")
					val text = bundle.getString("text", "")
					if (sender.isNotEmpty()) "$sender: $text" else text
				}
			}
			sbn.notification.extras.containsKey(NotificationCompat.EXTRA_BIG_TEXT) ->
				sbn.notification.extras.get(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
			else -> sbn.notification.extras.get(NotificationCompat.EXTRA_TEXT)?.toString()
		}
	
	private fun extractNotificationIcon(sbn: StatusBarNotification): Bitmap? =
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && sbn.notification.getLargeIcon() != null)
				iconToBitmap(createPackageContext(sbn.packageName, 0), sbn.notification.getLargeIcon())
			else if (sbn.notification.largeIcon != null)
				sbn.notification.largeIcon
			else
				drawableToBitmap(packageManager.getResourcesForApplication(sbn.packageName).getDrawable(sbn.notification.icon))
		} catch (e: PackageManager.NameNotFoundException) {
			null
		} catch (e: Resources.NotFoundException) {
			null
		}
	
	private fun extractNotificationActions(sbn: StatusBarNotification): List<NotificationItem.Action> =
		sbn.notification.actions?.mapIndexed { index, action ->
			index to action
		}?.filter {
			it.second.title != null
		}?.map {
			NotificationItem.Action(
				it.first,
				it.second.title.toString(),
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH)
					it.second.remoteInputs != null && it.second.remoteInputs.isNotEmpty()
				else
					false
			)
		} ?: emptyList()
	
	@RequiresApi(Build.VERSION_CODES.M)
	private fun iconToBitmap(context: Context, icon: Icon): Bitmap = drawableToBitmap(icon.loadDrawable(context))
	
	private fun drawableToBitmap(drawable: Drawable): Bitmap {
		val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		drawable.setBounds(0, 0, bitmap.width, bitmap.height)
		drawable.draw(canvas)
		return bitmap
	}
	
	interface OnUpdateListener {
		fun onNotificationPosted(item: NotificationItem)
		fun onNotificationRemoved(item: NotificationItem)
	}
	
	companion object {
		var instance: NLService? = null
			private set
		private val notificationsMap = mutableMapOf<String, NotificationItem>()
		private val notificationsSet = sortedSetOf<NotificationItem>(Comparator { a, b ->
			b.timestamp.compareTo(a.timestamp)
		})
		private val listeners = mutableListOf<OnUpdateListener>()
		val notifications: Collection<NotificationItem> = notificationsSet
		
		@JvmStatic
		fun addListener(listener: OnUpdateListener) {
			listeners.add(listener)
		}
		
		@JvmStatic
		fun removeListener(listener: OnUpdateListener) {
			listeners.remove(listener)
		}
		
		fun findNotificationByKey(key: String): NotificationItem? = notificationsMap[key]
	}
}
