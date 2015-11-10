package just4fun.android.core.net

import java.net.{InetAddress, URLConnection, URL, HttpURLConnection, Proxy}

import android.annotation.TargetApi
import android.content.{IntentFilter, Intent, BroadcastReceiver, Context}
import android.net._
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkCapabilities._
import android.os.Build
import just4fun.android.core.app.MemoryState
import just4fun.android.core.app.MemoryState._
import just4fun.android.core.app.{AppSingleton, Modules, Module}
import just4fun.android.core.async.{ThreadPoolContext, MainThreadContext, FutureX}
import just4fun.utils.Utils._
import just4fun.utils.logger.Logger._

import scala.collection.mutable.ListBuffer

trait NetChannelListenerModule extends Module {
	/** Override to listen for network specified by set of capabilities. See [[just4fun.android.core.net.NetChannel]].apply(capabilities) */
	protected[this] implicit val netChannel: NetChannel = NetChannel.default

	protected[this] def onOnlineStateChange(online: Boolean): Unit

	protected[this] final def startListenOnlineState(): Unit = netChannel.startListen()
	protected[this] final def stopListenOnlineState(): Unit = netChannel.stopListen()

	/* LIFECYCLE callbacks */
	override protected[this] def onActivatingStart(initial: Boolean): Unit = {
		if (initial) startListenOnlineState()
		super.onActivatingStart(initial)
	}
	override protected[this] def onDestroy(): Unit = {
		stopListenOnlineState()
		super.onDestroy()
	}

	/* INTERNAL API */
	private[net] def onlineStateChange(online: Boolean): Unit = try onOnlineStateChange(online) catch loggedE
	private[net] def channel = netChannel
}




/* NET POFILE */
object NetChannel extends AppSingleton {
	import android.Manifest.permission.ACCESS_NETWORK_STATE
	import Build.VERSION.SDK_INT
	private val NOPE = 0
	private val WAIT = 1
	private val LIVE = 2
	private[net] val hasPermission = Modules.hasPermission(ACCESS_NETWORK_STATE)
	private[net] val PRE21 = SDK_INT < 21 || !hasPermission
	private[this] var coreChnl: CoreNetChannel = null
	private[this] var defaultChnl: NetChannel = null
	private[this] var mgr: ConnectivityManager = null
	//
	if (!hasPermission) logW(s"To operate properly ${classOf[NetChannel].getName} requires ACCESS_NETWORK_STATE permission.")

	def exists(capabilities: Int*)(transports: Int*)(minBandwidth: Int = 0): Boolean = {
		if (PRE21) true else existsPost21(capabilities: _*)(transports: _*)(minBandwidth)
	}
	@TargetApi(21)
	private[this] def existsPost21(capabilities: Int*)(transports: Int*)(minBandwidth: Int = 0): Boolean = {
		val nets = manager.getAllNetworks
		if (nets == null) false
		else nets.exists { n =>
			val ncaps = manager.getNetworkCapabilities(n)
			ncaps != null && transports.forall(ncaps.hasTransport) && capabilities.forall(ncaps.hasCapability) && minBandwidth <= ncaps.getLinkDownstreamBandwidthKbps
		}
	}
	def createIfExists(capabilities: Int*)(transports: Int*)(minBandwidth: Int = 0)(implicit listener: NetChannelListenerModule = null): Option[NetChannel] = {
		exists(capabilities: _*)(transports: _*)(minBandwidth) match {
			case true => Some(apply(capabilities: _*)(transports: _*)(minBandwidth))
			case false => None
		}
	}
	/** @param capabilities Use [[android.net.NetworkCapabilities]].NET_CAPABILITY_.. constants.
	@param transports Use [[android.net.NetworkCapabilities]].TRANSPORT_.. constants.
	@param minBandwidth Minimum acceptable bandwidth in Kbps. */
	def apply(capabilities: Int*)(transports: Int*)(minBandwidth: Int = 0)(implicit listener: NetChannelListenerModule = null): NetChannel = {
		if (PRE21) new DefaultNetChannel(listener)
		else new SpecificNetChannel(capabilities, transports, minBandwidth, listener)
	}
	def default(implicit listener: NetChannelListenerModule = null): NetChannel = {
		if (listener != null) new DefaultNetChannel(listener)
		else {
			if (defaultChnl == null) defaultChnl = new DefaultNetChannel(null)
			defaultChnl
		}
	}

	private[net] def coreChannel: CoreNetChannel = {
		if (coreChnl == null) coreChnl = if (PRE21) new CoreNetChannelPre21 else new CoreNetChannelPost21
		coreChnl
	}
	private[net] def manager: ConnectivityManager = {
		if (mgr == null) mgr = Modules.systemService[ConnectivityManager](Context.CONNECTIVITY_SERVICE)
		mgr
	}
	override protected[this] def onTrimMemory(e: MemoryState): Unit = if (e == MemoryState.DROP) {
		if (coreChnl != null) coreChnl.stopListen()
		coreChnl = null
		defaultChnl = null
		mgr = null
	}
	@TargetApi(21) def dumpNetworks(): String = {
		if (Build.VERSION.SDK_INT >= 21) {
			manager.getAllNetworks.map { n =>
				val info = manager.getNetworkInfo(n)
				val caps = manager.getNetworkCapabilities(n)
				val lnk = manager.getLinkProperties(n)
				s"name= ${lnk.getInterfaceName};  state= ${info.getState.name};  type= ${info.getTypeName}:${info.getSubtypeName};  caps= ${0 to 30 filter caps.hasCapability mkString (",")};  trans= ${0 to 10 filter caps.hasTransport mkString (",")};   upload= ${caps.getLinkUpstreamBandwidthKbps} kbps;  download= ${caps.getLinkDownstreamBandwidthKbps} kbps;  "
			}.mkString("\n")
		}
		else {
			manager.getAllNetworkInfo.map { info =>
				s"state= ${info.getState.name};  type= ${info.getTypeName}:${info.getSubtypeName};  "
			}.mkString("\n")
		}
	}
}



/* NET CHANNEL */
trait NetChannel {
	import NetChannel._
	private[net] val listener: NetChannelListenerModule
	private[this] var online = false
	private[this] var listening = false

	def connection(url: URL, proxy: Proxy = null, checkOnline: Boolean = true): Option[URLConnection]
	private[net] def isOnlineReally: Boolean
	protected[this] def startListenWrapped(): Unit
	protected[this] def stopListenWrapped(): Unit

	def httpConnection(url: URL, proxy: Proxy = null, checkOnline: Boolean = true): Option[HttpURLConnection] = {
		connection(url, proxy, checkOnline) match {
			case Some(c: HttpURLConnection) => Some(c)
			case _ => None
		}
	}
	def isOnline: Boolean = {
		if (listening) online else isOnlineReally
	}
	def isOffline: Boolean = !isOnline
	private[net] def isListening: Boolean = listening
	private[net] def startListen(): Unit = if (!listening) {
		listening = true
		//		prepareListen()
		startListenWrapped()
	}
	protected[this] def prepareListen(): Unit = {
		setOnline(isOnlineReally)
	}
	private[net] def stopListen(): Unit = if (listening) {
		listening = false
		online = false
		stopListenWrapped()
	}
	private[net] def setOnline(on: Boolean): Unit = if (listening) {
		logD(s"[${getClass.getSimpleName}];  wasOn? $online;  on? $on")
		if (online != on) {
			online = on
			fireEvent(on)
		}
	}
	private[net] def fireEvent(on: Boolean): Unit = if (listener != null) {
		try listener.onlineStateChange(on) catch loggedE
	}
}





/* DEFAULT NET CHANNEL */
class DefaultNetChannel private[net](val listener: NetChannelListenerModule) extends NetChannel {
	import NetChannel._
	override private[net] def isOnlineReally: Boolean = coreChannel.isOnlineReally
	override def connection(url: URL, proxy: Proxy, checkOnline: Boolean) = coreChannel.connection(url, proxy, checkOnline)
	override protected[this] def startListenWrapped(): Unit = coreChannel.startListen(this)
	override protected[this] def stopListenWrapped(): Unit = coreChannel.stopListen(this)
}





/* CORE NET CHANNEL */
trait CoreNetChannel extends NetChannel {
	override val listener = null
	protected[this] val listeners = collection.mutable.Set[NetChannel]()
	private[net] def startListen(child: DefaultNetChannel): Unit = {
		if (listeners.add(child) && isListening && isOnline) child.setOnline(true)
		super.startListen()
	}
	private[net] def stopListen(child: DefaultNetChannel): Unit = {
		listeners.remove(child)
		if (listeners.isEmpty) super.stopListen()
	}
	override private[net] def stopListen(): Unit = {
		listeners.foreach(_.stopListen())
	}
	override private[net] def fireEvent(on: Boolean): Unit = {
		listeners.foreach(_.setOnline(on))
	}
}




/* PRE 21 NET CHANNEL */
class CoreNetChannelPre21 extends BroadcastReceiver with CoreNetChannel {
	import NetChannel._

	override def onReceive(context: Context, intent: Intent): Unit = {
		setOnline(isOnlineReally)
	}
	override protected[this] def startListenWrapped(): Unit = {
		Modules.context.registerReceiver(this, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
	}
	override protected[this] def stopListenWrapped(): Unit = {
		try Modules.context.unregisterReceiver(this) catch loggedE
	}
	override private[net] def isOnlineReally: Boolean = !hasPermission || {
		val netInfo: NetworkInfo = manager.getActiveNetworkInfo
		netInfo != null && netInfo.isConnected
	}
	override def connection(url: URL, proxy: Proxy, checkOnline: Boolean) = {
		if (checkOnline && !isOnline) None
		else Some(if (proxy == null) url.openConnection() else url.openConnection(proxy))
	}
}




/* POST 21 NET CHANNEL */
@TargetApi(21)
class CoreNetChannelPost21
  extends SpecificNetChannel(NetworkCapabilities.NET_CAPABILITY_INTERNET :: Nil)
  with CoreNetChannel





/* SPEC NET CHANNEL */
@TargetApi(21)
class SpecificNetChannel private[net](val capabilities: Seq[Int], val transports: Seq[Int] = Nil, val minBandwidth: Int = 0, val listener: NetChannelListenerModule = null) extends NetworkCallback with NetChannel {
	import NetChannel._
	import Build.VERSION.SDK_INT
	private[this] var nets = ListBuffer[Network]()

	override private[net] def isOnlineReally: Boolean = activeNetwork.nonEmpty
	override def connection(url: URL, proxy: Proxy, checkOnline: Boolean) = activeNetwork match {
		case Some(net) => Some(if (proxy == null || SDK_INT < 23) net.openConnection(url) else net.openConnection(url, proxy))
		case None => None
	}
	//	override protected[this] def prepareListen(): Unit = findActiveNetwork() match {
	//		case Some(n) => nets.clear(); onAvailable(n)
	//		case None => nets.clear()
	//	}
	override protected[this] def startListenWrapped(): Unit = {
		val b = new NetworkRequest.Builder
		transports.foreach(b.addTransportType)
		capabilities.foreach(b.addCapability)
		manager.registerNetworkCallback(b.build(), this)
	}
	override protected[this] def stopListenWrapped(): Unit = {
		nets.clear()
		manager.unregisterNetworkCallback(this)
	}
	override def onAvailable(network: Network): Unit = {
		//		logD(s"[${getClass.getSimpleName}];  ${netInfo(network)}")
		if (minBandwidth <= speed(network) && !nets.contains(network)) {
			nets = (nets += network).sortBy(costAndSpeed)
			setOnline(true)
		}
	}
	override def onLost(network: Network): Unit = {
		nets -= network
		//		logD(s"[${getClass.getSimpleName}];  ${netInfo(network)};   nets.size= ${nets.size}")
		if (nets.isEmpty) setOnline(false)
	}

	private[this] def activeNetwork: Option[Network] = {
		if (isListening) nets.headOption
		else {
			nets.exists { n =>
				val info = manager.getNetworkInfo(n)
				if (info == null) true
				else if (info.isConnected) return Some(n)
				else false
			} match {
				case true => findActiveNetwork()
				case false if nets.isEmpty => findActiveNetwork()
				case false => None
			}
		}
	}
	private[this] def findActiveNetwork(): Option[Network] = capableNetworks.find { n =>
		val info = manager.getNetworkInfo(n)
		info != null && info.isConnected
	}
	private[this] def capableNetworks: Iterable[Network] = {
		nets.clear()
		val all = manager.getAllNetworks
		if (all != null) all.foreach { n =>
			val ncaps = manager.getNetworkCapabilities(n)
			val capable = ncaps != null && transports.forall(ncaps.hasTransport) && capabilities.forall(ncaps.hasCapability) && minBandwidth <= ncaps.getLinkDownstreamBandwidthKbps
			if (capable) nets += n
		}
		nets = nets.sortBy(costAndSpeed)
		nets
	}
	private[this] def speed(n: Network): Int = {
		val caps = manager.getNetworkCapabilities(n)
		if (caps == null) 0 else caps.getLinkDownstreamBandwidthKbps
	}
	private[this] def costAndSpeed(n: Network): (Boolean, Int) = {
		val caps = manager.getNetworkCapabilities(n)
		if (caps == null) (false, 0)
		else (!caps.hasCapability(NET_CAPABILITY_NOT_METERED), -caps.getLinkDownstreamBandwidthKbps)
	}
	private[this] def netInfo(n: Network): String = {
		val info = manager.getNetworkInfo(n)
		if (info == null) s"no info;  id= ${n.hashCode}"
		else s"type= ${info.getTypeName}:${info.getSubtypeName};  state= ${info.getDetailedState.name};  id= ${n.hashCode}"
	}

}
