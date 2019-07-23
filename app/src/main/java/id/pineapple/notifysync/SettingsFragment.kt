package id.pineapple.notifysync

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import id.pineapple.notifysync.net.ProtocolServer

class SettingsFragment: PreferenceFragmentCompat() {
	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		setPreferencesFromResource(R.xml.pref_main, rootKey)
		
		bindPreferenceSummaryToValue(findPreference("device_name")) { p, newValue ->
			ProtocolServer.instance?.deviceName = newValue.toString()
			true
		}
		findPreference("notification_access_settings").setOnPreferenceClickListener {
			Utils.openNotificationAccessSettings(context!!)
			true
		}
	}
	
	private val sBindPreferenceSummaryToValueListener = Preference.OnPreferenceChangeListener { preference, value ->
		val stringValue = value?.toString()
		if (preference is ListPreference) {
			val index = preference.findIndexOfValue(stringValue)
			preference.setSummary(if (index >= 0) preference.entries[index] else null)
		} else {
			preference.summary = stringValue
		}
		true
	}
	
	private fun bindPreferenceSummaryToValue(
		preference: Preference,
		callback: ((preference: Preference, newValue: Any?) -> Boolean)? = null
	) {
		preference.onPreferenceChangeListener =
			if (callback != null) {
				Preference.OnPreferenceChangeListener { p, newValue ->
					if (callback(p, newValue))
						sBindPreferenceSummaryToValueListener.onPreferenceChange(p, newValue)
					else
						false
				}
			} else
				sBindPreferenceSummaryToValueListener
		sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
			PreferenceManager
				.getDefaultSharedPreferences(preference.context)
				.getString(preference.key, ""))
	}
	
	companion object {
		@JvmStatic
		fun newInstance() = SettingsFragment()
	}
}
