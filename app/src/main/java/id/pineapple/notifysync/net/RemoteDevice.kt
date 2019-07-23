package id.pineapple.notifysync.net

import android.os.AsyncTask
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.*

class RemoteDevice(
	val key: ByteArray,
	name: String
) {
	var name: String = name
		private set
	private val broadcastEncoder = NetworkCipher.PacketEncoder(key)
	private val broadcastDecoder = NetworkCipher.PacketDecoder(key)
	var connectionHandler: ConnectionHandler? = null
		private set
	val isConnected: Boolean get() = connectionHandler != null
	var lastSeenIpAddress: InetAddress? = null
		private set
	
	fun sendBroadcast(socket: DatagramSocket, forceReconnect: Boolean) {
		val packetText = listOf(
			Random().nextInt(),
			"NotifySync",
			if (forceReconnect) 1 else 0,
			ProtocolServer.instance?.deviceName
		).joinToString(":")
		val packetData = synchronized(broadcastEncoder) {
			broadcastEncoder.doFinal(packetText.toByteArray())
		}
		try {
			socket.send(
				DatagramPacket(
					packetData, packetData.size,
					ProtocolServer.BROADCAST_ADDRESS, ProtocolServer.UDP_PORT
				)
			)
		} catch (e: IOException) {
			Log.e(this::class.java.simpleName, "Failed to send broadcast for $name", e)
		}
	}
	
	fun sendBroadcast(forceReconnect: Boolean = false) {
		sendBroadcast(forceReconnect, this)
	}
	
	fun acceptBroadcast(encryptedPacket: ByteArray): Boolean {
		try {
			val broadcastStr = String(broadcastDecoder.doFinal(encryptedPacket))
			val data = broadcastStr.split(':', limit = 4)
			if (data.size == 4 && data[1] == "NotifySyncServer" && data[3].isNotEmpty()) {
				Log.i(this::class.java.simpleName, "Accepted broadcast from $name")
				return true
			}
		} catch (e: GeneralSecurityException) {
		}
		return false
	}
	
	fun acceptHandshake(encryptedHandshake: ByteArray, socket: Socket, inputStream: DataInputStream): ConnectionHandler? {
		val decoder = NetworkCipher.PacketDecoder(key)
		try {
			val handshake = String(decoder.doFinal(encryptedHandshake)).split(':', limit = 3)
			if (handshake.size == 3 && handshake[1] == "NotifySync" && handshake[2].isNotEmpty()) {
				name = handshake[2]
				Log.i(this::class.java.simpleName, "Accepted handshake from $name")
				return synchronized(this) {
					connectionHandler?.disconnect()
					connectionHandler = ConnectionHandler(socket, inputStream, decoder)
					connectionHandler
				}
			} else {
				Log.i(this::class.java.simpleName, "Invalid handshake for $name: $handshake")
			}
		} catch (e: GeneralSecurityException) {
			Log.w(this::class.java.simpleName, "Unable to decode handshake for $name: $e")
		}
		return null
	}
	
	fun disconnect() {
		synchronized(this) { connectionHandler }?.disconnect()
	}
	
	class BroadcastAsyncTask(private val forceReconnect: Boolean): AsyncTask<RemoteDevice, Void?, Void?>() {
		override fun doInBackground(vararg devices: RemoteDevice): Void? {
			ProtocolServer.instance?.broadcastSocket?.let { socket ->
				devices.forEach { device ->
					device.sendBroadcast(socket, forceReconnect)
					if (devices.size > 1) {
						Thread.sleep(100)
					}
				}
			}
			return null
		}
	}
	
	inner class ConnectionHandler(
		private val socket: Socket,
		private val inputStream: DataInputStream,
		private val decoder: NetworkCipher.PacketDecoder
	) {
		val remoteDevice = this@RemoteDevice
		private val outputStream = DataOutputStream(socket.getOutputStream())
		private val encoder = NetworkCipher.PacketEncoder(key)
		private val thread = Thread.currentThread()
		private val outputDigest = MessageDigest.getInstance("MD5")
		private val inputDigest = MessageDigest.getInstance("MD5")
		private val inputHashBytes = ByteArray(16)
		
		fun run() {
			lastSeenIpAddress = socket.inetAddress
			ProtocolServer.instance?.pairedDevicesUpdate()
			socket.keepAlive = true
			ProtocolServer.instance!!.plugins.forEach { plugin ->
				plugin.start(this)
			}
			while (!thread.isInterrupted) {
				try {
					val packet = readPacket()
					if (packet != null) {
						handleReceivedData(packet)
					}
				} catch (e: IOException) {
					Log.w(this::class.java.simpleName, e.toString())
					break
				} catch (e: GeneralSecurityException) {
					Log.w(this::class.java.simpleName, e.toString())
					break
				}
			}
			try {
				socket.close()
			} catch (e: IOException) {
			}
			ProtocolServer.instance!!.plugins.forEach { plugin ->
				plugin.stop(this)
			}
			Log.i(this::class.java.simpleName, "Disconnected from $name")
			synchronized(this@RemoteDevice) {
				connectionHandler = null
			}
			ProtocolServer.instance?.pairedDevicesUpdate()
		}
		
		private fun readEncryptedPacket(): ByteArray? {
			inputStream.read(inputHashBytes)
			val packetSize = inputStream.readUnsignedShort()
			val packet = ByteArray(packetSize)
			inputStream.read(packet)
			val actualPacketHash = inputDigest.digest(packet)
			if (!inputHashBytes.contentEquals(actualPacketHash)) {
				Log.w(this::class.java.simpleName, "Expected and actual packet hashes are different ($packetSize bytes)")
				sendStringPacket("error")
				return null
			}
			sendStringPacket("okay")
			Log.v(this::class.java.simpleName, "Received $packetSize encrypted bytes from $name")
			return packet
		}
		
		private fun readPacket(): String? {
			val packet = readEncryptedPacket()
			if (packet != null) {
				Log.v(this::class.java.simpleName, "Received ${packet.size} bytes from $name")
				return String(decoder.doFinal(packet))
			}
			return null
		}
		
		private fun sendEncryptedPacket(data: ByteArray) {
			Log.v(this::class.java.simpleName, "Sending ${data.size} bytes to $name")
			try {
				synchronized(outputStream) {
					val hash = outputDigest.digest(data)
					outputStream.write(hash)
					outputStream.writeShort(data.size)
					outputStream.write(data)
				}
			} catch (e: IOException) {
				if (thread != Thread.currentThread()) {
					disconnect()
				} else {
					throw e
				}
			}
		}
		
		fun sendStringPacket(data: String) = synchronized(encoder) {
			sendEncryptedPacket(encoder.doFinal(data.toByteArray()))
		}
		
		fun sendPacket(data: String) {
			sendStringPacket(data)
		}
		
		fun sendNotification(vararg o: BaseNotification) {
			NotificationAsyncTask(o).execute(this@RemoteDevice)
		}
		
		private fun handleReceivedData(data: String) {
			val json = synchronized(gson) {
				gson.fromJson(data, JsonElement::class.java)
			}.asJsonObject
			val type = json["type"].asString
			for (plugin in ProtocolServer.instance!!.plugins) {
				if (plugin.handleData(this, type, json)) {
					break
				}
			}
		}
		
		fun disconnect() {
			try {
				socket.close()
			} catch (e: IOException) {
			}
			thread.interrupt()
			thread.join()
		}
	}
	
	class NotificationAsyncTask(private val data: Array<out BaseNotification>): AsyncTask<RemoteDevice, Void?, Void?>() {
		override fun doInBackground(vararg devices: RemoteDevice): Void? {
			val packets = synchronized(gson) {
				data.map { gson.toJson(it) }
			}
			devices.forEach { device ->
				packets.forEach { packet ->
					device.connectionHandler?.sendPacket(packet)
				}
			}
			return null
		}
	}
	
	companion object {
		private val gson = Gson()
		
		fun sendBroadcast(forceReconnect: Boolean, vararg devices: RemoteDevice) {
			BroadcastAsyncTask(forceReconnect).execute(*devices)
		}
	}
}
