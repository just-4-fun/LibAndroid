package just4fun.android.core.power

import android.annotation.TargetApi
import android.content.{BroadcastReceiver, Intent, IntentFilter, Context}
import android.os.{SystemClock, Build, BatteryManager}
import just4fun.android.core.app.MemoryState.MemoryState
import just4fun.android.core.app.{Module, MemoryState, AppSingleton, Modules}
import just4fun.android.core.power.BatteryState.BatteryState
import just4fun.utils.logger.Logger._
import SystemClock.elapsedRealtime

import scala.collection.mutable

object Battery extends AppSingleton {
	import BatteryManager._
	import Build.VERSION.SDK_INT
	private[this] var _manager: BatteryManager = null
	private[this] var _tracker: BatteryTracker = null
	private[this] var listeners: mutable.Set[BatteryListenerModule] = null
	private[this] val expired = 1000
	private[this] var lastAsked = 0L
	private[this] var lastInfo: BatteryInfo = null

	def charging: Boolean = tracker.charging

	def info: BatteryInfo = if (lastInfo != null && elapsedRealtime - lastAsked < expired) lastInfo
	else {
		val info = fullInfo
		if (info != null) {
			lastAsked = elapsedRealtime
			val maxLevel = info.getIntExtra(EXTRA_SCALE, 100)
			val level = info.getIntExtra(EXTRA_LEVEL, -1)
			val status = info.getIntExtra(EXTRA_STATUS, -1)
			val source = info.getIntExtra(EXTRA_PLUGGED, -1)
			val percentage = level / (maxLevel / 100f)
			lastInfo = BatteryInfo(source > 0, percentage.toInt, source, status)
			lastInfo
		}
		else {
			lastAsked = 0L
			lastInfo = null
			BatteryInfo(false, -1, -1, -1)
		}
	}
	private[this] def fullInfo: Intent = {
		Modules.context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED))
	}
	private[power] def addBatteryListener(listener: BatteryListenerModule): Unit = {
		if (listeners == null) listeners = mutable.Set[BatteryListenerModule]()
		listeners.add(listener)
		listener.onBatteryStateChange(if (charging) BatteryState.CHARGING else BatteryState.DISCHARGING)
		if (info.level < 15) listener.onBatteryStateChange(BatteryState.CHARGE_LOW)
		tracker.startTrack()
	}
	private[power] def removeBatteryListener(listener: BatteryListenerModule): Unit = {
		if (listeners != null && listeners.remove(listener) && listeners.isEmpty) tracker.stopTrack()
	}
	private[power] def fireEvent(state: BatteryState): Unit = {
		if (listeners != null) listeners.foreach(l => try l.onBatteryStateChange(state) catch loggedE)
	}
	private[power] def manager: BatteryManager = {
		if (_manager == null) _manager = Modules.systemService[BatteryManager](Context.BATTERY_SERVICE)
		_manager
	}
	private[this] def tracker: BatteryTracker = {
		if (_tracker == null) _tracker = if (SDK_INT < 23) new BatteryTrackerPre23 else new BatteryTrackerPost23
		_tracker
	}
	override protected[this] def onTrimMemory(state: MemoryState): Unit = {
		if (state == MemoryState.DROP) {
			if (_tracker != null) _tracker.stopTrack()
			_tracker = null
			listeners = null
		}
		if (state <= MemoryState.LOW) {
			_manager = null
			lastInfo = null
			lastAsked = 0L
		}
	}
}


/**/
case class BatteryInfo(charging: Boolean, level: Int, source: Int, status: Int)


/**/
private[power] trait BatteryTracker {
	import Battery._
	private[this] var tracking = false
	protected[this] var on = false
	def startTrack(): Unit = if (!tracking) {
		tracking = true
		doStartTrack()
	}
	def stopTrack(): Unit = if (tracking) {
		tracking = false
		doStopTrack()
	}
	def charging: Boolean = if (tracking) on else chargingReally
	protected[this] def doStartTrack(): Unit
	protected[this] def doStopTrack(): Unit
	protected[this] def chargingReally: Boolean
}


/**/
private[power] class BatteryTrackerPre23 extends BroadcastReceiver with BatteryTracker {
	import Battery._
	override protected[this] def doStartTrack(): Unit = {
		val filter = new IntentFilter(Intent.ACTION_BATTERY_LOW)
		filter.addAction(Intent.ACTION_BATTERY_OKAY)
		filter.addAction(Intent.ACTION_POWER_CONNECTED)
		filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
		Modules.context.registerReceiver(this, filter)
	}
	override protected[this] def doStopTrack(): Unit = {
		try Modules.context.unregisterReceiver(this) catch loggedE
	}
	override protected[this] def chargingReally: Boolean = info.source > 0
	override def onReceive(context: Context, intent: Intent): Unit = {
		val state: BatteryState = intent.getAction match {
			case Intent.ACTION_BATTERY_LOW => BatteryState.CHARGE_LOW
			case Intent.ACTION_BATTERY_OKAY => BatteryState.CHARGE_OK
			case Intent.ACTION_POWER_CONNECTED => on = true; BatteryState.CHARGING
			case Intent.ACTION_POWER_DISCONNECTED => on = false; BatteryState.DISCHARGING
			case _ => null
		}
		if (state != null) fireEvent(state)
	}
}


/**/
@TargetApi(23)
private[power] class BatteryTrackerPost23 extends BroadcastReceiver with BatteryTracker {
	import Battery._
	override protected[this] def doStartTrack(): Unit = {
		val filter = new IntentFilter(Intent.ACTION_BATTERY_LOW)
		filter.addAction(Intent.ACTION_BATTERY_OKAY)
		filter.addAction(BatteryManager.ACTION_CHARGING)
		filter.addAction(BatteryManager.ACTION_DISCHARGING)
		Modules.context.registerReceiver(this, filter)
	}
	override protected[this] def doStopTrack(): Unit = {
		try Modules.context.unregisterReceiver(this) catch loggedE
	}
	override protected[this] def chargingReally: Boolean = manager.isCharging
	override def onReceive(context: Context, intent: Intent): Unit = {
		val state: BatteryState = intent.getAction match {
			case Intent.ACTION_BATTERY_LOW => BatteryState.CHARGE_LOW
			case Intent.ACTION_BATTERY_OKAY => BatteryState.CHARGE_OK
			case BatteryManager.ACTION_CHARGING => on = true; BatteryState.CHARGING
			case BatteryManager.ACTION_DISCHARGING => on = false; BatteryState.DISCHARGING
			case _ => null
		}
		if (state != null) fireEvent(state)
	}
}



/**/
object BatteryState extends Enumeration {
	type BatteryState = Value
	val CHARGE_LOW, DISCHARGING, CHARGING, CHARGE_OK = Value
}



/**/
trait BatteryListenerModule extends Module {

	def onBatteryStateChange(state: BatteryState): Unit

	protected[this] final def startListenBatteryState(): Unit = Battery.addBatteryListener(this)
	protected[this] final def stopListenBatteryState(): Unit = Battery.removeBatteryListener(this)

	override protected[this] def onActivatingStart(initial: Boolean): Unit = {
		if (initial) startListenBatteryState()
		super.onActivatingStart(initial)
	}
	override protected[this] def onDestroy(): Unit = {
		stopListenBatteryState()
		super.onDestroy()
	}
}