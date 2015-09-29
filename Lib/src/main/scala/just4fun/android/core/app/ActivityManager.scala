package just4fun.android.core.app

import just4fun.utils.logger.Logger

import scala.collection.mutable

import android.app.{Application, Activity}
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import just4fun.utils.Utils
import Logger._

object ActivityState extends Enumeration {
	val NONE, CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED = Value
}

object UiEvent extends Enumeration {
	val NONE, CREATE, SHOW, HIDE, DESTROY  = Value
}




private[app] class ActivityManager(app: Modules, mMgr: ModuleManager) extends ActivityLifecycleCallbacks  {
	import ActivityState._
	private var activity: Activity = _
	private var state: ActivityState.Value = NONE
	private var uiEvent: UiEvent.Value = UiEvent.NONE
	private var reconfiguring = false
	private var visible = false

	// INIT
	app.registerActivityLifecycleCallbacks(this)
	
	def uiContext: Option[Activity] = Option(activity)
	def uiVisible = state == RESUMED
	
	def onActivityConstructed(a: Activity) = {
		if (activity == null) uiEvent = UiEvent.CREATE
		activity = a
	}
	protected def onActivityCreated(a: Activity, savedState: Bundle) = {
		if (activity == null) uiEvent = UiEvent.CREATE
		activity = a
		onStateChange(a, CREATED)
		if (!reconfiguring) {
			mMgr.onActivityCreate(a)
			if (savedState != null) mMgr.onActivityRestoreState(a, savedState)
		}
	}
	protected def onActivityStarted(a: Activity) = {
		activity = a
		onStateChange(a, STARTED)
	}
	protected def onActivityResumed(a: Activity) = {
		activity = a
		if (!visible) {
			visible = true
			uiEvent = UiEvent.SHOW
			KeepAliveService.onUiVisible(true)
		}
		reconfiguring = false
		onStateChange(a, RESUMED)
	}
	protected def onActivityPaused(a: Activity) = {
		if (activity == a) reconfiguring = a.isChangingConfigurations
		onStateChange(a, PAUSED)
	}
	protected def onActivityStopped(a: Activity) = {
		if (activity == a && !a.isChangingConfigurations) {
			visible = false
			uiEvent = UiEvent.HIDE
			KeepAliveService.onUiVisible(false)
		}
		onStateChange(a, STOPPED)
	}
	protected def onActivityDestroyed(a: Activity) = {
		if (activity == a) {
			reconfiguring = a.isChangingConfigurations
			if (!reconfiguring) {
				activity = null
				uiEvent = UiEvent.DESTROY
			}
		}
		onStateChange(a, DESTROYED)
		if (!reconfiguring) mMgr.onActivityDestroy(a)
	}
	protected def onActivitySaveInstanceState(a: Activity, savedState: Bundle) = {
		mMgr.onActivitySaveState(a, savedState)
	}

	private def onStateChange(a: Activity, newStt: Value): Unit = {
		val isCurrent = activity == null || activity == a
		if (isCurrent) state = newStt
		fireUiEvent()
		fireActivityEvent(ActivityEvent(a.hashCode, newStt, isCurrent, reconfiguring, a.isFinishing))
		uiEvent = UiEvent.NONE
	}
	private def reason(a: Activity): String = if (uiEvent != UiEvent.NONE) uiEvent.toString else if (a.isFinishing) "finishing" else if (reconfiguring) "reconfiguring" else "replacing"

	private def fireActivityEvent(e: ActivityEvent): Unit = {
		logV(e.toString)
		// todo fire event
	}
	private def fireUiEvent(): Unit = if (uiEvent != UiEvent.NONE) {
		// todo fire event
	}



	/* EVENT */
	case class ActivityEvent(hash: Int, state: ActivityState.Value, current: Boolean, reconfiguring: Boolean, finishing: Boolean) {
		override def toString: String = s"Current= $current;  reason= ${if (uiEvent != UiEvent.NONE) uiEvent.toString else if (finishing) "finishing" else if (reconfiguring) "reconfiguring" else "replacing"};  state= $state"
	}
}
