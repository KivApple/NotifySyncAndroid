package id.pineapple.notifysync.plugins

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.JsonObject
import id.pineapple.notifysync.R
import id.pineapple.notifysync.net.BaseNotification
import id.pineapple.notifysync.net.ProtocolServer
import id.pineapple.notifysync.net.RemoteDevice
import kotlinx.android.synthetic.main.activity_find_device.*

class FindDevicePlugin: BasePlugin {
	private lateinit var context: Context
	
	override fun init(context: Context) {
		this.context = context
	}
	
	override fun handleData(conn: RemoteDevice.Connection, type: String, data: JsonObject): Boolean {
		if (type != "find-device") return false
		if (!data.has("cancel")) {
			Handler(Looper.getMainLooper()).post {
				context.startActivity(Intent(context, FindDeviceActivity::class.java))
			}
		} else {
			Handler(Looper.getMainLooper()).post {
				FindDeviceActivity.instance?.finish()
				ProtocolServer.instance?.sendNotification(DeviceFoundNotification())
			}
		}
		return true
	}
	
	class DeviceFoundNotification: BaseNotification("device-found")
	
	class FindDeviceActivity: AppCompatActivity() {
		private lateinit var vibrator: Vibrator
		private lateinit var audioManager: AudioManager
		private var userAlarmVolume: Int = 0
		private var mediaPlayer: MediaPlayer? = null
		
		override fun onCreate(savedInstanceState: Bundle?) {
			super.onCreate(savedInstanceState)
			
			requestWindowFeature(Window.FEATURE_NO_TITLE)
			window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
					WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
					WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
			
			setContentView(R.layout.activity_find_device)
			
			device_found_button.setOnClickListener {
				ProtocolServer.instance?.sendNotification(DeviceFoundNotification())
				finish()
			}
			
			audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
			RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.let { ringtoneUri ->
				mediaPlayer = MediaPlayer()
				mediaPlayer?.setDataSource(this, ringtoneUri)
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
					mediaPlayer?.setAudioAttributes(
						AudioAttributes.Builder()
							.setUsage(AudioAttributes.USAGE_ALARM)
							.build()
					)
				} else {
					mediaPlayer?.setAudioStreamType(AudioManager.STREAM_ALARM)
				}
				mediaPlayer?.prepare()
				mediaPlayer?.start()
			}
			vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
			
			instance = this
		}
		
		override fun onStart() {
			super.onStart()
			val pattern = longArrayOf(
				0, 1000, 0, 200, 800
			)
			val repeat = 2
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				vibrator.vibrate(
					VibrationEffect.createWaveform(pattern, repeat),
					AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_UNKNOWN)
						.build()
				)
			} else {
				vibrator.vibrate(pattern, repeat)
			}
			
			userAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
			audioManager.setStreamVolume(AudioManager.STREAM_ALARM,
				audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), AudioManager.FLAG_PLAY_SOUND)
		}
		
		override fun onStop() {
			audioManager.setStreamVolume(AudioManager.STREAM_ALARM, userAlarmVolume, AudioManager.FLAG_PLAY_SOUND)
			
			vibrator.cancel()
			super.onStop()
		}
		
		override fun onDestroy() {
			mediaPlayer?.stop()
			instance = null
			super.onDestroy()
		}
		
		override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
			when (keyCode) {
				KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
					audioManager.setStreamVolume(AudioManager.STREAM_ALARM, userAlarmVolume,
						AudioManager.FLAG_PLAY_SOUND)
					mediaPlayer?.stop()
				}
			}
			return super.onKeyDown(keyCode, event)
		}
		
		override fun onBackPressed() {
		}
		
		companion object {
			var instance: FindDeviceActivity? = null
				private set
		}
	}
}
