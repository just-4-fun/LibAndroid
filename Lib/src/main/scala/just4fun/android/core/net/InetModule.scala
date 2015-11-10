package just4fun.android.core.net

import just4fun.android.core.app.{Module, ModuleRequest, PermissionCritical}
import just4fun.android.core.async.{FutureX, MainThreadContext, OwnThreadContextHolder}
import just4fun.android.core.power.BatteryState.BatteryState
import just4fun.android.core.power.{BatteryListenerModule, Battery}
import just4fun.utils.Utils._
import just4fun.utils.logger.Logger._

/* INET MODULE */

class InetModule extends /*BatteryListener*/Module with OwnThreadContextHolder {
	private val KEY = "INET_CHECK_CODE_"
	private[this] var queue = List[InetQuery[_]]()
	private[this] val tailTime = 5000
	private[this] val ACTING = Long.MaxValue
	private[this] var lastActed = 0L
	standbyMode = true

	override protected[this] def permissions = PermissionCritical(android.Manifest.permission.INTERNET) :: Nil

	/* PUBLIC API */
	def request[T](request: InetRequest[T]): ModuleRequest[T] = {
		val q = new InetQuery[T](request)
		addRequest(q)
		q
	}

	/* LIFECYCLE callbacks */
	override protected[this] def onDeactivatingStart(terminal: Boolean): Unit = {
		MainThreadContext.cancel(KEY)
	}

	/* BATTERY power saving */
	protected[this] def addRequest(rq: InetQuery[_]): Unit = synchronized {
		queue = (rq :: queue).sortBy(r => (r.execTime, r.id.asInstanceOf[Int]))
//		logD(s"added  size= ${queue.size};  rqs [${queue.map(_.hashCode).mkString(",")}];  rq= ${rq.hashCode};")
		checkQueue()
	}
	override protected[this] def onRequestComplete(rq: ModuleRequest[_]): Unit = synchronized {
//		logD(s"request complete   size= ${queue.size};  rq= ${rq.hashCode}")
		lastActed = now
		checkQueue() 
	}
	def checkQueue(): Unit = synchronized {
//		logD(s"checkQueue;   size= ${queue.size};  acting= ${lastActed == ACTING}")
		if (queue.nonEmpty && lastActed != ACTING && !isRequestsServingPaused) {
			val nowMs = now
			val rq = queue.head
			if (Battery.charging || rq.execTime <= (nowMs + 1000) || (nowMs - lastActed < tailTime)) {
				lastActed = ACTING
				queue = queue.tail
//				logD(s"requesting    size= ${queue.size};  rq= ${rq.hashCode}  ...")
				serveRequest(rq)
			}
			else FutureX.post(rq.execTime - nowMs, KEY)(checkQueue())(MainThreadContext)
		}
	}
	override protected[this] def onBeforeTerminalDeactivating(): Unit = synchronized {
		if (!isRequestsServingPaused) {
			queue.foreach(serveRequest(_))
			queue = Nil
		}
	}
//	override def onBatteryStateChange(state: BatteryState): Unit = {
//		logD(s"battery state= $state")
//	}
}
