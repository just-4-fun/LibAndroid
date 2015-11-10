package just4fun.android.libtest.test12

import java.net.URL

import android.content.Context
import android.net.ConnectivityManager.{OnNetworkActiveListener, NetworkCallback}
import android.net.{Network, NetworkRequest, NetworkCapabilities, ConnectivityManager}
import android.os.{Build, Bundle}
import android.view.View
import android.view.View.OnClickListener
import android.widget.Button
import just4fun.android.core.app._
import just4fun.android.core.net._
import just4fun.android.core.power.BatteryListenerModule
import just4fun.android.core.power.BatteryState.BatteryState
import just4fun.android.libtest.{TestModule, R}
import just4fun.utils.logger.Logger._

import scala.util.{Failure, Success}

class TestActivity extends TwixActivity[TestActivity, MainModule] {
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
		findViewById(R.id.button1).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				logD(s"click1")
				module.query()
			}
		})
		findViewById(R.id.button2).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				logD(s"click2")
				module.switchListenBattery()
			}
		})
	}
}

class MainModule extends TwixModule[TestActivity, MainModule]
with TestModule
with NetChannelListenerModule
with BatteryListenerModule
{
	startAfter = 1000
	stopAfter = 1000
	//	override protected[this] impliacit val netChannel: NetChannel = _
	val inet = bind[InetModule]
	val defaultCfg = InetRequest().attempts(3)
	val mgr = Modules.systemService[ConnectivityManager](Context.CONNECTIVITY_SERVICE)
	var listenBattery =  true

	override protected[this] def permissions = PermissionCritical(android.Manifest.permission.ACCESS_NETWORK_STATE) :: Nil

	override protected[this] def onActivatingStart(firstTime: Boolean): Unit = {
		import NetworkCapabilities._
		super.onActivatingStart(firstTime)
		if (netChannel.isOnline)query()
		logD(s"NETWORKS\n${NetChannel.dumpNetworks()}")
		logD(s"NETWORK exists? ${NetChannel.exists(NET_CAPABILITY_INTERNET)(TRANSPORT_WIFI)()}")
		if (Build.VERSION.SDK_INT >= 21) {
			mgr.addDefaultNetworkActiveListener(new OnNetworkActiveListener {
				override def onNetworkActive(): Unit = {
					logD(s"Default Network Active.")
					//				logD(s"2 :: netActive conn= ${new URL("http://www.google.com").openConnection().connect()}")
				}
			})
		}
	}
	def query() = {
		inet.request(defaultCfg().url("http://www.google.com"))
	}
	def switchListenBattery(): Unit = {
		if (listenBattery) stopListenBatteryState() else startListenBatteryState()
		listenBattery = !listenBattery
	}
	override protected[this] def onOnlineStateChange(online: Boolean): Unit = {
		logD(s"State Changed:: Online? $online")
		if (online) query()
		  .onComplete {
			  case Success(res) => logD(s"Request complete:: res= $res")
			  case Failure(e) => logD(s"Request error= $e")
		  }
	}
	override def onBatteryStateChange(state: BatteryState): Unit = {
		logD(s"BATTERY state= $state")
	}
}
