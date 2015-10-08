package just4fun.android.core.net

import android.content.{IntentFilter, Intent, BroadcastReceiver, Context}
import android.net.ConnectivityManager
import just4fun.android.core.app.Module
import just4fun.utils.logger.Logger._

class ConnectivityModule extends BroadcastReceiver with Module {

	override protected[this] def onActivatingStart(initial: Boolean): Unit = {
		appContext.registerReceiver(this, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))

	}
	override protected[this] def onDeactivatingFinish(terminal: Boolean): Unit = {
		try appContext.unregisterReceiver(this) catch loggedE
		//

	}
	override protected[this] def onFailure(err: Throwable): Option[Throwable] = {
		super.onFailure(err)
	}
	override def onReceive(context: Context, intent: Intent): Unit = {
//		val isOnline = isReallyOnline
//		logV("onReceive", s"wasOnline: ${_online};  isOnline: $isOnline")
//		if (_online && !isOnline) fireEvent(false)
//		else if (!_online && isOnline) postCheck(SHORT_SPAN)
	}
}
