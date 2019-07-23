package id.pineapple.notifysync

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import java.util.*

class MainActivity : AppCompatActivity() {
	private lateinit var fragmentHelper: MainActivityFragmentHelper
	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		fragmentHelper = MainActivityFragmentHelper(this)
		setContentView(R.layout.activity_main)
		
		val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
		if (!sharedPreferences.contains("device_name")) {
			val adapter = BluetoothAdapter.getDefaultAdapter()
			val deviceName = adapter.name ?: "android-" + ByteArray(4).let {
				Random().nextBytes(it)
				it.joinToString("") { b -> String.format("%02x", b) }
			}
			sharedPreferences
				.edit()
				.putString("device_name", deviceName)
				.apply()
		}
		
		if (savedInstanceState == null) {
			showFragment(DashboardFragment.newInstance())
			if (!Utils.isNotificationAccessEnabled(this)) {
				EnableNotificationAccessDialog.newInstance().show(supportFragmentManager, null)
			}
		}
		
		startService(Intent(this, BackgroundService::class.java))
	}
	
	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			android.R.id.home -> supportFragmentManager.popBackStack()
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}
	
	fun showFragment(fragment: Fragment) = fragmentHelper.showFragment(fragment)
}
