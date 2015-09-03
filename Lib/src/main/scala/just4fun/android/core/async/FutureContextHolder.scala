package just4fun.android.core.async

import android.os.{HandlerThread, Looper}
import just4fun.utils.devel.ILogger._


/** FtureContext that is required by [[FutureX]] functions */
trait FutureContextHolder extends Loggable {
	implicit val  futureContext: FutureContext = new HandlerContext(logtag.name)
}



/** Class that extends it runs its async operations in allocated parallel [[Thread]]. */
trait OwnThreadContextHolder extends FutureContextHolder {
	override implicit val futureContext: FutureContext = new HandlerContext(logtag.name, false)
}



/** Class that extends it runs its async operations each in new [[Thread]] from pool. */
trait NewThreadContextHolder extends FutureContextHolder {
	override implicit val futureContext: FutureContext = new ThreadPoolContext(logtag.name)
}
