package id.pineapple.notifysync

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import id.pineapple.notifysync.net.ProtocolServer
import id.pineapple.notifysync.net.RemoteDevice
import kotlinx.android.extensions.LayoutContainer
import kotlinx.android.synthetic.main.item_device.*
import kotlinx.android.synthetic.main.item_new_device.*
import kotlinx.android.synthetic.main.item_notification.*

class DashboardAdapter(
	private val context: Context
): RecyclerView.Adapter<DashboardAdapter.BaseViewHolder>(), NLService.OnUpdateListener,
	ProtocolServer.OnPairedDevicesUpdateListener {
	
	var addNewDeviceListener: AddNewDeviceListener? = null
	private var items = emptyList<Any>()
	private val handler = Handler()
	
	init {
		updateItems()
	}
	
	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder = when (viewType) {
		VIEW_HEADER_DEVICES -> DevicesHeaderViewHolder(parent)
		VIEW_DEVICE -> DeviceViewHolder(parent)
		VIEW_NEW_DEVICE -> NewDeviceViewHolder(parent)
		VIEW_HEADER_NOTIFICATIONS -> HeaderNotificationsViewHolder(parent)
		VIEW_NO_NOTIFICATIONS -> NoNotificationsViewHolder(parent)
		VIEW_NOTIFICATION -> NotificationViewHolder(parent)
		else -> throw IllegalArgumentException()
	}
	
	override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
		when (holder) {
			is DeviceViewHolder -> holder.bind(items[position] as RemoteDevice)
			is NotificationViewHolder -> holder.bind(items[position] as NotificationItem)
		}
	}
	
	override fun getItemViewType(position: Int): Int = when (items[position]) {
		is DevicesHeaderMarker -> VIEW_HEADER_DEVICES
		is RemoteDevice -> VIEW_DEVICE
		is NewDeviceMarker -> VIEW_NEW_DEVICE
		is NotificationsHeaderMarker -> VIEW_HEADER_NOTIFICATIONS
		is NoNotificationsMarker -> VIEW_NO_NOTIFICATIONS
		is NotificationItem -> VIEW_NOTIFICATION
		else -> throw IllegalStateException()
	}
	
	override fun getItemCount(): Int = items.size
	
	override fun onNotificationPosted(item: NotificationItem) = updateItems()
	
	override fun onNotificationRemoved(item: NotificationItem) = updateItems()
	
	override fun onPairedDevicesUpdate() {
		handler.post {
			updateItems()
		}
	}
	
	private fun updateItems() {
		items = listOf(DevicesHeaderMarker()) +
				(ProtocolServer.instance?.pairedDevices ?: listOf()) +
				listOf(NewDeviceMarker(), NotificationsHeaderMarker()) +
				if (NLService.notifications.isNotEmpty())
					NLService.notifications.toList()
				else
					listOf<Any>(NoNotificationsMarker())
		notifyDataSetChanged()
	}
	
	interface AddNewDeviceListener {
		fun onAddNewDevice()
	}
	
	class DevicesHeaderMarker
	
	class NewDeviceMarker
	
	class NotificationsHeaderMarker
	
	class NoNotificationsMarker
	
	open inner class BaseViewHolder(resId: Int, container: ViewGroup?): RecyclerView.ViewHolder(
		LayoutInflater.from(context).inflate(resId, container, false)
	)
	
	inner class DevicesHeaderViewHolder(container: ViewGroup?):
		BaseViewHolder(R.layout.item_header_devices, container)
	
	inner class DeviceViewHolder(container: ViewGroup?):
		BaseViewHolder(R.layout.item_device, container), LayoutContainer {
		override val containerView: View? = itemView
		
		fun bind(item: RemoteDevice) {
			device_name.text = item.name
			device_name.setTextColor(context.resources.getColor(
					if (item.isConnected)
						R.color.connectedDevice
					else
						R.color.disconnectedDevice
			))
			device_address.text = item.lastSeenIpAddress?.hostAddress
			device_address.visibility = if (item.isConnected) View.VISIBLE else View.GONE
			unpair_device.setOnClickListener {
				UnpairDeviceDialogFragment.newInstance(item).show(
					(context as FragmentActivity).supportFragmentManager, null
				)
			}
		}
	}
	
	inner class NewDeviceViewHolder(container: ViewGroup?):
		BaseViewHolder(R.layout.item_new_device, container), LayoutContainer {
		override val containerView: View? = itemView
		
		init {
			new_device.setOnClickListener {
				addNewDeviceListener?.onAddNewDevice()
			}
		}
	}
	
	inner class HeaderNotificationsViewHolder(container: ViewGroup?):
		BaseViewHolder(R.layout.item_header_notifications, container)
	
	inner class NoNotificationsViewHolder(container: ViewGroup?):
		BaseViewHolder(R.layout.item_no_notifications, container)
	
	inner class NotificationViewHolder(container: ViewGroup?): BaseViewHolder(R.layout.item_notification, container),
			LayoutContainer {
		override val containerView: View? = itemView
		
		fun bind(item: NotificationItem) {
			notification_app_name.text = item.appName
			notification_title.text = item.title
			notification_text.text = item.message
			notification_title.visibility = if (item.title.isNotEmpty()) View.VISIBLE else View.GONE
			notification_text.visibility = if (item.message.isNotEmpty()) View.VISIBLE else View.GONE
			notification_icon.setImageBitmap(item.icon)
		}
	}
	
	companion object {
		private const val VIEW_HEADER_DEVICES = 1
		private const val VIEW_DEVICE = 2
		private const val VIEW_NEW_DEVICE = 3
		private const val VIEW_HEADER_NOTIFICATIONS = 4
		private const val VIEW_NO_NOTIFICATIONS = 5
		private const val VIEW_NOTIFICATION = 6
	}
}
