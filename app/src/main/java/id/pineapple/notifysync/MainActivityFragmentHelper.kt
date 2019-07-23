package id.pineapple.notifysync

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

class MainActivityFragmentHelper(
	private val activity: MainActivity
): FragmentManager.FragmentLifecycleCallbacks() {
	private val resumedFragments = mutableSetOf<Fragment>()
	
	init {
		activity.supportFragmentManager.registerFragmentLifecycleCallbacks(this, false)
	}
	
	override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
		resumedFragments.add(f)
		onResumedFragmentsChanged()
	}
	
	override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
		resumedFragments.remove(f)
		onResumedFragmentsChanged()
	}
	
	private fun onResumedFragmentsChanged() {
		if (resumedFragments.isNotEmpty() && resumedFragments.any { it is DashboardFragment }) {
			activity.supportActionBar?.setDisplayHomeAsUpEnabled(false)
		} else {
			activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
		}
	}
	
	fun showFragment(fragment: Fragment) {
		val transaction = activity.supportFragmentManager.beginTransaction()
		transaction.replace(R.id.fragment_container, fragment)
		if (fragment !is DashboardFragment) {
			transaction.addToBackStack(null)
		}
		transaction.commit()
	}
}
