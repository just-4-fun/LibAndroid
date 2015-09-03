package just4fun.android.libtest

import just4fun.android.core.app.Module
import just4fun.android.core.async.FutureX
import just4fun.utils.devel.ILogger._

trait TestModule extends Loggable {
	this: Module =>
	var activating = true
	var deactivating = true
	var startAfter = 0
	var stopAfter = 0
	override protected[this] def onStartActivating(firstTime: Boolean): Unit = FutureX.post(delay = startAfter) {activating = false; deactivating = true}
	override protected[this] def onKeepActivating(firstTime: Boolean): Boolean = !activating
	override protected[this] def onStartDeactivating(lastTime: Boolean): Unit = FutureX.post(delay = stopAfter) {deactivating = false; activating = true}
	override protected[this] def onKeepDeactivating(lastTime: Boolean): Boolean = !deactivating
	override protected[this] def onTimeout(): Boolean = { logW("onTimeout"); true }
	override protected[this] def onFailure(err: Throwable): Option[Throwable] = err match {
		//		case DependencyParentFailed(s) => Some(err)
		//	case timeout => Some(err)
		case e => logE(err, "onFailure"); Some(e)
	}
}

