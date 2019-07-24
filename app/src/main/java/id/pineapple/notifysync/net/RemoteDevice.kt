package id.pineapple.notifysync.net

import android.os.AsyncTask
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonSyntaxException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.util.*

class RemoteDevice(
	val key: ByteArray,
	name: String
) {
	var name: String = name
		private set
	private val broadcastEncoder = NetworkCipher.PacketEncoder(key)
	private val broadcastDecoder = NetworkCipher.PacketDecoder(key)
	var connection: Connection? = null
		private set
	val isConnected: Boolean get() = connection != null
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
	
	fun acceptHandshake(encryptedHandshake: ByteArray, socket: Socket, inputStream: DataInputStream): Connection? {
		val decoder = NetworkCipher.PacketDecoder(key)
		try {
			val handshake = String(decoder.doFinal(encryptedHandshake)).split(':', limit = 4)
			if (handshake.size == 4 && handshake[1] == "NotifySync" && handshake[3].isNotEmpty()) {
				name = handshake[3]
				Log.i(this::class.java.simpleName, "Accepted handshake from $name")
				return synchronized(this) {
					connection?.disconnect()
					connection = Connection(socket, inputStream, decoder, handshake[2] != "0")
					connection
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
		synchronized(this) { connection }?.disconnect()
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
	
	inner class Connection(
		private val socket: Socket,
		private val inputStream: DataInputStream,
		private val decoder: NetworkCipher.PacketDecoder,
		private val encryptionEnabled: Boolean
	) {
		val remoteDevice = this@RemoteDevice
		private val outputStream = DataOutputStream(socket.getOutputStream())
		private val encoder = NetworkCipher.PacketEncoder(key)
		private val thread = Thread.currentThread()
		
		fun run() {
			lastSeenIpAddress = socket.inetAddress
			ProtocolServer.instance?.pairedDevicesUpdate()
			socket.keepAlive = true
			ProtocolServer.instance!!.plugins.forEach { plugin ->
				plugin.start(this)
			}
			while (!thread.isInterrupted) {
				var packet = ""
				try {
					packet = readPacket()
					handleReceivedData(packet)
				} catch (e: IOException) {
					Log.w(this::class.java.simpleName, e.toString())
					break
				} catch (e: GeneralSecurityException) {
					Log.e(this::class.java.simpleName, e.toString())
					break
				} catch (e: JsonSyntaxException) {
					Log.e(this::class.java.simpleName, e.toString())
					Log.e(this::class.java.simpleName, "JSON: $packet")
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
				connection = null
			}
			ProtocolServer.instance?.pairedDevicesUpdate()
		}
		
		private fun readEncryptedPacket(): ByteArray {
			val packetSize = inputStream.readUnsignedShort()
			val packet = ByteArray(packetSize)
			inputStream.readFully(packet)
			Log.v(this::class.java.simpleName, "Received $packetSize encrypted bytes from $name")
			return packet
		}
		
		private fun readPacket(): String {
			val encryptedPacket = readEncryptedPacket()
			val packet = if (encryptionEnabled) decoder.doFinal(encryptedPacket) else encryptedPacket
			Log.v(this::class.java.simpleName, "Received ${packet.size} bytes from $name")
			return String(packet)
		}
		
		private fun sendEncryptedPacket(data: ByteArray) {
			Log.v(this::class.java.simpleName, "Sending ${data.size} bytes to $name")
			try {
				synchronized(outputStream) {
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
		
		private fun sendStringPacket(data: String) = synchronized(encoder) {
			val packet = data.toByteArray()
			val encryptedPacket = if (encryptionEnabled) encoder.doFinal(packet) else packet
			sendEncryptedPacket(encryptedPacket)
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
					device.connection?.sendPacket(packet)
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
