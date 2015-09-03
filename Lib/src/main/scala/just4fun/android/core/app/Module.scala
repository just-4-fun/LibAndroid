package just4fun.android.core.app

import scala.collection.mutable
import scala.concurrent.Future
import scala.language.experimental.macros
import scala.util.{Failure, Try}

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.SystemClock.{uptimeMillis => deviceNow}
import just4fun.android.core.async.{FutureContext, FutureContextHolder, FutureX}
import just4fun.android.core.vars._
import just4fun.utils.devel.ILogger._

// todo def setStatusNotification
// todo def setLock / sync
// TODO onBeforeDeactivate Event
// todo def onNoRequests >> can reenable suspending

object Module {
	var FwCWe434fREt = 0
	// todo remove
	
	object StateValue extends Enumeration {
		val PASSIVE, ACTIVATING, ACTIVE, DEACTIVATING, FAILED, DESTROYED = Value
	}
	
	object RestoreAfterCrashPolicy extends Enumeration {
		val NEVER, IF_SELF_BOUND = Value
	}
	
	private[app] object StateCause extends Enumeration {
		val BIND_FIRST, BIND_LAST, REQ_FIRST, REQ_LAST, ACTIV, DEACT, FAIL, PASS_OFF, PASS_ON, CH_ADD, CH_ACTIV, CH_DEL, CH_DEACT, PAR_ADD, PAR_DEL, PAR_ACTIV, PAR_FAIL = Value
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



trait Module extends FutureContextHolder with Loggable {
	
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
	protected[this] val activatingTimeout = 30000
	protected[this] val deactivatingTimeout = activatingTimeout * 2
	protected[this] val activeLatency = 10000
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
	protected[this] def unbindSelf(): Unit = {
		manager.moduleUnbind[this.type](this)
	}
	protected[this] def bind[M <: Module : Manifest]: M = macro Macros.bindS[M]
	protected[this] def unchecked_bind[M <: Module : Manifest]: M = {
		val s = manager.moduleBind[M](this)
		dependParentRemove(s)
		s
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
	
	def isAlive = state < StateValue.FAILED
	def isActive = state == StateValue.ACTIVE
	def isPassive = state <= StateValue.PASSIVE
	def isDestroyed = state == StateValue.DESTROYED
	def isBound = bindings.nonEmpty
	def isPassiveModeOn: Boolean = passiveMode
	
	def dumpState(): String = s"state= ${state}; bindings= ${bindings.size};  depends= ${dependParents.size}; requests= ${requests.size};  suspend= $passiveMode" // ID=
	
	/* Lifecycle CALLBACKS to override */
	
	protected[this] def onStartActivating(firstTime: Boolean): Unit = ()
	/** @return true - if activation is finished. false - if continue activation */
	protected[this] def onKeepActivating(firstTime: Boolean): Boolean = true
	protected[this] def onFinishActivating(firstTime: Boolean): Unit = ()
	protected[this] final def setActivated(): Unit = engine.update(ACTIV)
	protected[this] def onStartDeactivating(lastTime: Boolean): Unit = ()
	/** @return true - if deactivation is finished. false - if continue deactivation */
	protected[this] def onKeepDeactivating(lastTime: Boolean): Boolean = true
	protected[this] def onFinishDeactivating(lastTime: Boolean): Unit = ()
	protected[this] final def setDeactivated(): Unit = engine.update(DEACT)
	protected[this] def onDestroy(): Unit = ()
	/** @return true to skip; false to keep waiting. */
	protected[this] def onTimeout(): Boolean = true
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
		val u = if (binding == null) this else binding
		u.detectCyclicBinding(this, u :: Nil) match {
			case Nil => val first = bindings.isEmpty
				bindings += u
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
		val u = if (binding == null) this else binding
		val wasChild = dependChildren.remove(binding)
		if (bindings.remove(u) && bindings.isEmpty) engine.update(BIND_LAST)
		else if (wasChild) engine.update(CH_DEL)
		//		logI(s"binding Remove> size= ${bindings.size}")
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
		if (requests.isEmpty) engine.update(REQ_LAST, if (passiveMode && bindings.nonEmpty) activeLatency else 0)
	}
	
	private[app] def trimMemory(level: Int): Unit = {
		import ComponentCallbacks2._
		if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) asyncVars.foreach(_.releaseValue())
		try onTrimMemory(level) catch {case e: Throwable => logE(e)}
	}
	
	private[core] def registerAsyncVar(v: FileVar[_]) = asyncVars = v :: asyncVars
	
	
	
	
	
	
	
	/* STATE MACHINE */
	
	protected[app] object engine {
		
		import Module.StateCause._
		import Module.StateValue._
		private[this] var _state: StateValue.Value = PASSIVE
		private[app] var failure: Throwable = null
		private[this] var nextTik = 0L
		private[this] var initialTime = 0L
		private[this] var nextTimeout = 0L
		private[this] var initialCycle = true

		private[app] def state = synchronized(_state)
		private[this] def state_=(v: StateValue.Value): Unit = {
			logW(s"${" " * (90 - moduleID.length)} [$moduleID]:  ${_state} >  $v", "STATE")
			_state = v
		}
		
		private[app] def update(cause: StateCause.Value, delay: Int = 0): Boolean = {
			val yeap = _state match {
				case ACTIVE => canDeactivate
				case PASSIVE => canDestroy || canActivate
				case ACTIVATING | DEACTIVATING => true
				case FAILED => canDestroy
				case _ => false
			}
			//			logV(s"REQUEST UPDATE   N=$FwCWe434fREt;  from $cause;   ? $yeap")
			//			logV(s"DUMP ${dumpState}")
			if (yeap) requestUpdate(delay)
			yeap
		}
		private[this] def canActivate: Boolean = {
			FwCWe434fREt += 1
			shouldActivate && parentsReady
		}
		private[this] def shouldActivate: Boolean = {
			!passiveMode || requests.nonEmpty || dependChildren.exists(_.engine.canParentActivate)
		}
		private def canParentActivate: Boolean = {
			bindings.nonEmpty && shouldActivate
		}
		private[this] def canDestroy: Boolean = {
			bindings.isEmpty
		}
		private[this] def canDeactivate: Boolean = {
			requests.isEmpty && (bindings.isEmpty || (passiveMode && dependChildren.forall(_.engine.canParentDeactivate)))
		}
		private def canParentDeactivate: Boolean = {
			_state == PASSIVE || _state >= FAILED
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
		
		private[this] def requestUpdate(delay: Long): Unit = {
			nextTik = deviceNow() + delay
			ModuleTiker.requestUpdate(nextTik)
			//			logV(s"REQUEST UPDATE  for $delay ms")
		}
		
		private[app] def onUpdate(): Boolean = synchronized {
			//			logV(s"                  UPDATE ;  state= ${_state}; canDestroy? $canDestroy;   canDeactivate? $canDeactivate;  canActivate? $canActivate")
			nextTik = 0
			val prev = _state
			//
			_state match {
				case PASSIVE => if (canDestroy) destroy else if (canActivate) activate
				case ACTIVATING => activated
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
			trying(onStartActivating(initialCycle))
			initialCycle = false
			initialTime = deviceNow()
			nextTimeout = initialTime + activatingTimeout
			activated
		}
		private[this] def activated: Unit = if (trying(onKeepActivating(initialCycle)) || skipTimeout) {
			state = ACTIVE
			nextTimeout = 0
			initialTime = 0
			trying(onFinishActivating(initialCycle))
			consumeRequests()
			dependChildren.foreach(_.engine.update(PAR_ACTIV))
		}
		private[this] def consumeRequests(): Unit = {
			requests.foreach(fx => if (_state == ACTIVE) fx.activate() else if (_state >= FAILED) fx.cancel())
		}
		private[this] def skipTimeout: Boolean = {
			if (_state == FAILED || deviceNow() < nextTimeout) false else trying(onTimeout())
		}
		private[this] def deactivate: Unit = {
			state = DEACTIVATING
			initialCycle = bindings.isEmpty
			trying(onStartDeactivating(initialCycle))
			initialTime = deviceNow()
			nextTimeout = initialTime + deactivatingTimeout
			deactivated
		}
		private[this] def deactivated: Unit = if (trying(onKeepDeactivating(initialCycle)) || skipTimeout) {
			state = PASSIVE
			nextTimeout = 0
			initialTime = 0
			trying(onFinishDeactivating(initialCycle))
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
			passiveMode = false
			consumeRequests()
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

		private[this] def calcDelay(): Int = {
			val past = deviceNow() - initialTime
			val delay = if (past < 2000) 200
			else if (past < 10000) 1000
			else if (past < 60000) 5000
			else 10000
			val mult = if (_state == DEACTIVATING) 2 else 1
			delay * mult
		}
		
	}
}
