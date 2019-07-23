package id.pineapple.notifysync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.zxing.Result
import me.dm7.barcodescanner.zxing.ZXingScannerView

class QRCodeScannerFragment: Fragment(), ZXingScannerView.ResultHandler {
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
		ZXingScannerView(context)
	
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		(view as ZXingScannerView).setResultHandler(this)
	}
	
	override fun onResume() {
		super.onResume()
		if (ContextCompat.checkSelfPermission(context!!, Manifest.permission.CAMERA) !=
			PackageManager.PERMISSION_GRANTED) {
			requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
		} else {
			(view as ZXingScannerView).startCamera()
			Toast.makeText(context!!, arguments!!.getString(ARG_MESSAGE), Toast.LENGTH_LONG).show()
		}
	}
	
	override fun onPause() {
		(view as ZXingScannerView).stopCamera()
		super.onPause()
	}
	
	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		when (requestCode) {
			REQUEST_CAMERA_PERMISSION ->
				if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					(view as ZXingScannerView).startCamera()
					Toast.makeText(context!!, arguments!!.getString(ARG_MESSAGE), Toast.LENGTH_LONG).show()
				} else {
					activity!!.supportFragmentManager.popBackStack()
				}
		}
	}
	
	override fun handleResult(result: Result) {
		activity!!.supportFragmentManager.popBackStack()
		targetFragment?.onActivityResult(targetRequestCode, Activity.RESULT_OK, Intent().apply {
			putExtra(RESULT_TEXT, result.text)
		})
	}
	
	companion object {
		private const val REQUEST_CAMERA_PERMISSION = 1
		
		private const val ARG_MESSAGE = "message"
		
		const val RESULT_TEXT = "text"
		
		@JvmStatic
		fun newInstance(message: String) = QRCodeScannerFragment().apply {
			arguments = Bundle().apply {
				putString(ARG_MESSAGE, message)
			}
		}
	}
}
