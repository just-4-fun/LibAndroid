package just4fun.android.libtest

import just4fun.android.core.app.Module
import just4fun.android.core.async.FutureX
import just4fun.utils.logger.Logger._

trait TestModule {
	this: Module =>

	override val moduleID: String = getClass.getSimpleName
	var activating = true
	var deactivating = true
	var startAfter = 0
	var stopAfter = 0
	override protected[this] def onActivatingStart(firstTime: Boolean): Unit = FutureX.post(delay = startAfter) {activating = false; deactivating = true}
	override protected[this] def onActivatingProgress(firstTime: Boolean, seconds: Int): Boolean = !activating
	override protected[this] def onDeactivatingStart(lastTime: Boolean): Unit = FutureX.post(delay = stopAfter) {deactivating = false; activating = true}
	override protected[this] def onDeactivatingProgress(lastTime: Boolean, seconds: Int): Boolean = !deactivating
	override protected[this] def onFailure(err: Throwable): Option[Throwable] = err match {
		//		case DependencyParentFailed(s) => Some(err)
		//	case timeout => Some(err)
		case e => logE(err, "onFailure"); Some(e)
	}
}

