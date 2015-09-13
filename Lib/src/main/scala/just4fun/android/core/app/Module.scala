package just4fun.android.core.app

import android.app.Activity
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
		val ACTIVATING, ACTIVE, DEACTIVATING, PASSIVE, FAILED, DESTROYED = Value
	}
	
	object RestoreAfterCrashPolicy extends Enumeration {
		val NEVER, IF_SELF_BOUND = Value
	}
	
	private[app] object StateCause extends Enumeration {
		val BIND_FIRST, BIND_LAST, REQ_FIRST, REQ_LAST, UNIQ, CHECK, FAIL, PASS_OFF, PASS_ON, CH_ADD, CH_ACTIV, CH_DEL, CH_DEACT, PAR_ADD, PAR_DEL, PAR_ACTIV, PAR_FAIL = Value
		// Params: binding, req, activated, failed, suspend, ch_activate, ch_Deactivated, par_activated
	}
	
	private lazy val _failedFuture: FutureX[_] = {
		val fx = new FutureX
		fx.cancel()
		fx
	}
	private def failedFuture[T]: FutureX[T] = _failedFuture.asInstanceOf[FutureX[T]]
	private def failedTry[T]: Failure[T] = Failure(ModuleInactiveException)
}



trait Module extends FutureContextHolder {
	
	import Module.StateCause._
	import Module._
	import RestoreAfterCrashPolicy._
	val moduleID: String = getClass.getName
	implicit protected val thisModule: this.type = this
	implicit protected val appContext = Modules.context
	protected[this] val manager = Modules.mManager
	protected[this] val bindings = mutable.Set[Module]()
	protected[this] val dependChildren = mutable.Set[Module]()
	protected[this] val dependParents = mutable.Set[Module]()
	protected[this] val requests = mutable.ListBuffer[FutureX[_]]()
	protected[this] val requestWaitLatency = 10000
	private[this] var passiveMode = false
	private[this] var asyncVars = List[AsyncVar[_]]()
	val isAfterCrash: Boolean = Prefs.contains(moduleID)
	def state: StateValue.Value = engine.state
	
	
	/* INIT */
	init()
	
	/* PUBLIC */
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
	/** Uses module M. depends on module so that this module can be activated only if all parent modules are active. */
	protected[this] final def dependOn[M <: Module : Manifest]: M = macro Macros.dependOn[M]
	protected[this] def unchecked_dependOn[M <: Module : Manifest]: M = {
		val s = manager.moduleBind[M](this)
		dependParentAdd(s)
		s
	}
	
	protected def setPassiveMode(yeap: Boolean = true): Unit = if (yeap != passiveMode) {
		passiveMode = yeap
		engine.update(if (yeap) PASS_OFF else PASS_ON)
	}
	
	protected[app] def restoreAfterCrashPolicy: RestoreAfterCrashPolicy.Value = NEVER
	
	def isAlive = state < StateValue.FAILED && !engine.terminating
	def isActive = state == StateValue.ACTIVE
	def isPassive = state == StateValue.PASSIVE
	def isDestroying = state == StateValue.DESTROYED || engine.terminating
	def isBound = bindings.nonEmpty
	def isPassiveModeOn: Boolean = passiveMode
	
	def dumpState(): String = s"state= ${state}; bindings= ${bindings.size};  depends= ${dependParents.size}; requests= ${requests.size};  suspend= $passiveMode;  extremCycle? ${engine.initialising || engine.terminating}"
	
	/* Lifecycle CALLBACKS to override */
	
	protected[this] def onActivatingStart(firstTime: Boolean): Unit = ()
	/** @return true - if activation is finished. false - if continue activation */
	protected[this] def onActivatingProgress(firstTime: Boolean, seconds: Int): Boolean = true
	protected[this] def onActivatingFinish(firstTime: Boolean): Unit = ()
	protected[this] def onDeactivatingStart(lastTime: Boolean): Unit = ()
	/** @return true - if deactivation is finished. false - if continue deactivation */
	protected[this] def onDeactivatingProgress(lastTime: Boolean, seconds: Int): Boolean = true
	protected[this] def onDeactivatingFinish(lastTime: Boolean): Unit = ()
	protected[this] def onDestroy(): Unit = ()
	protected[this] def onFailed(): Unit = ()
	
	/** Called when one of lifecycle methods throws an exception. That exception is passed here to handle it or let module fail.
	If dependency parent is failed the DependencyParentFailed(module) exception is passed.
	  @return None if module can proceed. Otherwise module is considered failed with returned error.
	  */
	protected[this] def onFailure(err: Throwable): Option[Throwable] = err match {
		//		case DependencyParentFailed(s) => Some(err)
		case e => Some(e)
	}
	final def failure: Option[Throwable] = Option(engine.failure)
	protected[this] def setFailed(err: Throwable): Unit = {
		engine.fail(err)
		engine.update(FAIL)
	}
	protected[app] def onConfigurationChanged(newConfig: Configuration): Unit = ()
	protected[this] def onTrimMemory(level: Int): Unit = ()
	
	protected[this] final def pauseActivatingProgress(): Unit = engine.pauseUpdates(true)
	protected final def resumeActivatingProgress(): Unit = engine.pauseUpdates(false)
	final def isActivatingProgressPaused: Boolean = engine.pausedActivating


	/* REQUEST WRAPPERS */
	protected[this] def execOption[T](code: => T): Option[T] = {
		if (isActive) Option(code) else None
	}
	protected[this] def execTry[T](code: => T): Try[T] = {
		if (isActive) Try(code) else failedTry
	}
	protected[this] def execAsync[T](code: => T)(implicit futureContext: FutureContext): FutureX[T] = {
		requestAdd(new FutureX[T].task(code))
	}
	protected[this] def execAsyncSeq[T](code: => FutureX[T])(implicit futureContext: FutureContext): FutureX[T] = {
		requestAdd(new FutureX[T].taskSeq(code))
	}
	protected[this] def execAsyncFuture[T](code: => Future[T])(implicit futureContext: FutureContext): FutureX[T] = {
		requestAdd(new FutureX[T].taskFuture(code))
	}
	
	
	/* INTERNAL */
	protected[this] def init(): Unit = {
		Prefs(moduleID) = true
	}
	
	private[app] def bindingAdd(binding: Module): Unit = {
		val b = if (binding == null) this else binding
		b.detectCyclicBinding(this, b :: Nil) match {
			case Nil => val first = bindings.isEmpty
				bindings += b
				//				logI(s"binding Add> size= ${bindings.size};  $u")
				if (first) engine.update(BIND_FIRST)
			case trace => throw CyclicUsageException(trace.map(_.moduleID).mkString(", "))
		}
	}
	private def detectCyclicBinding(par: Module, trace: List[Module]): List[Module] = {
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
			if (bindings.isEmpty) engine.update(BIND_LAST)
			else if (wasChild) engine.update(CH_DEL)
			//		logI(s"binding Remove> size= ${bindings.size}")
		}
		else if (b.getClass == getClass && b != this && b.isDestroying) engine.update(UNIQ)
	}
	
	private[this] def dependParentAdd(m: Module): Unit = if (dependParents.add(m)) {
		m.dependChildAdd(this)
		engine.update(PAR_ADD)
	}
	private[this] def dependParentRemove(m: Module): Unit = if (dependParents.remove(m)) {
		m.dependChildRemove(this)
		engine.update(PAR_DEL)
	}
	
	private[app] def dependChildAdd(m: Module): Unit = if (dependChildren.add(m)) {
		engine.update(CH_ADD)
	}
	private[app] def dependChildRemove(m: Module): Unit = if (dependChildren.remove(m)) {
		engine.update(CH_DEL)
	}
	
	private[this] def requestAdd[T](fx: FutureX[T]): FutureX[T] = {
		if (isAlive) {
			val first = requests.isEmpty
			fx.onComplete(_ => requestRemove(fx))
			requests += fx
			if (isActive) fx.activate()
			else if (first) engine.update(REQ_FIRST)
			fx
		}
		else failedFuture
	}
	private[this] def requestRemove(fx: FutureX[_]): Unit = {
		requests -= fx
		if (requests.isEmpty) engine.update(REQ_LAST, if (passiveMode && bindings.nonEmpty) requestWaitLatency else 0)
	}
	
	private[app] def trimMemory(level: Int): Unit = {
		import ComponentCallbacks2._
		if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) asyncVars.foreach(_.releaseValue())
		try onTrimMemory(level) catch loggedE
	}
	
	private[core] def registerAsyncVar(v: FileVar[_]) = asyncVars = v :: asyncVars
	
	
	
	
	
	
	
	/* STATE MACHINE */
	
	protected[app] object engine {
		
		import Module.StateCause._
		import Module.StateValue._
		private[this] var _state: StateValue.Value = PASSIVE
		private[app] var failure: Throwable = null
		private[this] var nextTik = 0L
		private[this] var startTime = 0L
		private[app] var pausedActivating = false
		private[app] var initialising = true
		private[app] var terminating = false

		private[app] def state = synchronized(_state)
		private[this] def state_=(v: StateValue.Value): Unit = {
			logW(s"[$moduleID]:  ${_state} >  $v")
			_state = v
		}
		
		private[app] def update(cause: StateCause.Value, delay: Int = 0): Boolean = {
			val needUpdate = _state match {
				case ACTIVE => canDeactivate
				case PASSIVE => canDestroy || canActivate
				case ACTIVATING => isAbandoned; true
				case DEACTIVATING => true
				case FAILED => canDestroy
				case _ => false
			}
			//						logV(s"REQUEST UPDATE  [$moduleID]  cause $cause;   ? $needUpdate;    paused? ${isActivatingProgressPaused};  ")
			//			logV(s"DUMP ${dumpState}")
			if (needUpdate) requestUpdate(delay)
			needUpdate
		}
		private[this] def isAbandoned: Boolean = {
			if (bindings.isEmpty && requests.isEmpty) fail(new ModuleException("Module activating is stopped since it is abandoned."))
			_state == FAILED
		}
		private[this] def canActivate: Boolean = {
			shouldActivate && parentsReady
		}
		private[this] def shouldActivate: Boolean = {
			(!initialising || !manager.moduleWaitPredecessor) && (!passiveMode || requests.nonEmpty || dependChildren.exists(_.engine.canParentActivate))
		}
		private def canParentActivate: Boolean = {
			bindings.nonEmpty && shouldActivate
		}
		private[this] def canDestroy: Boolean = {
			bindings.isEmpty && requests.isEmpty
		}
		private[this] def canDeactivate: Boolean = {
			requests.isEmpty && (bindings.isEmpty || (passiveMode && dependChildren.forall(_.engine.canParentDeactivate)))
		}
		private def canParentDeactivate: Boolean = {
			_state >= PASSIVE
		}
		private[this] def parentsReady: Boolean = dependParents.forall { par =>
			par.isActive || {
				if (par.isAlive) {
					par.engine.update(CH_ACTIV)
					false
				}
				else {
					unbind(par)
					handleError(DependencyParentException(par))
					_state != FAILED
				}
			}
		}
		
		private[app] def pauseUpdates(yeap: Boolean): Unit = if (_state == ACTIVATING) {
			pausedActivating = yeap
			if (pausedActivating) ModuleTiker.cancelUpdates
			else update(CHECK)
		}

		private[this] def requestUpdate(delay: Long): Unit = if (!pausedActivating) {
			nextTik = deviceNow() + delay
			ModuleTiker.requestUpdate(nextTik)
			//						logV(s"REQUEST UPDATE  for $delay ms")
		}
		
		private[app] def onUpdate(): Boolean = synchronized {
			//						logV(s"                  UPDATE [$moduleID]   state= ${_state}; canDestroy? $canDestroy;   canDeactivate? $canDeactivate;  canActivate? $canActivate")
			val prev = _state
			//
			_state match {
				case PASSIVE => if (canDestroy) destroy else if (canActivate) activate
				case ACTIVATING => if (!isAbandoned) activated
				case ACTIVE => if (canDeactivate) deactivate
				case DEACTIVATING => deactivated
				case FAILED => if (canDestroy) destroy
				case DESTROYED =>
				case _ => logE(s"Module [$moduleID] is in unexpected state [${_state}].")
			}
			//
			val changed = prev != _state
			if (changed && _state != DESTROYED) requestUpdate(0)
			else if (_state == ACTIVATING || _state == DEACTIVATING) requestUpdate(calcDelay())
			changed
		}
		private[this] def activate: Unit = {
			state = ACTIVATING
			futureContext.start()
			trying(onActivatingStart(initialising))
			startTime = deviceNow()
			if (!pausedActivating) activated
		}
		private[this] def isActivated: Boolean = {
			val time = (deviceNow() - startTime) / 1000
			trying(onActivatingProgress(initialising, time.toInt))
		}
		private[this] def activated: Unit = if (isActivated) {
			state = ACTIVE
			trying(onActivatingFinish(initialising))
			consumeRequests()
			initialising = false
			dependChildren.foreach(_.engine.update(PAR_ACTIV))
		}
		private[this] def deactivate: Unit = {
			state = DEACTIVATING
			//todo : on terminal disable bind and serve.  if bind or serve, manager should create new instance.
			if (bindings.isEmpty) terminating = true
			trying(onDeactivatingStart(terminating))
			startTime = deviceNow()
			deactivated
		}
		private[this] def isDeactivated: Boolean = {
			val time = (deviceNow() - startTime) / 1000
			trying(onDeactivatingProgress(terminating, time.toInt))
		}
		private[this] def deactivated: Unit = if (isDeactivated) {
			state = PASSIVE
			trying(onDeactivatingFinish(terminating))
			dependParents.foreach(_.engine.update(CH_DEACT))
			futureContext.quit(true)
		}
		private[this] def trying[T](code: => T): T = try code catch {
			case err: Throwable => handleError(err)
				null.asInstanceOf[T]
		}
		private[this] def handleError(err: Throwable): Unit = onFailure(err) match {
			case Some(e) => fail(e)
			case None => logE(err, s"Module [$moduleID] error is ignored.")
		}
		private[app] def fail(err: Throwable): Unit = if (failure == null) synchronized {
			val prev = _state
			state = FAILED
			failure = err
			try onFailed() catch {case e: Throwable => logE(e, s"Module [$moduleID] error is ignored.")}
			consumeRequests()
			pausedActivating = false
			passiveMode = false
			futureContext.quit()
			dependParents.foreach(d => unbind(d))
			dependChildren.foreach(_.engine.update(PAR_FAIL))
			logE(err, s"Module [$moduleID] is failed in state [$prev]. ")
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
				consumeRequests()
				futureContext.quit()
				dependParents.clear()
				dependChildren.clear()
				bindings.clear()
				ModuleTiker.cancelUpdates
			}
		}
		private[this] def consumeRequests(): Unit = {
			requests.foreach(fx => if (_state == ACTIVE) fx.activate() else if (_state >= FAILED) fx.cancel())
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
}
