package id.pineapple.notifysync.plugins

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import id.pineapple.notifysync.R
import id.pineapple.notifysync.net.BaseNotification
import id.pineapple.notifysync.net.ProtocolServer
import id.pineapple.notifysync.net.RemoteDevice
import java.io.File
import java.io.IOException
import java.io.OutputStream

class FileReceiverPlugin: BasePlugin {
	private lateinit var context: Context
	private lateinit var notificationManager: NotificationManager
	private val receivers = mutableMapOf<RemoteDevice.Connection, FileReceiver>()
	
	override fun init(context: Context) {
		this.context = context
		notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		createNotificationChannel()
	}
	
	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				NOTIFICATION_CHANNEL,
				context.getString(R.string.file_receiver_notification_channel),
				NotificationManager.IMPORTANCE_LOW
			)
			channel.enableVibration(false)
			channel.setShowBadge(true)
			notificationManager.createNotificationChannel(channel)
		}
	}
	
	@Synchronized
	override fun start(conn: RemoteDevice.Connection) {
		receivers[conn] = FileReceiver(conn)
	}
	
	@Synchronized
	override fun stop(conn: RemoteDevice.Connection) {
		receivers.remove(conn)?.cancelReceiving()
	}
	
	override fun handleData(conn: RemoteDevice.Connection, type: String, data: JsonObject): Boolean {
		if (type != "file") return false
		val receiver = synchronized(this) {
			receivers[conn]
		} ?: return false
		receiver.handleData(data)
		return true
	}
	
	@Synchronized
	fun findReceiverByRemoteDevice(device: RemoteDevice) = receivers.entries.firstOrNull {
		it.key.remoteDevice == device
	}?.value
	
	inner class FileReceiver(private val conn: RemoteDevice.Connection) {
		private var currentFileName: String? = null
		private var outputFile: File? = null
		private var outputStream: OutputStream? = null
		private var notificationId: Int = -1
		private var totalBytes: Long = 0
		private var receivedBytes: Long = 0
		private var currentProgress: Int = 0
		
		@Synchronized
		private fun startReceiving(fileName: String, totalBytes: Long) {
			if (currentFileName != null) {
				cancelReceiving()
			}
			Log.i(this::class.java.simpleName, "Start receiving \"$fileName\" ($totalBytes bytes)")
			currentFileName = fileName
			this.totalBytes = totalBytes
			receivedBytes = 0
			notifyStartReceiving()
			var safeFileName = fileName.filter {
				it != '\\' && it != '/'
			}
			if (safeFileName.startsWith(".")) {
				safeFileName = "_$safeFileName"
			}
			try {
				val destinationDirectory =
					Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
				if (File("$destinationDirectory/$safeFileName").exists()) {
					var counter = 1
					do {
						counter++
						val dot = safeFileName.lastIndexOf('.')
						val name = if (dot >= 0) safeFileName.substring(0, dot) else safeFileName
						val ext = if (dot >= 0) safeFileName.substring(dot) else ""
						safeFileName = "$name ($counter)$ext"
					} while (File("$destinationDirectory/$safeFileName").exists())
				}
				outputFile = File("$destinationDirectory/$safeFileName")
				outputStream = outputFile?.outputStream()
			} catch (e: IOException) {
			}
		}
		
		@Synchronized
		private fun receiveChunk(chunk: ByteArray) {
			if (currentFileName == null) return
			receivedBytes += chunk.size
			Log.i(this::class.java.simpleName, "Received ${chunk.size} bytes ($receivedBytes/$totalBytes) of file \"$currentFileName\" from ${conn.remoteDevice.name}")
			try {
				outputStream?.write(chunk)
			} catch (e: IOException) {
			}
			notifyChunkReceived()
		}
		
		@Synchronized
		private fun finishReceiving() {
			if (currentFileName == null) return
			Log.i(this::class.java.simpleName, "Finish receiving file \"$currentFileName\"")
			outputStream?.close()
			outputStream = null
			notifyFinishReceiving()
			outputFile = null
			currentFileName = null
		}
		
		@Synchronized
		fun cancelReceiving() {
			if (currentFileName == null) return
			Log.i(this::class.java.simpleName, "Cancel receiving file \"$currentFileName\"")
			outputStream?.close()
			try {
				outputFile?.delete()
			} catch (e: IOException) {
			}
			outputStream = null
			outputFile = null
			notifyCancelReceiving()
			currentFileName = null
		}
		
		fun requestCancelSending() {
			conn.sendNotification(BaseNotification("cancel-file"))
		}
		
		fun handleData(data: JsonObject) {
			when {
				data.has("name") -> startReceiving(data["name"].asString, data["size"].asLong)
				data.has("chunk") -> receiveChunk(Base64.decode(data["chunk"].asString, Base64.DEFAULT))
				data.has("status") -> when (data["status"].asString) {
					"complete" -> finishReceiving()
					"cancel" -> cancelReceiving()
				}
			}
		}
		
		private fun notifyStartReceiving() {
			if (notificationId >= 0) {
				notifyCancelReceiving()
			}
			notificationId = makeNotificationId()
			currentProgress = -1
			notifyChunkReceived()
		}
		
		private fun notifyChunkReceived() {
			val newProgress = if (totalBytes > 0) (receivedBytes * 100 / totalBytes).toInt() else 0
			if (newProgress == currentProgress) return
			currentProgress = newProgress
			val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
				.setSmallIcon(R.drawable.ic_file_download_black_24dp)
				.setContentTitle(currentFileName)
				.setContentText(conn.remoteDevice.name)
				.setOngoing(true)
				.setProgress(100, currentProgress, false)
				.addAction(0, context.getString(R.string.cancel), PendingIntent.getBroadcast(
					context,
					notificationId,
					Intent(context, BroadcastReceiver::class.java).apply {
						putExtra("device_id", conn.remoteDevice.id)
					},
					PendingIntent.FLAG_UPDATE_CURRENT
				))
				.build()
			notificationManager.notify(notificationId, notification)
		}
		
		private fun notifyFinishReceiving() {
			if (notificationId < 0) return
			val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(outputFile?.extension)
			val fileProviderAuthority = "${context.applicationContext.packageName}.GenericFileProvider"
			val fileUri = FileProvider.getUriForFile(context, fileProviderAuthority, outputFile!!)
			val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
				.setSmallIcon(R.drawable.ic_file_download_black_24dp)
				.setContentTitle(currentFileName)
				.setContentText(context.getString(R.string.file_received_from, conn.remoteDevice.name))
				.setContentIntent(
					PendingIntent.getActivity(
						context,
						notificationId,
						Intent(Intent.ACTION_VIEW).apply {
							setDataAndType(fileUri, mimeType)
							addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
						},
						PendingIntent.FLAG_CANCEL_CURRENT
					)
				)
				.build()
			notificationManager.notify(notificationId, notification)
			notificationId = -1
		}
		
		private fun notifyCancelReceiving() {
			if (notificationId < 0) return
			notificationManager.cancel(notificationId)
			notificationId = -1
		}
	}
	
	class BroadcastReceiver: android.content.BroadcastReceiver() {
		override fun onReceive(content: Context, intent: Intent) {
			val deviceId = intent.getStringExtra("device_id")!!
			val device = ProtocolServer.instance?.findPairedDeviceById(deviceId) ?: return
			val plugin = ProtocolServer.instance?.findPluginByClass(FileReceiverPlugin::class.java) ?: return
			val receiver = plugin.findReceiverByRemoteDevice(device)
			receiver?.requestCancelSending()
		}
	}
	
	companion object {
		private const val NOTIFICATION_CHANNEL = "file-receiver"
		private const val NOTIFICATION_ID_BASE = 1000
		private const val NOTIFICATION_ID_MAX = 1999
		
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
