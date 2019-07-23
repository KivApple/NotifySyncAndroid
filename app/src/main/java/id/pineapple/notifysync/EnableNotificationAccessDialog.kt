package id.pineapple.notifysync

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment

class EnableNotificationAccessDialog: DialogFragment() {
	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
			AlertDialog.Builder(context)
				.setMessage(R.string.enable_notification_access_message)
				.setPositiveButton(R.string.go_to_settings) { _, _ ->
					Utils.openNotificationAccessSettings(context!!)
				}
				.setNegativeButton(R.string.not_now) { _, _ ->
				}
				.create()
	
	companion object {
		@JvmStatic
		fun newInstance() = EnableNotificationAccessDialog()
	}
}
