package id.pineapple.notifysync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import id.pineapple.notifysync.net.ProtocolServer
import kotlinx.android.synthetic.main.fragment_dashboard.*

class DashboardFragment: Fragment(), DashboardAdapter.OnAddNewDeviceListener {
	private var adapter: DashboardAdapter? = null
	private val refreshDeviceList = {
		ProtocolServer.instance?.sendBroadcast()
		device_list_recycler_view.postDelayed({
			device_list_refresh_layout?.isRefreshing = false
		}, 2000)
	}
	
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setHasOptionsMenu(true)
	}
	
	override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
		inflater.inflate(R.menu.menu_main, menu)
	}
	
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
			inflater.inflate(R.layout.fragment_dashboard, container, false)
	
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		adapter = DashboardAdapter(device_list_recycler_view.context)
		adapter!!.onAddNewDeviceListener = this
		device_list_recycler_view.adapter = adapter
		
		device_list_refresh_layout.setOnRefreshListener { refreshDeviceList() }
		
		NLService.addListener(adapter!!)
		ProtocolServer.instance?.addOnPairedDevicesUpdateListener(adapter!!)
	}
	
	override fun onStart() {
		super.onStart()
		if (!device_list_refresh_layout.isRefreshing) {
			refreshDeviceList()
		}
	}
	
	override fun onDestroyView() {
		ProtocolServer.instance?.removeOnPairedDevicesUpdateListener(adapter!!)
		NLService.removeListener(adapter!!)
		super.onDestroyView()
		adapter = null
	}
	
	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.action_settings -> (activity as MainActivity).showFragment(SettingsFragment.newInstance())
			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}
	
	override fun onAddNewDevice() {
		QRCodeScannerFragment.newInstance(getString(R.string.pair_new_device_message)).let {
			it.setTargetFragment(this, REQUEST_PAIR_NEW_DEVICE)
			(activity as MainActivity).showFragment(it)
		}
	}
	
	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		when (requestCode) {
			REQUEST_PAIR_NEW_DEVICE -> if (resultCode == Activity.RESULT_OK) {
				val text = data!!.getStringExtra(QRCodeScannerFragment.RESULT_TEXT)!!
				val parts = text.split(':', limit = 3)
				if (parts.size == 3 && parts[0] == "NotifySync" && parts[2].isNotEmpty()) {
					val key = Base64.decode(parts[1], Base64.DEFAULT)
					if (key.size == 32) {
						val deviceName = parts[2]
						ProtocolServer.instance?.startPairing(key, deviceName)
						return
					}
				}
				Toast.makeText(context!!, getString(R.string.invalid_new_device_qr_code), Toast.LENGTH_LONG).show()
			}
		}
	}
	
	companion object {
		private const val REQUEST_PAIR_NEW_DEVICE = 1
		
		@JvmStatic
		fun newInstance() = DashboardFragment()
	}
}
