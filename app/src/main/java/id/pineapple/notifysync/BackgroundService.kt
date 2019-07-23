package id.pineapple.notifysync

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import id.pineapple.notifysync.net.ProtocolServer

class BackgroundService: Service(), ProtocolServer.OnPairedDevicesUpdateListener {
	private lateinit var preferences: SharedPreferences
	
	override fun onCreate() {
		super.onCreate()
		
		val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
		}
		registerReceiver(MyBroadcastReceiver(), filter)
		
		preferences = PreferenceManager.getDefaultSharedPreferences(this)
		
		ProtocolServer.instance?.addOnPairedDevicesUpdateListener(this)
	}
	
	override fun onDestroy() {
		ProtocolServer.instance?.removeOnPairedDevicesUpdateListener(this)
		stopForeground(true)
		super.onDestroy()
	}
	
	override fun onPairedDevicesUpdate() {
		val newList = LinkedHashSet(synchronized(ProtocolServer.instance!!) { ProtocolServer.instance!!.pairedDevices.map {
			"${Base64.encodeToString(it.key, Base64.DEFAULT)}:${it.name}"
		}})
		if (newList != preferences.getStringSet("paired_devices", emptySet())) {
			preferences.edit()
				.putStringSet("paired_devices", newList)
				.apply()
		}
		val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		notificationManager.notify(BACKGROUND_NOTIFICATION_ID, createNotification())
	}
	
	override fun onBind(intent: Intent): IBinder? = Binder()
	
	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		createNotificationChannel()
		startForeground(BACKGROUND_NOTIFICATION_ID, createNotification())
		return START_STICKY
	}
	
	private fun createNotification(): Notification {
		val notificationBuilder = NotificationCompat.Builder(this, BACKGROUND_NOTIFICATION_CHANNEL)
			.setSmallIcon(R.drawable.ic_devices_black_24dp)
			.setOngoing(true)
			.setPriority(NotificationCompat.PRIORITY_MIN)
			.setShowWhen(false)
			.setAutoCancel(false)
			.setContentIntent(PendingIntent.getActivity(
				this, 0,
				Intent(this, MainActivity::class.java),
				PendingIntent.FLAG_UPDATE_CURRENT
			))
			.setContentText(synchronized(ProtocolServer.instance!!) {
				val connectedDevices = ProtocolServer.instance!!.pairedDevices.filter { it.isConnected }
				if (connectedDevices.isEmpty()) {
					getString(R.string.not_connected_to_any_device)
				} else {
					getString(
						R.string.connected_to,
						connectedDevices.joinToString(", ") { it.name }
					)
				}
			})
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			notificationBuilder.setContentTitle(getString(R.string.app_name))
		}
		return notificationBuilder.build()
	}
	
	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			val channel = NotificationChannel(
				BACKGROUND_NOTIFICATION_CHANNEL,
				getString(R.string.background_notification_channel),
				NotificationManager.IMPORTANCE_MIN
			)
			channel.setShowBadge(false)
			notificationManager.createNotificationChannel(channel)
		}
	}
	
	companion object {
		const val BACKGROUND_NOTIFICATION_CHANNEL = "background"
		const val BACKGROUND_NOTIFICATION_ID = 1
		
		@JvmStatic
		fun start(context: Context) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				context.startForegroundService(Intent(context, BackgroundService::class.java))
			} else {
				context.startService(Intent(context, BackgroundService::class.java))
			}
		}
		
		@JvmStatic
		fun networkChanged(context: Context, really: Boolean) {
			start(context)
			ProtocolServer.instance?.sendBroadcast(really)
		}
	}
}
