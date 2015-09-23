package just4fun.android.core.app

import android.app.Notification
import android.app.Service.{START_FLAG_REDELIVERY, START_FLAG_RETRY}
import android.content.Intent
import android.os.{Bundle, IBinder}
import just4fun.android.core.async.{FutureX, ThreadPoolContext}
import just4fun.utils.logger.Logger
import Logger._

private[app] object KeepAliveService {
	private var daemon: android.app.Service = _
	private var keepAliveForeground = false
	def startForeground(id: Int, notification: Notification): Unit = {
		keepAliveForeground = true
		if (daemon == null) {
			val info = new Bundle
			info.putParcelable("notification", notification)
			info.putInt("id", id)
			info.putBoolean("foreground", keepAliveForeground)
			start(info)
		}
		else daemon.startForeground(id, notification)
	}
	def stopForeground(removeNotification: Boolean): Unit = {
		keepAliveForeground = false
		if (daemon != null) daemon.stopForeground(removeNotification)
	}
	def onUiVisible(visible: Boolean): Unit = if (visible) stop() else start()
	
	def start(info: Bundle = null): Unit = if (daemon == null && !Modules.mManager.isEmpty) {
		val intent = new Intent(Modules.context, classOf[KeepAliveService])
		if (info != null) intent.putExtra("info", info)
		Modules.context.startService(intent)
		logV("start KEEP ALIVE")
	}
	def stop(): Unit = if (daemon != null && (!keepAliveForeground || Modules.mManager.isEmpty)) {
		if (keepAliveForeground) stopForeground(true)
		daemon.stopSelf()
		daemon = null
		logV("stop KEEP ALIVE")
	}

	/* SERVICE CALLBACKS */
	private def onDaemonCreate(s: KeepAliveService): Unit = {
		daemon = s
		logV("onCreate")
	}
	private def onDaemonDestroy(s: KeepAliveService): Unit = {
		// WARN !! is called when app is killed via Settings
		daemon = null
		logV("onDestroy")
	}
	private def onDaemonStart(intent: Intent, flags: Int, startId: Int): Int = {
		val isRestart = flags == START_FLAG_REDELIVERY || flags == START_FLAG_RETRY
		if (intent != null) {
			val info = intent.getBundleExtra("info")
			if(info != null) {
				if (isRestart) keepAliveForeground = info.getBoolean("foreground")
				if (keepAliveForeground) {
					val id = info.getInt("id")
					val ntf = info.getParcelable[Notification]("notification")
					daemon.startForeground(id, ntf)
				}
			}
		}
		val cancel = Modules.mManager.isEmpty || (Modules.aManager.uiVisible && !keepAliveForeground)
		logV(s"onStart:   cancel? $cancel")
		if (cancel) stop()

		
		// TODO REmove
		var counter = 0
		if (!cancel) spam()
		def spam(): Unit = {
			logV("KeepAlive SPAM "+ counter )
			counter += 1
			FutureX.post(10000, "SPAM") {spam()} (ThreadPoolContext)
		}


		android.app.Service.START_REDELIVER_INTENT
	}
}



/* SERVICE Class */
class KeepAliveService extends android.app.Service {
	import KeepAliveService._
	override def onCreate(): Unit = onDaemonCreate(this)
	override def onStartCommand(intent: Intent, flags: Int, startId: Int): Int = onDaemonStart(intent, flags, startId)
	override def onDestroy(): Unit = onDaemonDestroy(this)
	override def onBind(intent: Intent): IBinder = null
}
