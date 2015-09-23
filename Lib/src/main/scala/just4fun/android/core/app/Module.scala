package just4fun.android.core.app

import java.util.concurrent.CancellationException

import android.app.Activity
import just4fun.android.core.async.FutureX.DummyImplicit2
import just4fun.utils.logger.Logger

import scala.collection.mutable
import scala.concurrent.Future
import scala.language.experimental.macros
import scala.util.{Failure, Try}

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.SystemClock.{uptimeMillis => deviceNow}
import just4fun.android.core.async.{FutureContext, FutureContextHolder, FutureX}
import just4fun.android.core.vars._
import Logger._

// todo def setStatusNotification
// todo def setLock / sync
// TODO onBeforeDeactivate Event
// todo def onNoRequests >> can re-enable suspending

object Module {

	object StateValue extends Enumeration {
		val ACTIVATING, SERVING, DEACTIVATING, STANDBY, FAILED, DESTROYED = Value
	}
	
	object RestoreAfterCrashPolicy extends Enumeration {
		val NEVER, IF_SELF_BOUND = Value
	}
	
	private[app] object StateCause extends Enumeration {
		val USER, BIND_FIRST, BIND_LAST, REQ_FIRST, REQ_LAST, UNIQ, CHECK, SERV, FAIL, PASS_OFF, PASS_ON, CH_ADD, CH_ACTIV, CH_DEL, CH_DEACT, PAR_ADD, PAR_DEL, PAR_ACTIV, PAR_FAIL = Value
		// Params: binding, req, activated, failed, suspend, ch_activate, ch_Deactivated, par_activated
	}
	
	private[app] def notAliveFailure[T]: Failure[T] = Failure(ModuleNotAliveException)
}





/* MODULE */

trait Module extends LifeCycleSubSystem with RelationsSubSystem with ServiceSubSystem with FutureContextHolder {
	import Module._
	import StateValue._
	import StateCause._
	import RestoreAfterCrashPolicy._
	val moduleID: String = getClass.getName
	implicit protected val thisModule: this.type = this
	implicit protected val appContext = Modules.context
	protected[this] val manager = Modules.mManager
	private[app] lazy val asyncVars = mutable.ListBuffer[AsyncVar[_]]()
	val isAfterCrash: Boolean = Prefs.contains(moduleID)
	//
	init()
	
	
	/* RELATIONS USER API */
	
	def isBound = bindings.nonEmpty
	
	protected[this] def bindSelf(): Unit = if (isAlive) {
		manager.moduleBind[this.type](this)
	}
	protected[this] def bind[M <: Module : Manifest]: M = macro Macros.bindS[M]
	protected[this] def unchecked_bind[M <: Module : Manifest]: M = {
		val s = manager.moduleBind[M](this)
		dependParentRemove(s)
		s
	}
	protected[this] def unbindSelf(): Unit = {
		manager.moduleUnbind[this.type](this)
	}
	protected[this] def unbind[M <: Module : Manifest]: Unit = macro Macros.unbindS[M]
	protected[this] def unchecked_unbind[M <: Module : Manifest]: Unit = {
		manager.moduleUnbind[M](this).foreach(dependParentRemove)
	}
	protected[this] def unbind[M <: Module : Manifest](s: M): Unit = {
		manager.moduleUnbind[M](s.getClass.asInstanceOf[Class[M]], this).foreach(dependParentRemove)
	}
	/** Uses module M. depends on module so that this module can be activated only if all parent modules are serving. */
	protected[this] final def dependOn[M <: Module : Manifest]: M = macro Macros.dependOn[M]
	protected[this] def unchecked_dependOn[M <: Module : Manifest]: M = {
		val s = manager.moduleBind[M](this)
		dependParentAdd(s)
		s
	}
	
	
	/* LIFECYCLE USER API */
	
	def state = syncLock synchronized (_state)
	def failure: Option[Throwable] = Option(_failure)

	def isAlive = state < FAILED && !terminating
	def isDead = !isAlive
	def isServing = state == SERVING
	def isInstantlyServing = state == SERVING && !servePaused
	def isStandby = state == STANDBY
	def isDestroying = terminating

	def isActivatingProgressPaused: Boolean = activatingPaused
	protected[this] final def pauseActivatingProgress(): Unit = pauseActivating(true)
	final def resumeActivatingProgress(): Unit = pauseActivating(false)
	
	def isStandbyModeOn: Boolean = standbyMode
	protected[this] def setStandbyMode(yeap: Boolean = true): Unit = if (yeap != standbyMode) {
		standbyMode = yeap
		updateState(if (yeap) PASS_OFF else PASS_ON)
	}
	protected[this] def setFailed(err: Throwable): Unit = {
		fail(err)
		updateState(FAIL)
	}
	protected[this] def updateState(): Boolean = {
		updateState(USER)
	}

	/* LIFECYCLE CALLBACKS to override */

	protected[this] def onActivatingStart(initial: Boolean): Unit = ()
	/** @return true - if activation is finished. false - if continue activation */
	protected[this] def onActivatingProgress(initial: Boolean, durationSec: Int): Boolean = true
	protected[this] def onActivatingFinish(initial: Boolean): Unit = ()
	protected[this] def onBeforeTerminalDeactivating(): Unit = ()
	protected[this] def onDeactivatingStart(terminal: Boolean): Unit = ()
	/** @return true - if deactivation is finished. false - if continue deactivation */
	protected[this] def onDeactivatingProgress(terminal: Boolean, durationSec: Int): Boolean = true
	protected[this] def onDeactivatingFinish(terminal: Boolean): Unit = ()
	/** Called when one of lifecycle methods throws an exception. That exception is passed here to handle it or let module fail.
	If dependency parent is failed the DependencyParentFailed(module) exception is passed.
	  @return None if module can proceed. Otherwise module is considered failed with returned error.
	  */
	protected[this] def onFailure(err: Throwable): Option[Throwable] = err match {
		//		case DependencyParentFailed(s) => Some(err)
		case e => Some(e)
	}
	protected[this] def onFailed(): Unit = ()
	protected[this] def onDestroy(): Unit = ()

	protected[app] def onConfigurationChanged(newConfig: Configuration): Unit = ()
	protected[this] def onTrimMemory(level: Int): Unit = ()
	
	
	/* SERVICE USER API */
	
	final def isServingRequestsPaused: Boolean = servePaused
	protected[this] final def pauseServingRequests(): Unit = pauseServing(true)
	protected[this] final def resumeServingRequests(): Unit = pauseServing(false)

	/* REQUEST WRAPPERS */
	protected[this] def serveOpt[T](code: => T): Option[T] = {
		if (isServing) Option(code) else None
	}
	protected[this] def serveTry[T](code: => T): Try[T] = {
		if (isServing) Try(code) else notAliveFailure
	}
	protected[this] def serveAsync[T](code: => T)(implicit futureContext: FutureContext): FutureX[T] = {
		serveRequest(new FutureXM[T].task(code))
	}
	protected[this] def serveAsync[T](code: => FutureX[T])(implicit futureContext: FutureContext, d: DummyImplicit): FutureX[T] = {
		serveRequest(new FutureXM[T].task(code))
	}
	protected[this] def serveAsync[T](code: => Future[T])(implicit futureContext: FutureContext, d: DummyImplicit, d2: DummyImplicit2 = null): FutureX[T] = {
		serveRequest(new FutureXM[T].task(code))
	}

	/* REQUEST QUEUE ACCESS */
	protected[this] def serveRequest[T](request: FutureXM[T]): FutureX[T] = {
		if (isAlive) {
			val first = requests.isEmpty
			requests += request
			if ((first || serveRequestsInParallel) && _state == SERVING && !servePaused) request.activate()
			else if (first && _state != SERVING && !servePaused) updateState(REQ_FIRST)
		}
		else request.reject()
		request
	}
	protected[this] def cancelRequests(filter: FutureXM[_] => Boolean = null): Unit = {
		if (filter != null) requests.withFilter(filter).foreach(_.cancel())
		else requests.foreach(_.cancel())
	}

	/* EVENT API */
	protected[this] def fireEvent[T <: ModuleEventListener : Manifest](e: ModuleEvent[T]): Unit = {
		bindings.foreach {
			case m: T => try e.onFired(m) catch loggedE
			case _ =>
		}
	}


	/* MISK USER API */
	
	def restoreAfterCrashPolicy: RestoreAfterCrashPolicy.Value = NEVER
	
	def dumpState(): String = s"state= ${state}; bindings= ${bindings.size};  depends= ${dependParents.size}; requests= ${requests.size};  passiveMode= ${standbyMode};  extremCycle? ${initialising || terminating}"
	
	
	/* INTERNAL API */
	protected[this] def init(): Unit = {
		Prefs(moduleID) = true
	}

	private[app] def trimMemory(level: Int): Unit = {
		import ComponentCallbacks2._
		if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) asyncVars.foreach(_.releaseValue())
		try onTrimMemory(level) catch loggedE
	}

	private[core] def registerAsyncVar(v: FileVar[_]) = asyncVars += v
	
	def isPredecessorOf(module: Module): Boolean = {
		terminating && getClass == module.getClass && this != module
	}
	
}







/* SERVICE */

trait ServiceSubSystem {
	this: Module =>
	import Module._
	import StateValue._
	import StateCause._
	protected[this] val requests = mutable.ListBuffer[FutureXM[_]]()
	protected[this] val serveRequestsInParallel = false
	// TODO set 20000
	protected[this] val serveStandbyLatency = 10000
	private[app] var servePaused = false
	
	/* INTERNAL API */
	
	private[app] def requestComplete(fx: FutureXM[_]): Unit = {
		requests -= fx
		if (requests.isEmpty) {
			val delay = if (standbyMode && bindings.nonEmpty && !servePaused) serveStandbyLatency else 500
			updateState(REQ_LAST, delay)
		}
		else {
			if (!serveRequestsInParallel && _state == SERVING && !servePaused) requests.head.activate()
			else if (servePaused && requests.forall(_.state == FutureX.State.NONE)) updateState(REQ_LAST)
		}
	}

	private[app] def pauseServing(yeap: Boolean): Unit = if (yeap != servePaused) {
		servePaused = yeap
		servePaused match {
			case false => if (_state == SERVING) {
				resumeRequests()
				fireEvent(new AbleToServeEvent)
			} else updateState(SERV)
			case true => if (_state == SERVING) {
				pauseRequests()
				if (standbyMode) updateState(SERV)
				fireEvent(new UnableToServeEvent)
			}
		}
	}
	private[app] def pauseRequests(): Unit = {
		requests.foreach(_.deactivate())
	}
	private[app] def resumeRequests(): Unit = serveRequestsInParallel match {
		case true => requests.foreach(_.activate())
		case false => requests.headOption.foreach(_.activate())
	}
}






/* RELATIONS */

trait RelationsSubSystem {
	this: Module =>
	import Module.StateCause._

	protected[this] val bindings = mutable.Set[Module]()
	protected[this] val dependChildren = mutable.Set[Module]()
	protected[this] val dependParents = mutable.Set[Module]()

	/* INTERNAL API */
	
	private[app] def bindingAdd(binding: Module): Unit = {
		val b = if (binding == null) this else binding
		b.detectCyclicBinding(this, b :: Nil) match {
			case Nil => val first = bindings.isEmpty
				bindings += b
				//				logI(s"binding Add> size= ${bindings.size};  $u")
				if (first) updateState(BIND_FIRST)
			case trace => throw CyclicUsageException(trace.map(_.moduleID).mkString(", "))
		}
	}
	private[app] def detectCyclicBinding(par: Module, trace: List[Module]): List[Module] = {
		if (this != par) bindings.foreach { chld =>
			val res = if (this == chld) Nil else if (par == chld) chld :: trace else chld.detectCyclicBinding(par, chld :: trace)
			if (res.nonEmpty) return res
		}
		Nil
	}
	private[app] def bindingRemove(binding: Module): Unit = {
		val b = if (binding == null) this else binding
		if (bindings.remove(b)) {
			val wasChild = dependChildren.remove(b)
			if (bindings.isEmpty) updateState(BIND_LAST)
			else if (wasChild) updateState(CH_DEL)
			//		logI(s"binding Remove> size= ${bindings.size}")
		}
		else if (b.isPredecessorOf(this)) updateState(UNIQ)
	}

	private[app] def dependParentAdd(m: Module): Unit = if (dependParents.add(m)) {
		m.dependChildAdd(this)
		updateState(PAR_ADD)
	}
	private[app] def dependParentRemove(m: Module): Unit = if (dependParents.remove(m)) {
		m.dependChildRemove(this)
		updateState(PAR_DEL)
	}

	private[app] def dependChildAdd(m: Module): Unit = if (dependChildren.add(m)) {
		updateState(CH_ADD)
	}
	private[app] def dependChildRemove(m: Module): Unit = if (dependChildren.remove(m)) {
		updateState(CH_DEL)
	}
}






/* LIFECYCLE STATE MACHINE */

protected[app] trait LifeCycleSubSystem {
	this: Module =>
	import Module._
	import StateCause._
	import StateValue._
	private[app] var _state: StateValue.Value = STANDBY
	private[app] var _failure: Throwable = null
	private[app] var syncLock = new Object
	private[this] var nextTik = 0L
	private[this] var startTime = 0L
	private[app] var activatingPaused = false
	private[app] var initialising = true
	private[app] var terminating = false
	private[app] var standbyMode = false
	
	/* CHECK STATE INTERNAL API */
	
	private[app] def updateState(cause: StateCause.Value, delay: Int = 0): Boolean = {
		val needUpdate = _state match {
			case SERVING => canDeactivate
			case STANDBY => canDestroy || canActivate
			case ACTIVATING => isAbandoned; true
			case DEACTIVATING => true
			case FAILED => canDestroy
			case _ => false
		}
		//			logV(s"REQUEST UPDATE  [$moduleID]  cause $cause;  state= ${_state};  canDestroy? ${canDestroy};  canDeactivate? ${canDeactivate};  canActivate? ${canActivate};  shouldActivate? ${shouldActivate};  >> $needUpdate;  ")
		//			logV(s"DUMP ${dumpState}")
		if (needUpdate) requestUpdateState(delay)
		needUpdate
	}
	private[this] def canActivate: Boolean = {
		shouldActivate && parentsReady
	}
	private[this] def shouldActivate: Boolean = {
		(!initialising || manager.moduleHasNoPredecessor) && (shouldServe || !standbyMode || bindings.isEmpty || dependChildren.exists(_.shouldParentActivate))
	}
	private[app] def shouldParentActivate: Boolean = {
		!canDestroy && shouldActivate
	}
	private[this] def canDestroy: Boolean = {
		terminating || (initialising && bindings.isEmpty && !shouldServe)
	}
	private[this] def checkTerminating(): Unit = {
		if (bindings.isEmpty) try onBeforeTerminalDeactivating() catch loggedE
	}
	private[this] def canDeactivate: Boolean = {
		!shouldServe && (bindings.isEmpty || (standbyMode && dependChildren.forall(_.canParentDeactivate)))
	}
	private[app] def canParentDeactivate: Boolean = {
		_state > STANDBY || (_state == STANDBY && !shouldActivate)
	}
	private[this] def shouldServe: Boolean = {
		requests.nonEmpty && (!servePaused || requests.exists(_.state == FutureX.State.EXEC))
	}
	private[this] def isAbandoned: Boolean = {
		if (canDestroy) fail(ModuleAbandonedException)
		_state == FAILED
	}
	private[this] def parentsReady: Boolean = dependParents.forall { par =>
		par.isServing || (par.isAlive match {
			case true => par.updateState(CH_ACTIV); false
			case false => unbind(par)
				handleError(ParentDependencyFailedException(par))
				_state != FAILED
		})
	}
	private[app] def pauseActivating(yeap: Boolean): Unit = if (_state == ACTIVATING) {
		activatingPaused = yeap
		if (activatingPaused) ModuleTiker.cancelUpdates
		else updateState(CHECK)
	}

	private[this] def requestUpdateState(delay: Long): Unit = if (!(_state == ACTIVATING && activatingPaused)) {
		nextTik = deviceNow() + delay
		ModuleTiker.requestUpdate(nextTik)
		//						logV(s"REQUEST UPDATE  for $delay ms")
	}

	/* CHANGE STATE INTERNAL API */
	
	private[app] def onUpdateState(): Boolean = syncLock synchronized {
		//						logV(s"                  UPDATE [$moduleID]   state= ${_state}; canDestroy? $canDestroy;   canDeactivate? $canDeactivate;  canActivate? $canActivate")
		val prev = _state
		//
		_state match {
			case STANDBY => if (canDestroy) destroy else if (canActivate) activate
			case ACTIVATING => if (!isAbandoned) activated
			case SERVING => checkTerminating(); if (canDeactivate) deactivate
			case DEACTIVATING => deactivated
			case FAILED => if (canDestroy) destroy
			case DESTROYED =>
			case _ => logE(s"Module [$moduleID] is in unexpected state [${_state}].")
		}
		//
		val changed = prev != _state
		if (changed && _state != DESTROYED) requestUpdateState(0)
		else if (_state == ACTIVATING || _state == DEACTIVATING) requestUpdateState(calcDelay())
		changed
	}
	private[this] def state_=(v: StateValue.Value): Unit = {
		logW(s"[$moduleID]:  ${_state} >  $v")
		_state = v
	}
	private[this] def activate: Unit = {
		state = ACTIVATING
		startFutureContext()
		trying(onActivatingStart(initialising))
		startTime = deviceNow()
		if (!activatingPaused) activated
	}
	private[this] def isActivated: Boolean = {
		val time = (deviceNow() - startTime) / 1000
		trying(onActivatingProgress(initialising, time.toInt))
	}
	private[this] def activated: Unit = if (isActivated) {
		state = SERVING
		trying(onActivatingFinish(initialising))
		resumeRequests()
		dependChildren.foreach(_.updateState(PAR_ACTIV))
		if (!servePaused) fireEvent(new AbleToServeEvent)
		initialising = false
	}
	private[this] def deactivate: Unit = {
		state = DEACTIVATING
		if (bindings.isEmpty) terminating = true
		if (!servePaused && !terminating) fireEvent(new UnableToServeEvent)
		trying(onDeactivatingStart(terminating))
		startTime = deviceNow()
		deactivated
	}
	private[this] def isDeactivated: Boolean = {
		val time = (deviceNow() - startTime) / 1000
		trying(onDeactivatingProgress(terminating, time.toInt))
	}
	private[this] def deactivated: Unit = if (isDeactivated) {
		state = STANDBY
		trying(onDeactivatingFinish(terminating))
		quitFutureContext(true)
		dependParents.foreach(_.updateState(CH_DEACT))
	}
	private[this] def trying[T](code: => T): T = try code catch {
		case err: Throwable => handleError(err)
			null.asInstanceOf[T]
	}
	private[this] def handleError(err: Throwable): Unit = onFailure(err) match {
		case Some(e) => val prev = _state
			if (err != e && e.getCause == null) e.initCause(err)
			fail(e)
			logE(e, s"Module [$moduleID] is failed in state [$prev]. ")
		case None => logE(err, s"Module [$moduleID] error is ignored.")
	}
	private[app] def fail(err: Throwable): Unit = syncLock synchronized {
		if (_failure == null) {
			state = FAILED
			_failure = err
			try onFailed() catch {case e: Throwable => logE(e, s"Module [$moduleID] error is ignored.")}
			cancelRequests()
			quitFutureContext()
			activatingPaused = false
			servePaused = false
			standbyMode = false
			dependParents.foreach(d => unbind(d))
			dependChildren.foreach(_.updateState(PAR_FAIL))
			fireEvent(new UnableToServeEvent)
		}
	}
	private[this] def destroy: Unit = {
		try {
			state = DESTROYED
			manager.moduleDestroyed(thisModule)
			Prefs.remove(moduleID)
			onDestroy()
		}
		catch {case e: Throwable => logE(e, s"Module [$moduleID] error is ignored.")}
		finally {
			ModuleTiker.cancelUpdates
			cancelRequests()
			quitFutureContext()
			dependParents.clear()
			dependChildren.clear()
			bindings.clear()
			asyncVars.clear()
		}
	}
	private[this] def calcDelay(): Int = {
		val past = deviceNow() - startTime
		val delay = if (past < 2000) 200
		else if (past < 10000) 1000
		else if (past < 60000) 5000
		else 10000
		val mult = if (_state == DEACTIVATING) 2 else 1
		delay * mult
	}
}






/* FUTURE */

class FutureXM[T](implicit module: Module) extends FutureX[T] {
	override def finishExecute(v: Try[T]): Unit = {
		super.finishExecute(v)
		module.requestComplete(this)
	}
	def reject(): Unit = {
		super.finishExecute(Module.notAliveFailure)
	}
}






/* EVENTS */

trait ModuleEventListener


abstract class ModuleEvent[T <: ModuleEventListener : Manifest] {
	implicit val source: Module
	def onFired(listener: T): Unit
}







/* SERVABILITY EVENT */
// TODO
trait ModuleServabilityListener extends ModuleEventListener {
	def onAbleToServe(e: AbleToServeEvent)
	def onUnableToServe(e: UnableToServeEvent)
}

abstract class ModuleServabilityEvent extends ModuleEvent[ModuleServabilityListener] {
	override def onFired(listener: ModuleServabilityListener): Unit = this match {
		case e: AbleToServeEvent => listener.onAbleToServe(e)
		case e: UnableToServeEvent => listener.onUnableToServe(e)
	}
}

class AbleToServeEvent(implicit val source: Module) extends ModuleServabilityEvent
class UnableToServeEvent(implicit val source: Module) extends ModuleServabilityEvent
