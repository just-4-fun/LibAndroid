package just4fun.android.core.app

import java.lang
import java.util.concurrent.CancellationException

import android.app.Activity
import just4fun.android.core.async.FutureX.DummyImplicit2
import just4fun.utils.logger.Logger._

import scala.collection.mutable
import scala.concurrent.Future
import scala.language.experimental.macros
import scala.util.{Failure, Try}

import android.content.{Context, ComponentCallbacks2}
import android.content.res.Configuration
import android.os.SystemClock.{uptimeMillis => deviceNow}
import just4fun.android.core.async.{FutureContext, FutureContextHolder, FutureX}
import just4fun.android.core.vars._

// todo def onNoRequests >> can re-enable suspending

object ModuleState extends Enumeration {
	type StateValue = Value
	val ACTIVATING, SERVING, DEACTIVATING, STANDBY, FAILED, DESTROYED = Value
}

private[app] object StateChangeCause extends Enumeration {
	type StateChangeCause = Value
	val USER, BIND_FIRST, BIND_LAST, REQ_FIRST, REQ_LAST, PREDEC, UNPAUSE, SERV, FAIL, STBY_OFF, STBY_ON, CH_ADD, CH_ACTIV, CH_DEL, CH_DEACT, PAR_ADD, PAR_DEL, PAR_ACTIV = Value
}




/* MODULE */

trait Module extends LifeCycleSubSystem with RelationsSubSystem with ServiceSubSystem with FutureContextHolder {
	import ModuleState._
	import StateChangeCause._
	implicit protected val thisModule: this.type = this
	implicit protected val appContext = Modules.context
	private[this] implicit val cache = Prefs.syscache
	lazy val moduleID: String = getClass.getName
	protected[this] val manager = Modules.mManager
	private[app] lazy val asyncVars = mutable.ListBuffer[AsyncVar[_]]()
	private[app] var hasPredecessor = manager.moduleHasPredecessor
	private[app] var lock = new Object
	val isAfterAbort: Boolean = Prefs.contains(moduleID)
	private[app] var restore = try {getClass.getDeclaredConstructor(classOf[Boolean]); true} catch {case _: Throwable => false}
	//
	init()

	/* RELATIONS USER API */

	def isBound = bindings.nonEmpty
	def nonBound = bindings.isEmpty

	protected[this] def bindSelf: Unit = {
		tryBind[this.type]()
	}
	protected[this] def bind[M <: Module : Manifest]: M = macro Macros.bindS[M]
	protected[this] def unchecked_bind[M <: Module : Manifest]: M = {
		tryBind[M]()
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
	protected[this] final def bindSync[M <: Module : Manifest]: M = macro Macros.bindSync[M]
	protected[this] def unchecked_bindSync[M <: Module : Manifest]: M = {
		tryBind[M](true)
	}
	protected[this] def onModuleBound(module: Module, sync: Boolean): Unit = {}
	protected[this] def onModuleUnbound(module: Module): Unit = {}

	/* LIFECYCLE USER API */

	def state = lock synchronized (_state)
	def failure: Option[Throwable] = Option(_failure)

	def isAlive = state < FAILED && !terminating
	def isDead = !isAlive
	def isDestroying = terminating
	def inActivatingState = state == ACTIVATING
	def inDeactivatingState = state == DEACTIVATING
	def inServingState = state == SERVING
	def inServingStateNonPaused = state == SERVING && !servePaused
	def inStandbyState = state == STANDBY

	def isActivatingProgressPaused: Boolean = !activatingNonPaused
	protected[this] final def pauseActivatingProgressFor(future: FutureX[_]): Unit = {
		future.onCompleteInMainThread(_ => resumeActivatingProgress())
	}
	protected[this] final def pauseActivatingProgress(): Unit = if (inStandbyState || inActivatingState) {
		pauseActivation(true, 1)
	}
	final def resumeActivatingProgress(): Unit = {
		pauseActivation(false, 1)
	}

	def standbyMode: Boolean = standby
	protected[this] def standbyMode_=(yeap: Boolean): Unit = if (yeap != standby) {
		standby = yeap
		updateState(if (yeap) STBY_OFF else STBY_ON)
	}
	protected[this] def setFailed(err: Throwable): Unit = {
		fail(err)
		updateState(FAIL)
	}
	protected[this] def updateState(): Unit = {
		updateState(USER)
	}
	protected def reActivate(): Unit = {
		reactivating = 1
		if (standby) reactivating |= 2
		if (servePaused) reactivating |= 4
		standbyMode = true
		pauseRequestsServing()
		syncChildren.foreach(_.reActivate())
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
	protected[this] def onFailure(err: Throwable): Option[Throwable] = err match {
		case e => Some(e)
	}
	protected[this] def onFailed(): Unit = ()
	protected[this] def onDestroy(): Unit = ()

	protected[this] def onConfigurationChanged(newConfig: Configuration): Unit = ()
	protected[this] def onTrimMemory(level: Int): Unit = ()


	/* SERVICE USER API */

	final def isRequestsServingPaused: Boolean = servePaused
	protected[this] final def pauseRequestsServing(): Unit = if (!servePaused) {
		servePaused = true
		if (_state == SERVING) {
			pauseRequests()
			if (standby) updateState(SERV)
			fireEvent(new UnableToServeEvent)
		}
	}
	protected[this] final def resumeRequestsServing(): Unit = if (servePaused) {
		servePaused = false
		if (_state == SERVING) {
			resumeRequests()
			fireEvent(new AbleToServeEvent)
		}
		else updateState(SERV)
	}

	/* REQUEST WRAPPERS */
	protected[this] def serveOpt[T](code: => T): Option[T] = {
		if (inServingState) Option(code) else None
	}
	protected[this] def serveTry[T](code: => T): Try[T] = {
		if (inServingState) Try(code) else Failure(ModuleServiceException())
	}
	protected[this] def serveAsync[T](code: => T)(implicit futureContext: FutureContext): FutureX[T] = {
		requestAdd(new ModuleRequest[T].task(code))
	}
	protected[this] def serveAsync[T](code: => FutureX[T])(implicit futureContext: FutureContext, d: DummyImplicit): FutureX[T] = {
		requestAdd(new ModuleRequest[T].task(code))
	}
	protected[this] def serveAsync[T](code: => Future[T])(implicit futureContext: FutureContext, d: DummyImplicit, d2: DummyImplicit2 = null): FutureX[T] = {
		requestAdd(new ModuleRequest[T].task(code))
	}

	/* REQUEST QUEUE ACCESS */
	protected[this] def serveRequest[T](request: ModuleRequest[T]): FutureX[T] = {
		requestAdd(request)
	}
	protected[this] def cancelRequests(filter: ModuleRequest[_] => Boolean = null): Unit = filter match {
		case null => requests.foreach(_.cancel())
		case _ => requests.withFilter(filter).foreach(_.cancel())
	}
	protected[this] def onRequestComplete(fx: ModuleRequest[_]): Unit = {}


	/* EVENT API */
	protected[this] def fireEvent[T <: ModuleEventListener : Manifest](e: ModuleEvent[T], module: Module = null): Unit = {
		(if (module == null) bindings else List(module)).foreach {
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

	/* PERMISSIONS API */
	protected[this] def permissions: Seq[PermissionExtra] = Nil
	protected[this] def hasPermission(permission: String): Boolean = {
		manager.hasPermission(permission)
	}
	protected[this] def requestPermission(permission: String): Future[Boolean] = {
		manager.requestDynamicPermission(permission)
	}

	/* MISC USER API */

	def systemService[T](contextServiceName: String): Option[T] = {
		try Option(appContext.getSystemService(contextServiceName).asInstanceOf[T]) catch loggedE(None)
	}
	def dumpState(): String = s"state= ${state}; bindings= ${bindings.size};  syncChildren= ${syncChildren.size};  syncParents= ${syncParents.size}; requests= ${requests.size};  passiveMode= $standby;  extremCycle? ${initialising || terminating}"


	/* INTERNAL API */
	protected[this] def init(): Unit = {
		if (!isAfterAbort) Prefs(moduleID) = 1
	}

	private[app] def trimMemory(level: Int): Unit = {
		import ComponentCallbacks2._
		if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) asyncVars.foreach(_.releaseValue())
		try onTrimMemory(level) catch loggedE
	}
	private[app] def configurationChanged(newConfig: Configuration): Unit = {
		try onConfigurationChanged(newConfig) catch loggedE
	}

	private[core] def registerAsyncVar(v: AsyncVar[_]) = asyncVars += v

	def isPredecessorOf(module: Module): Boolean = {
		terminating && getClass == module.getClass && this.ne(module)
	}
	private[app] def permissions_intr: Seq[PermissionExtra] = permissions

}







/* SERVICE */

trait ServiceSubSystem {
	this: Module =>
	import ModuleState._
	import StateChangeCause._
	// TODO QUEUE
	protected[this] val requests = mutable.ListBuffer[ModuleRequest[_]]()
	// TODO set 20000
	protected[this] val standbyLatency = 10000
	private[app] var servePaused = false

	/* INTERNAL API */

	private[app] def requestAdd[T](request: ModuleRequest[T]): FutureX[T] = {
		if (isAlive) {
			val first = requests.isEmpty
			requests += request
			if (first && !servePaused) {
				if (_state == SERVING) request.activate()
				else if (_state == STANDBY) updateState(REQ_FIRST)
			}
		}
		else request.cancel(ModuleServiceException())
		request
	}
	private[app] def requestComplete(fx: ModuleRequest[_]): Unit = {
		try onRequestComplete(fx) catch loggedE
		requests -= fx
		if (requests.isEmpty) {
			val delay = if (standby && bindings.nonEmpty && !servePaused) standbyLatency else 0
			updateState(REQ_LAST, delay)
		}
		else if (servePaused && standby) updateState(REQ_LAST)
	}

	private[app] def pauseRequests(): Unit = {
		requests.foreach(_.deactivate())
	}
	private[app] def resumeRequests(): Unit = {
		requests.foreach(_.activate())
	}
}





/* RELATIONS */

trait RelationsSubSystem {
	this: Module =>
	import StateChangeCause._

	protected[this] val bindings = mutable.Set[Module]()
	protected[this] val syncChildren = mutable.Set[Module]()
	protected[this] val syncParents = mutable.Set[Module]()

	/* INTERNAL API */

	private[app] def tryBind[M <: Module : Manifest](sync: Boolean = false): M = {
		try manager.moduleBind[M](this, sync)
		catch {
			case e: ModuleBindingException => handleError(e); null.asInstanceOf[M]
			case e: Throwable => handleError(new ModuleBindingException(implicitly[Manifest[M]].runtimeClass, e)); null.asInstanceOf[M]
		}
	}
	private[app] def bindingAdd(binding: Module, sync: Boolean): Unit = if (!bindings.contains(binding)) {
		val b = if (binding == null) this else binding
		if (isAlive) b.detectCyclicBinding(this, b :: Nil) match {
			case Nil => val first = bindings.isEmpty
				bindings += b
				if (first) updateState(BIND_FIRST)
				if (sync && b.ne(this)) syncChildAdd(b) else syncChildRemove(b)
				try onModuleBound(b, sync) catch loggedE
			case trace => throw new CyclicBindingException(getClass, trace.map(_.moduleID).mkString(", "))
		}
		else throw new DeadModuleBindingException(getClass)
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
			try onModuleUnbound(b) catch loggedE
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
	import StateChangeCause._
	import ModuleState._
	private[app] var _state: StateValue = STANDBY
	private[app] var _failure: Throwable = null
	private[app] var standby = false
	private[app] var activatingPaused = 0
	private[app] var initialising = true
	private[app] var terminating = false
	private[app] var expectUpdate = false
	private[app] var reactivating = 0
	private[this] var progressStartTime = 0L

	/* CHECK STATE INTERNAL API */

	private[app] def updateState(cause: StateChangeCause, delay: Int = 0): Unit = lock synchronized {
		if (!expectUpdate) {
			val needUpdate = cause match {
				case null => true
				case _ => _state match {
					case ACTIVATING => activatingNonPaused
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
		activatingNonPaused && needActivate && parentsLetActivate
	}
	private[this] def deactivate_? : Boolean = {
		!needServe && (bindings.isEmpty || (standby && childrenLetDeactivate))
	}
	private[app] def needActivate: Boolean = {
		!waitPredecessor && (!standby || needTerminate || needServe || childNeedActivate)
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
			case SERVING => if (deactivate_? && checkTerminating) deactivate_>>
			case DEACTIVATING => deactivated_>>
			case STANDBY => if (destroy_?) destroy_>> else if (activate_? && hasPermissions) activate_>>
			case FAILED => if (nonBound) destroy_>>
			case DESTROYED =>
			case _ => logE(s"Module [$moduleID] is in unexpected state [${_state}].")
		}
		//
		val changed = prev != _state
		if (changed && _state != DESTROYED) updateState(null, 0)
		else if (_state == DEACTIVATING || (_state == ACTIVATING && activatingNonPaused)) updateState(null, calcBackoff())
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
	private[this] def activated_? : Boolean = activatingNonPaused && {
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
		if (reactivating != 0) {
			standby = (reactivating & 2) != 0
			servePaused = (reactivating & 4) != 0
			reactivating = 0
		}
	}
	private[this] def trying[T](code: => T): T = try code catch {
		case err: Throwable => handleError(err); null.asInstanceOf[T]
	}
	private[app] def handleError(err: Throwable): Unit = {
		try onFailure(err) match {
			case Some(e) => if (err.ne(e) && e.getCause == null) e.initCause(err)
				fail(e)
			case None => logE(err, s"Module [$moduleID] in state [${_state}] error caught but ignored.")
		} catch loggedE
	}
	private[app] def fail(err: Throwable): Unit = lock synchronized {
		logE(err, s"Module [$moduleID] is failed in state [${_state}] . ")
		if (_failure == null) {
			state = FAILED
			_failure = err
			try onFailed() catch loggedE
			cancelRequests()
			quitFutureContext()
			manager.moduleUnbindFromAll
			syncChildren.foreach(_.fail(BoundParentException(this)))
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
	private[this] def checkTerminating: Boolean = bindings.nonEmpty || {
		try onBeforeTerminalDeactivating() catch loggedE
		deactivate_?
	}
	protected[this] final def progressDelay(): Long = {
		deviceNow() - progressStartTime
	}
	protected[this] def calcBackoff(): Int = {
		val past = progressDelay()
		val delay = if (past < 2000) 200
		else if (past < 10000) 1000
		else if (past < 60000) 5000
		else 10000
		val mult = if (_state == DEACTIVATING) 2 else 1
		delay * mult
	}
	private[app] def hasPermissions: Boolean = !initialising || {
		val failed = permissions.filter(p => p.typ == Permission.CRITICAL && !hasPermission(p.name))
		if (failed.nonEmpty) fail(ModulePermissionException(failed.map(_.name).mkString(", "), getClass))
		_state != FAILED
	}
	private[app] def waitPermissions(yep: Boolean): Unit = pauseActivation(yep, 2)
	private[app] def activatingNonPaused: Boolean = activatingPaused == 0
	private[app] def pauseActivation(yep: Boolean, reason: Int): Unit = yep match {
		case true => activatingPaused |= reason
			expectUpdate = false
			ModuleTiker.cancelUpdates
		case false if activatingPaused != 0 => activatingPaused &= ~reason
			if (activatingPaused == 0) updateState(UNPAUSE)
		case _ =>
	}

}






/* MODULE REQUEST */

class ModuleRequest[T](implicit module: Module) extends FutureX[T] {
	override final def onFinishExecute(v: Try[T]): Unit = {
		module.requestComplete(this)
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
