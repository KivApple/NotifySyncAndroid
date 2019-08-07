package id.pineapple.notifysync

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import id.pineapple.notifysync.net.ProtocolServer
import id.pineapple.notifysync.net.RemoteDevice

class UnpairDeviceDialogFragment: DialogFragment() {
	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
		AlertDialog.Builder(context!!)
			.setMessage(getString(R.string.are_you_sure_unpair, arguments!!.getString(ARG_DEVICE_NAME)))
			.setPositiveButton(R.string.yes) { _, _ ->
				synchronized(ProtocolServer.instance!!) { ProtocolServer.instance?.pairedDevices?.first {
					it.key.contentEquals(arguments!!.getByteArray(ARG_DEVICE_KEY)!!)
				}}?.let { device ->
					ProtocolServer.instance?.unpair(device)
				}
			}
			.setNegativeButton(R.string.no) { _, _ -> }
			.create()
	
	companion object {
		private const val ARG_DEVICE_NAME = "device_name"
		private const val ARG_DEVICE_KEY = "device_key"
		
		@JvmStatic
		fun newInstance(device: RemoteDevice) = UnpairDeviceDialogFragment().apply {
			arguments = Bundle().apply {
				putString(ARG_DEVICE_NAME, device.name)
				putByteArray(ARG_DEVICE_KEY, device.key)
			}
		}
	}
}
