package id.pineapple.notifysync.plugins

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.service.chooser.ChooserTarget
import android.service.chooser.ChooserTargetService
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject
import id.pineapple.notifysync.DashboardAdapter
import id.pineapple.notifysync.R
import id.pineapple.notifysync.net.BaseNotification
import id.pineapple.notifysync.net.ProtocolServer
import id.pineapple.notifysync.net.RemoteDevice
import kotlinx.android.synthetic.main.activity_device_picker.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.Exception
import java.util.*

class SharePlugin : BasePlugin {
	private lateinit var context: Context
	private lateinit var notificationManager: NotificationManager
	private var lastSenderThreadId: Int = 0
	private val senderThreads = mutableListOf<FileSenderThread>()
	
	override fun init(context: Context) {
		this.context = context
		notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		createNotificationChannel()
	}
	
	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				NOTIFICATION_CHANNEL,
				context.getString(R.string.file_sender_notification_channel),
				NotificationManager.IMPORTANCE_LOW
			)
			channel.enableVibration(false)
			channel.setShowBadge(true)
			notificationManager.createNotificationChannel(channel)
		}
	}
	
	override fun handleData(conn: RemoteDevice.Connection, type: String, data: JsonObject): Boolean {
		if (type == "cancel-file") {
			synchronized(this) {
				senderThreads.filter {
					it.remoteDevice == conn.remoteDevice
				}.forEach {
					it.interrupt()
				}
			}
			return true
		}
		return false
	}
	
	fun share(context: Context, remoteDevice: RemoteDevice, intent: Intent) {
		if (intent.hasExtra(Intent.EXTRA_STREAM)) {
			val uriList: List<Uri> = try {
				if (intent.action != Intent.ACTION_SEND)
					intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
				else
					listOf(intent.getParcelableExtra(Intent.EXTRA_STREAM))
			} catch (e: Throwable) {
				Log.e(this::class.java.simpleName, "Failed to extract URI list from intent", e)
				return
			}
			sendUriList(context, remoteDevice, uriList)
		} else if (intent.hasExtra(Intent.EXTRA_TEXT)) {
			val text = intent.getStringExtra(Intent.EXTRA_TEXT)!!
			remoteDevice.connection?.sendNotification(ClipboardPlugin.ClipboardNotification(text, Date()))
		}
	}
	
	private fun sendUriList(context: Context, remoteDevice: RemoteDevice, uriList: List<Uri>) {
		FileSenderThread(
			remoteDevice,
			uriList.mapNotNull { uri ->
				try {
					var fileName = "NoName.bin"
					var size: Long = -1
					if (uri.scheme == "file") {
						val file = File(uri.path!!)
						fileName = file.name
						size = file.length()
					} else {
						context.contentResolver.query(
							uri,
							arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
							null,
							null,
							null
						).use { cursor ->
							val nameColumnIndex = cursor!!.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
							val sizeColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
							cursor.moveToFirst()
							fileName = cursor.getString(nameColumnIndex)
							if (!cursor.isNull(sizeColumnIndex)) {
								size = cursor.getInt(sizeColumnIndex).toLong()
							}
						}
					}
					FileStreamInfo(
						fileName,
						context.contentResolver.openInputStream(uri)!!,
						size
					)
				} catch (e: Exception) {
					Log.e(this::class.java.simpleName, "Failed to fetch information and open $uri", e)
					null
				}
			}
		).start()
	}
	
	fun findSenderThreadById(id: Int): FileSenderThread? = senderThreads.firstOrNull { it.id == id }
	
	data class FileStreamInfo(
		val name: String,
		val inputStream: InputStream,
		val size: Long
	)
	
	inner class FileSenderThread(
		val remoteDevice: RemoteDevice,
		private val files: List<FileStreamInfo>
	) : Thread() {
		val id = synchronized(this@SharePlugin) {
			lastSenderThreadId++
		}
		private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		private var notificationId: Int = -1
		private var sentBytes: Long = 0
		private var totalBytes: Long = 0
		private var currentProgress: Int = 0
		
		override fun run() {
			synchronized(this@SharePlugin) {
				senderThreads.add(this)
			}
			try {
				val connection = remoteDevice.connection ?: return
				files.forEach { file ->
					sendFile(connection, file)
				}
			} catch (e: Throwable) {
				Log.e(this::class.java.simpleName, "Failed to send files", e)
			} finally {
				synchronized(this@SharePlugin) {
					senderThreads.removeAll { this == it }
				}
				files.forEach {
					try {
						it.inputStream.close()
					} catch (e: IOException) {
					}
				}
			}
		}
		
		private fun sendFile(connection: RemoteDevice.Connection, file: FileStreamInfo) {
			val buffer = ByteArray(SEND_BUFFER_SIZE)
			sentBytes = 0
			totalBytes = file.size
			currentProgress = -1
			notificationId = makeNotificationId()
			notifyChunkSent(file.name, connection)
			connection.sendPacket(FileBeginNotification(file.name, file.size))
			try {
				var count: Int
				do {
					count = file.inputStream.read(buffer)
					val chunk = Base64.encodeToString(buffer, 0, count, Base64.DEFAULT)
					connection.sendPacket(FileChunkNotification(chunk))
					sentBytes += count
					notifyChunkSent(file.name, connection)
				} while (count == buffer.size)
				connection.sendPacket(FileEndNotification("complete"))
				notifyFinishSending(file.name, connection)
			} catch (e: Throwable) {
				try {
					notifyCancelSending()
					connection.sendPacket(FileEndNotification("cancel"))
				} catch (e: Throwable) {
				}
				throw e
			}
		}
		
		private fun notifyChunkSent(fileName: String, connection: RemoteDevice.Connection) {
			if (notificationId < 0) return
			val newProgress = if (totalBytes > 0) (sentBytes * 100 / totalBytes).toInt() else 0
			if (newProgress == currentProgress) return
			currentProgress = newProgress
			val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
				.setSmallIcon(R.drawable.ic_file_upload_black_24dp)
				.setContentTitle(fileName)
				.setContentText(connection.remoteDevice.name)
				.setOngoing(true)
				.setProgress(100, currentProgress, totalBytes < 0)
				.addAction(
					0, context.getString(R.string.cancel), PendingIntent.getBroadcast(
						context,
						notificationId,
						Intent(context, BroadcastReceiver::class.java).apply {
							putExtra("thread_id", id)
						},
						PendingIntent.FLAG_UPDATE_CURRENT
					)
				)
				.build()
			notificationManager.notify(notificationId, notification)
		}
		
		private fun notifyFinishSending(fileName: String, connection: RemoteDevice.Connection) {
			if (notificationId < 0) return
			val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
				.setSmallIcon(R.drawable.ic_file_upload_black_24dp)
				.setContentTitle(fileName)
				.setContentText(context.getString(R.string.file_sent_to, connection.remoteDevice.name))
				.build()
			notificationManager.notify(notificationId, notification)
			notificationId = -1
		}
		
		private fun notifyCancelSending() {
			if (notificationId < 0) return
			notificationManager.cancel(notificationId)
			notificationId = -1
		}
	}
	
	class BroadcastReceiver : android.content.BroadcastReceiver() {
		override fun onReceive(content: Context, intent: Intent) {
			val threadId = intent.getIntExtra("thread_id", -1)
			val plugin = ProtocolServer.instance?.findPluginByClass(SharePlugin::class.java) ?: return
			val senderThread = plugin.findSenderThreadById(threadId)
			senderThread?.interrupt()
		}
	}
	
	class FileBeginNotification(
		val name: String,
		val size: Long
	) : BaseNotification("file")
	
	class FileChunkNotification(
		val chunk: String
	) : BaseNotification("file")
	
	class FileEndNotification(
		val status: String
	) : BaseNotification("file")
	
	class DevicePickerActivity : AppCompatActivity(), DashboardAdapter.OnDevicePickedListener {
		private lateinit var adapter: DashboardAdapter
		
		override fun onCreate(savedInstanceState: Bundle?) {
			super.onCreate(savedInstanceState)
			setContentView(R.layout.activity_device_picker)
			adapter = DashboardAdapter(this, devicePickerMode = true)
			adapter.onDevicePickedListener = this
			device_picker_recycler_view.adapter = adapter
			ProtocolServer.instance?.addOnPairedDevicesUpdateListener(adapter)
		}
		
		override fun onDestroy() {
			ProtocolServer.instance?.removeOnPairedDevicesUpdateListener(adapter)
			super.onDestroy()
		}
		
		override fun onStart() {
			super.onStart()
			intent?.let { intent ->
				intent.getStringExtra("device_id")?.let { deviceId ->
					ProtocolServer.instance?.findPairedDeviceById(deviceId)?.let { remoteDevice ->
						share(remoteDevice, intent)
						finish()
					}
				}
			}
			ProtocolServer.instance?.sendBroadcast()
		}
		
		override fun onDevicePicked(remoteDevice: RemoteDevice) {
			if (remoteDevice.isConnected) {
				intent?.let { intent ->
					share(remoteDevice, intent)
					finish()
				}
			} else {
				Toast.makeText(
					this,
					getString(R.string.device_not_connected, remoteDevice.name),
					Toast.LENGTH_SHORT
				).show()
			}
		}
		
		private fun share(remoteDevice: RemoteDevice, intent: Intent) =
			ProtocolServer.instance?.findPluginByClass(SharePlugin::class.java)?.share(this, remoteDevice, intent)
	}
	
	@TargetApi(Build.VERSION_CODES.M)
	class DevicePickerService : ChooserTargetService() {
		override fun onGetChooserTargets(
			targetActivityName: ComponentName,
			matchedFilter: IntentFilter
		): MutableList<ChooserTarget> {
			val deviceIcon = Icon.createWithResource(this, R.drawable.ic_computer_black_24dp)
			return (ProtocolServer.instance?.pairedDevices?.filter {
				it.isConnected
			}?.map { device ->
				ChooserTarget(
					device.name,
					deviceIcon,
					1.0f,
					ComponentName(this, DevicePickerActivity::class.java),
					Bundle().apply {
						putString("device_id", device.id)
					}
				)
			} ?: emptyList()).toMutableList()
		}
	}
	
	companion object {
		private const val NOTIFICATION_CHANNEL = "file-sender"
		private const val NOTIFICATION_ID_BASE = 2000
		private const val NOTIFICATION_ID_MAX = 2999
		
		private const val SEND_BUFFER_SIZE = 20480
		
		private var currentNotificationId = NOTIFICATION_ID_BASE
		
		@Synchronized
		fun makeNotificationId(): Int {
			if (currentNotificationId > NOTIFICATION_ID_MAX) {
				currentNotificationId = NOTIFICATION_ID_BASE
			}
			return currentNotificationId++
		}
	}
}
