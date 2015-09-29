package just4fun.android.core.app

import java.lang
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
// todo def onNoRequests >> can re-enable suspending

object Module {

	object StateValue extends Enumeration {
		type StateValue = Value
		val ACTIVATING, SERVING, DEACTIVATING, STANDBY, FAILED, DESTROYED = Value
	}
	
	private[app] object StateCause extends Enumeration {
		type StateCause = Value
		val USER, BIND_FIRST, BIND_LAST, REQ_FIRST, REQ_LAST, PREDEC, UNPAUSE, SERV, FAIL, STBY_OFF, STBY_ON, CH_ADD, CH_ACTIV, CH_DEL, CH_DEACT, PAR_ADD, PAR_DEL, PAR_ACTIV = Value
	}
	
	private[app] def notAliveFailure[T]: Failure[T] = Failure(ModuleNotActivatedException)
}





/* MODULE */

trait Module extends LifeCycleSubSystem with RelationsSubSystem with ServiceSubSystem with FutureContextHolder {
	import Module._
	import StateValue._
	import StateCause._
	implicit protected val thisModule: this.type = this
	implicit protected val appContext = Modules.context
	lazy val moduleID: String = getClass.getName
	protected[this] val manager = Modules.mManager
	private[app] lazy val asyncVars = mutable.ListBuffer[AsyncVar[_]]()
	private[app] var hasPredecessor = manager.moduleHasPredecessor
	private[app] var lock = new Object
	val isAfterAbort: Boolean = Prefs.contains(moduleID)
	private[app] var restore = try {getClass.getDeclaredConstructor(classOf[Boolean]); true} catch {case _ => false}
	//
	init()

	/* RELATIONS USER API */

	def isBound = bindings.nonEmpty
	def nonBound = bindings.isEmpty

	protected[this] def bindSelf: Unit = if (isAlive) {
		try manager.moduleBind[this.type](this)
		catch {case e: Throwable => setFailed(BindingModuleError("self", e))}
	}
	protected[this] def bind[M <: Module : Manifest]: M = macro Macros.bindS[M]
	protected[this] def unchecked_bind[M <: Module : Manifest]: M = {
		try manager.moduleBind[M](this)
		catch {case e: Throwable => setFailed(BindingModuleError(implicitly[Manifest[M]].runtimeClass.getName, e)); null.asInstanceOf[M]}
	}
	protected[this] def unbindSelf: Unit = {
		manager.moduleUnbind[this.type](this)
	}
	protected[this] def unbind[M <: Module : Manifest]: Unit = macro Macros.unbindS[M]
	protected[this] def unchecked_unbind[M <: Module : Manifest]: Unit = {
		manager.moduleUnbind[M](this)
	}
	protected[this] def unbind[M <: Module : Manifest](s: M): Unit = {
		manager.moduleUnbind[M](s.getClass.asInstanceOf[Class[M]], this)
	}
	/** */
	protected[this] final def bindSync[M <: Module : Manifest]: M = macro Macros.bindSync[M]
	protected[this] def unchecked_bindSync[M <: Module : Manifest]: M = {
		try manager.moduleBind[M](this, true)
		catch {case e: Throwable => setFailed(BindingModuleError(implicitly[Manifest[M]].runtimeClass.getName, e)); null.asInstanceOf[M]}
	}


	/* LIFECYCLE USER API */

	def state = lock synchronized (_state)
	def failure: Option[Throwable] = Option(_failure)

	def isAlive = state < FAILED && !terminating
	def isDead = !isAlive
	def isActivating = state == ACTIVATING
	def isDeactivating = state == DEACTIVATING
	def isServing = state == SERVING
	def isServingNonPaused = state == SERVING && !servePaused
	def isStandby = state == STANDBY
	def isDestroying = terminating

	def isActivatingProgressPaused: Boolean = activatingPaused
	protected[this] final def pauseActivatingProgress(): Unit = if (isActivating) {
		activatingPaused = true
		expectUpdate = false
		ModuleTiker.cancelUpdates
	}
	final def resumeActivatingProgress(): Unit = {
		activatingPaused = false
		updateState(UNPAUSE)
	}

	def isStandbyModeOn: Boolean = standbyMode
	protected[this] def setStandbyMode(yeap: Boolean = true): Unit = if (yeap != standbyMode) {
		standbyMode = yeap
		updateState(if (yeap) STBY_OFF else STBY_ON)
	}
	protected[this] def setFailed(err: Throwable): Unit = {
		logE(err, s"Module [$moduleID] is failed in state [${_state}] . ")
		fail(err)
		updateState(FAIL)
	}
	protected[this] def updateState(): Unit = {
		updateState(USER)
	}

	/* LIFECYCLE CALLBACKS to override */

	protected[this] def onActivatingStart(initial: Boolean): Unit = ()
	/** @return true - if activation is finished. false - if continue activation */
	protected[this] def onActivatingProgress(initial: Boolean, durationSec: Int): Boolean = true
	protected[this] def onActivatingFinish(initial: Boolean): Unit = ()
	protected[this] def onBeforeDeactivating(terminal: Boolean): Unit = ()
	protected[this] def onDeactivatingStart(terminal: Boolean): Unit = ()
	/** @return true - if deactivation is finished. false - if continue deactivation */
	protected[this] def onDeactivatingProgress(terminal: Boolean, durationSec: Int): Boolean = true
	protected[this] def onDeactivatingFinish(terminal: Boolean): Unit = ()
	protected[this] def onFailure(err: Throwable): Option[Throwable] = err match {
		case e => Some(e)
	}
	protected[this] def onFailed(): Unit = ()
	protected[this] def onDestroy(): Unit = ()

	protected[app] def onConfigurationChanged(newConfig: Configuration): Unit = ()
	protected[this] def onTrimMemory(level: Int): Unit = ()

	
	/* SERVICE USER API */
	
	final def isServingRequestsPaused: Boolean = servePaused
	protected[this] final def pauseServingRequests(): Unit = if (!servePaused) {
		servePaused = true
		if (_state == SERVING) {
			pauseRequests()
			if (standbyMode) updateState(SERV)
			fireEvent(new UnableToServeEvent)
		}
	}
	protected[this] final def resumeServingRequests(): Unit = if (servePaused) {
		servePaused = false
		if (_state == SERVING) {
			resumeRequests()
			fireEvent(new AbleToServeEvent)
		}
		else updateState(SERV)
	}

	/* REQUEST WRAPPERS */
	protected[this] def serveOpt[T](code: => T): Option[T] = {
		if (isServing) Option(code) else None
	}
	protected[this] def serveTry[T](code: => T): Try[T] = {
		if (isServing) Try(code) else notAliveFailure
	}
	protected[this] def serveAsync[T](code: => T)(implicit futureContext: FutureContext): FutureX[T] = {
		requestAdd(new FutureXM[T].task(code))
	}
	protected[this] def serveAsync[T](code: => FutureX[T])(implicit futureContext: FutureContext, d: DummyImplicit): FutureX[T] = {
		requestAdd(new FutureXM[T].task(code))
	}
	protected[this] def serveAsync[T](code: => Future[T])(implicit futureContext: FutureContext, d: DummyImplicit, d2: DummyImplicit2 = null): FutureX[T] = {
		requestAdd(new FutureXM[T].task(code))
	}
	
	/* REQUEST QUEUE ACCESS */
	protected[this] def serveRequest[T](request: FutureXM[T]): FutureX[T] = {
		requestAdd(request)
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

	/* RESTORE AFTER CRASH API */
	protected[this] def restorableAfterAbort: Boolean = restore
	protected[this] def restorableAfterAbort_=(v: Boolean): Unit = if (restore != v) {
		restore = v
		if (!v || bindings.contains(this)) manager.updateRestorables(getClass, v)
	}
	
	/* MISK USER API */
	
	def dumpState(): String = s"state= ${state}; bindings= ${bindings.size};  syncChildren= ${syncChildren.size};  syncParents= ${syncParents.size}; requests= ${requests.size};  passiveMode= $standbyMode;  extremCycle? ${initialising || terminating}"
	
	
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
		terminating && getClass == module.getClass && this.ne(module)
	}
	
}







/* SERVICE */

trait ServiceSubSystem {
	this: Module =>
	import Module._
	import StateValue._
	import StateCause._
	// TODO QUEUE
	protected[this] val requests = mutable.ListBuffer[FutureXM[_]]()
	protected[this] val serveInParallel = false
	// TODO set 20000
	protected[this] val serveStandbyLatency = 10000
	private[app] var servePaused = false
	
	/* INTERNAL API */
	
	private[app] def requestAdd[T](request: FutureXM[T]): FutureX[T] = {
		if (isAlive) {
			val first = requests.isEmpty
			requests += request
			if (!servePaused) {
				if ((first || serveInParallel) && _state == SERVING) request.activate()
				else if (first && _state == STANDBY) updateState(REQ_FIRST)
			}
		}
		else request.reject()
		request
	}
	private[app] def requestComplete(fx: FutureXM[_]): Unit = {
		requests -= fx
		if (requests.isEmpty) {
			val delay = if (standbyMode && bindings.nonEmpty && !servePaused) serveStandbyLatency else 500
			updateState(REQ_LAST, delay)
		}
		else if (servePaused && standbyMode) updateState(REQ_LAST)
		else if (!servePaused && !serveInParallel) requests.head.activate()
	}
	
	private[app] def pauseRequests(): Unit = {
		requests.foreach(_.deactivate())
	}
	private[app] def resumeRequests(): Unit = serveInParallel match {
		case true => requests.foreach(_.activate())
		case false => requests.headOption.foreach(_.activate())
	}
}






/* RELATIONS */

trait RelationsSubSystem {
	this: Module =>
	import Module.StateCause._
	
	protected[this] val bindings = mutable.Set[Module]()
	protected[this] val syncChildren = mutable.Set[Module]()
	protected[this] val syncParents = mutable.Set[Module]()
	
	/* INTERNAL API */
	
	private[app] def bindingAdd(binding: Module, sync: Boolean): Unit = if (!bindings.contains(binding)) {
		val b = if (binding == null) this else binding
		if (isAlive) b.detectCyclicBinding(this, b :: Nil) match {
			case Nil => val first = bindings.isEmpty
				bindings += b
				if (first) updateState(BIND_FIRST)
				if (sync && b.ne(this)) syncChildAdd(b) else syncChildRemove(b)
			case trace => b.handleError(CyclicUsageException(trace.map(_.moduleID).mkString(", ")))
		}
		else b.handleError(BindingDeadModuleException(this))
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
			if (bindings.isEmpty) updateState(BIND_LAST)
			syncChildRemove(b)
		}
		else if (hasPredecessor && b.isPredecessorOf(this)) {
			hasPredecessor = false
			updateState(PREDEC)
		}
	}
	
	private[this] def syncChildAdd(m: Module): Unit = if (syncChildren.add(m)) {
		m.syncParentAdd(this)
		updateState(CH_ADD)
	}
	private[this] def syncChildRemove(m: Module): Unit = if (syncChildren.remove(m)) {
		m.syncParentRemove(this)
		updateState(CH_DEL)
	}
	private[app] def syncParentAdd(m: Module): Unit = {
		syncParents.add(m)
	}
	private[app] def syncParentRemove(m: Module): Unit = {
		if (syncParents.remove(m)) updateState(PAR_DEL)
	}
}






/* LIFECYCLE STATE MACHINE */

protected[app] trait LifeCycleSubSystem {
	this: Module =>
	import Module._
	import StateCause._
	import StateValue._
	private[app] var _state: StateValue = STANDBY
	private[app] var _failure: Throwable = null
	private[app] var standbyMode = false
	private[app] var activatingPaused = false
	private[app] var initialising = true
	private[app] var terminating = false
	private[app] var expectUpdate = false
	private[this] var progressStartTime = 0L

	/* CHECK STATE INTERNAL API */
	
	private[app] def updateState(cause: StateCause, delay: Int = 0): Unit = lock synchronized {
		if (!expectUpdate) {
			val needUpdate = cause match {
				case null => true
				case _ => _state match {
					case ACTIVATING => !activatingPaused
					case SERVING => deactivate_?
					case DEACTIVATING => true
					case STANDBY => destroy_? || activate_?
					case FAILED => nonBound
					case _ => false
				}
			}
			//			logV(s"REQUEST UPDATE  [$moduleID]  cause $cause;  state= ${_state};  canDestroy? ${canDestroy};  canDeactivate? ${canDeactivate};  canActivate? ${canActivate};  shouldActivate? ${shouldActivate};  >> $needUpdate;  ")
			//			logV(s"DUMP ${dumpState}")
			if (needUpdate) {
				expectUpdate = delay <= 0
				ModuleTiker.requestUpdate(deviceNow() + delay)
			}
		}
	}
	private[app] def destroy_? : Boolean = {
		terminating || (initialising && bindings.isEmpty && !needServe)
	}
	private[this] def activate_? : Boolean = {
		needActivate && parentsLetActivate
	}
	private[this] def deactivate_? : Boolean = {
		!needServe && (bindings.isEmpty || (standbyMode && childrenLetDeactivate))
	}
	private[app] def needActivate: Boolean = {
		!waitPredecessor && (!standbyMode || needTerminate || needServe || childNeedActivate)
	}
	private[this] def needServe: Boolean = {
		requests.nonEmpty && (!servePaused || hasExecutingRequest)
	}
	private[this] def hasExecutingRequest: Boolean = {
		requests.exists(_.state == FutureX.State.EXEC)
	}
	private[this] def needTerminate: Boolean = {
		bindings.isEmpty // && standbyMode
	}
	private[this] def waitPredecessor: Boolean = {
		initialising && hasPredecessor
	}
	private[app] def childNeedActivate: Boolean = syncChildren.exists { child =>
		!child.destroy_? && child.needActivate
	}
	private[app] def childrenLetDeactivate: Boolean = syncChildren.forall { child =>
		child._state > STANDBY || (child._state == STANDBY && !child.needActivate)
	}
	private[this] def parentsLetActivate: Boolean = syncParents.forall { parent =>
		if (parent._state == STANDBY) parent.updateState(CH_ACTIV)
		parent._state == SERVING
	}
	
	/* CHANGE STATE INTERNAL API */
	
	private[app] def onUpdateState(): Boolean = lock synchronized {
		//						logV(s"                  UPDATE [$moduleID]   state= ${_state}; canDestroy? $canDestroy;   canDeactivate? $canDeactivate;  canActivate? $canActivate")
		expectUpdate = false
		val prev = _state
		//
		_state match {
			case ACTIVATING => activated_>>
			case SERVING => if (checkTerminating && deactivate_?) deactivate_>>
			case DEACTIVATING => deactivated_>>
			case STANDBY => if (destroy_?) destroy_>> else if (activate_?) activate_>>
			case FAILED => if (nonBound) destroy_>>
			case DESTROYED =>
			case _ => logE(s"Module [$moduleID] is in unexpected state [${_state}].")
		}
		//
		val changed = prev != _state
		if (changed && _state != DESTROYED) updateState(null, 0)
		else if (_state == DEACTIVATING || (_state == ACTIVATING && !activatingPaused)) updateState(null, calcDelay())
		changed
	}
	private[this] def state_=(v: StateValue): Unit = {
		logW(s"[$moduleID]:  ${_state} >  $v")
		_state = v
	}
	private[this] def activate_>> : Unit = {
		state = ACTIVATING
		startFutureContext()
		trying(onActivatingStart(initialising))
		progressStartTime = deviceNow()
		activated_>>
	}
	private[this] def activated_? : Boolean = !activatingPaused && {
		trying(onActivatingProgress(initialising, (progressDelay / 1000).toInt))
	}
	private[this] def activated_>> : Unit = if (activated_?) {
		state = SERVING
		trying(onActivatingFinish(initialising))
		resumeRequests()
		syncChildren.foreach(_.updateState(PAR_ACTIV))
		if (!servePaused) fireEvent(new AbleToServeEvent)
		initialising = false
	}
	private[this] def deactivate_>> : Unit = {
		state = DEACTIVATING
		if (bindings.isEmpty) terminating = true
		if (!servePaused && !terminating) fireEvent(new UnableToServeEvent)
		trying(onDeactivatingStart(terminating))
		progressStartTime = deviceNow()
		deactivated_>>
	}
	private[this] def deactivated_? : Boolean = {
		trying(onDeactivatingProgress(terminating, (progressDelay / 1000).toInt))
	}
	private[this] def deactivated_>> : Unit = if (deactivated_?) {
		state = STANDBY
		trying(onDeactivatingFinish(terminating))
		quitFutureContext(true)
		syncParents.foreach(_.updateState(CH_DEACT))
	}
	private[this] def trying[T](code: => T): T = try code catch {
		case err: Throwable => handleError(err)
			null.asInstanceOf[T]
	}
	private[app] def handleParentFailure(parent: Module): Unit = {
		unbind(parent)
		handleError(SyncParentFailedException(parent))
	}
	private[app] def handleError(err: Throwable): Unit = {
		try onFailure(err) match {
			case Some(e) => val prev = _state
				if (err.ne(e) && e.getCause == null) e.initCause(err)
				fail(e)
				logE(e, s"Module [$moduleID] is failed in state [$prev]. ")
			case None => logE(err, s"Module [$moduleID] in state [${_state}] error caught but ignored.")
		} catch loggedE
	}
	private[app] def fail(err: Throwable): Unit = lock synchronized {
		if (_failure == null) {
			state = FAILED
			_failure = err
			try onFailed() catch loggedE
			cancelRequests()
			quitFutureContext()
			manager.moduleUnbindFromAll
			syncChildren.foreach(_.handleParentFailure(this))
			fireEvent(new UnableToServeEvent)
		}
	}
	private[this] def destroy_>> : Unit = {
		state = DESTROYED
		try onDestroy() catch loggedE
		manager.moduleDestroyed
		Prefs.remove(moduleID)
		ModuleTiker.cancelUpdates
		cancelRequests()
		quitFutureContext()
		syncParents.clear()
		syncChildren.clear()
		bindings.clear()
		asyncVars.clear()
	}
	private[this] def checkTerminating: Boolean = {
		try onBeforeDeactivating(bindings.isEmpty) catch loggedE
		true
	}
	protected[this] final def progressDelay(): Long = {
		deviceNow() - progressStartTime
	}
	protected[this] def calcDelay(): Int = {
		val past = progressDelay()
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
