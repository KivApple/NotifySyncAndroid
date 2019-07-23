package id.pineapple.notifysync

import android.graphics.Bitmap
import android.service.notification.StatusBarNotification

data class NotificationItem(
	val key: String,
	val timestamp: Long,
	val appName: String,
	val title: String,
	val message: String,
	val icon: Bitmap?,
	val actions: List<Action>,
	val originalNotification: StatusBarNotification
) {
	data class Action(
		val index: Int,
		val title: String,
		val isTextInput: Boolean
	)
}
