package id.pineapple.notifysync.net

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

abstract class NetworkCipher(mode: Int, key: ByteArray) {
	private val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
	
	init {
		cipher.init(
			mode,
			SecretKeySpec(key.slice(0 until key.size / 2).toByteArray(), "AES"),
			IvParameterSpec(key.slice(key.size / 2 until key.size).toByteArray())
		)
	}
	
	fun update(data: ByteArray): ByteArray = cipher.update(data)
	
	fun doFinal(data: ByteArray): ByteArray = cipher.doFinal(data)
	
	class PacketEncoder(key: ByteArray): NetworkCipher(Cipher.ENCRYPT_MODE, key)
	
	class PacketDecoder(key: ByteArray): NetworkCipher(Cipher.DECRYPT_MODE, key)
}
