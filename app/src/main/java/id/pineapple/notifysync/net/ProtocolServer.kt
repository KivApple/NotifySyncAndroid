package id.pineapple.notifysync.net

import android.content.Context
import android.util.Log
import id.pineapple.notifysync.plugins.BasePlugin
import java.io.DataInputStream
import java.io.IOException
import java.net.*

class ProtocolServer(
	var deviceName: String,
	savedPairedDevices: List<Pair<ByteArray, String>> = emptyList()
): Thread() {
	private val remoteDeviceThreadGroup = ThreadGroup("remote-device-threads")
	internal val broadcastSocket = DatagramSocket(UDP_PORT, InetAddress.getByName("0.0.0.0"))
	private val serverSocket = ServerSocket(TCP_PORT)
	private var pairingDevice: RemoteDevice? = null
	private var _pairedDevices = savedPairedDevices.map { RemoteDevice(it.first, it.second) }.toMutableList()
	private val onPairedDevicesUpdateListeners = mutableSetOf<OnPairedDevicesUpdateListener>()
	val pairedDevices: List<RemoteDevice> get() = _pairedDevices
	private val _plugins = mutableListOf<BasePlugin>()
	val plugins: List<BasePlugin> get() = _plugins
	private val broadcastReceiverThread = BroadcastReceiverThread()
	
	init {
		broadcastSocket.broadcast = true
		instance = this
		start()
		broadcastReceiverThread.start()
	}
	
	fun shutdown() {
		broadcastReceiverThread.interrupt()
		broadcastReceiverThread.join()
		interrupt()
		join()
		synchronized(this) {
			_pairedDevices.forEach { device ->
				device.connectionHandler?.disconnect()
			}
		}
		_plugins.forEach { it.destroy() }
		remoteDeviceThreadGroup.destroy()
		serverSocket.close()
		broadcastSocket.close()
		instance = null
	}
	
	fun registerPlugin(context: Context, plugin: BasePlugin) {
		synchronized(this) {
			_plugins.add(plugin)
		}
		plugin.init(context)
	}
	
	fun startPairing(key: ByteArray, remoteDeviceName: String) {
		pairingDevice = RemoteDevice(key, remoteDeviceName)
		pairingDevice?.sendBroadcast()
	}
	
	fun sendBroadcast(forceReconnect: Boolean = false) {
		RemoteDevice.sendBroadcast(forceReconnect, *synchronized(this) {
			_pairedDevices.toTypedArray()
		})
	}
	
	fun sendNotification(vararg o: BaseNotification) {
		RemoteDevice.NotificationAsyncTask(o).execute(*synchronized(this) {
			_pairedDevices.filter { it.isConnected }
		}.toTypedArray())
	}
	
	fun unpair(device: RemoteDevice) {
		device.disconnect()
		if (synchronized(this) { _pairedDevices.remove(device) }) {
			pairedDevicesUpdate()
		}
	}
	
	fun pairedDevicesUpdate() {
		synchronized(this) {
			onPairedDevicesUpdateListeners.forEach {
				it.onPairedDevicesUpdate()
			}
		}
	}
	
	override fun run() {
		while (!isInterrupted) {
			val socket = serverSocket.accept()
			if (remoteDeviceThreadGroup.activeCount() < synchronized(this) { pairedDevices.size } + 1) {
				val thread = RemoteDeviceThread(socket)
				thread.start()
			} else {
				socket.close()
			}
		}
	}
	
	fun addOnPairedDevicesUpdateListener(listener: OnPairedDevicesUpdateListener) {
		onPairedDevicesUpdateListeners.add(listener)
	}
	
	fun removeOnPairedDevicesUpdateListener(listener: OnPairedDevicesUpdateListener) {
		onPairedDevicesUpdateListeners.remove(listener)
	}
	
	interface OnPairedDevicesUpdateListener {
		fun onPairedDevicesUpdate()
	}
	
	inner class RemoteDeviceThread(private val socket: Socket): Thread(remoteDeviceThreadGroup, null as? Runnable) {
		override fun run() {
			val inputStream = DataInputStream(socket.getInputStream())
			val handshakeData = try {
				val handshakeLength = inputStream.readUnsignedShort()
				val handshakeData = ByteArray(handshakeLength)
				inputStream.read(handshakeData)
				handshakeData
			} catch (e: IOException) {
				try {
					socket.close()
				} catch (e: IOException) {
				}
				return
			}
			val handler = synchronized(this@ProtocolServer) {
				var result = pairingDevice?.acceptHandshake(handshakeData, socket, inputStream)
				if (result != null) {
					_pairedDevices.add(pairingDevice!!)
					pairingDevice = null
					pairedDevicesUpdate()
				} else {
					for (device in _pairedDevices) {
						result = device.acceptHandshake(handshakeData, socket, inputStream)
						if (result != null) {
							break
						}
					}
				}
				result
			}
			if (handler != null) {
				handler.run()
			} else {
				socket.close()
			}
		}
	}
	
	inner class BroadcastReceiverThread: Thread() {
		override fun run() {
			val buffer = ByteArray(2048)
			val packet = DatagramPacket(buffer, buffer.size)
			while (!isInterrupted) {
				broadcastSocket.receive(packet)
				val data = packet.data.slice(0 until packet.length).toByteArray()
				synchronized(this@ProtocolServer) {
					for (device in _pairedDevices) {
						if (device.acceptBroadcast(data)) {
							device.sendBroadcast(broadcastSocket, false)
							break
						}
					}
				}
			}
		}
	}
	
	companion object {
		const val UDP_PORT = 5397
		const val TCP_PORT = 5397
		
		@JvmStatic
		internal val BROADCAST_ADDRESS = InetAddress.getByName("255.255.255.255")!!
		
		@JvmStatic
		var instance: ProtocolServer? = null
			private set
	}
}
