package just4fun.android.core.app

import java.lang

import scala.collection.mutable
import scala.language.existentials

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import just4fun.android.core.app.MemoryState._
import just4fun.android.core.app.ModuleState.ModuleState
import just4fun.android.core.async.{FutureX, MainThreadContext, ThreadPoolContext}
import just4fun.android.core.vars.Prefs
import just4fun.core.schemify.PropType
import just4fun.utils.Utils._
import just4fun.utils.logger.Logger._
import just4fun.utils.schema.ListType

private[app] class ModuleManager(val app: Modules) extends PermissionSubsystem with RestoreSubsysytem {
	protected[this] implicit val cache = Prefs.syscache
	protected[this] implicit val listType = new ListType[String]()
	protected[this] val modules = mutable.ArrayBuffer[Module]()
	protected[this] var inited = false

	/* MODULE  LIFECYCLE */
	def isModulesEmpty = modules.isEmpty
	def containsAliveModule(cls: Class[_]): Boolean = {
		modules.exists(m => !m.isDestroying && m.getClass == cls)
	}

	def moduleUse[M <: Module : Manifest]: M = synchronized {
		val cls: Class[M] = implicitly[Manifest[M]].runtimeClass.asInstanceOf[Class[M]]
		val m = modules.find(m => m.getClass == cls && !m.isDestroying) match {
			case Some(m) => m
			case _ => val m = moduleConstruct(cls)
				moduleAdd(m)
				m
		}
		m.asInstanceOf[M]
	}
	def moduleBind[M <: Module : Manifest](binding: Module, sync: Boolean = false): M = {
		val cls: Class[M] = implicitly[Manifest[M]].runtimeClass.asInstanceOf[Class[M]]
		moduleBind[M](cls, binding, sync, false)
	}
	def moduleBind[M <: Module : Manifest](clas: Class[M], binding: Module, sync: Boolean, restore: Boolean): M = synchronized {
		val cls = getPreferedModuleClass(clas)
		val m = modules.find(m => m.getClass == cls && !m.isDestroying) match {
			case Some(m) => m
			case _ => // case when self using while self constructing
				val m = if (binding != null && binding.getClass == cls) binding.asInstanceOf[M]
				else moduleConstruct(cls, binding, restore)
				moduleAdd(m)
				m
		}
		m.bindingAdd(binding, sync)
		val self = binding == null || binding.getClass == cls
		//		logD(s"BIND [${if (self) "self" else binding.moduleID}] to [${m.moduleID}];  sync? $sync;  ")
		if (self && m.restore) updateRestorables(m.getClass, true)
		m.asInstanceOf[M]
	}
	private def moduleConstruct[M <: Module](cls: Class[M], binding: Module = null, restore: Boolean = false): M = {
		// DEFs
		def constrEmpty(err: Throwable = null): M = {
			try cls.newInstance()
			catch {case e: Throwable => if (err == null) constrRestorable(e) else throw err}
		}
		def constrRestorable(err: Throwable = null): M = {
			try cls.getDeclaredConstructor(classOf[Boolean]).newInstance(new lang.Boolean(restore))
			catch {case e: Throwable => if (err == null) constrEmpty(e) else throw err}
		}
		// EXEC
		if (!inited) onInit()
		try {
			val m = if (restore) constrRestorable() else constrEmpty()
			m.onConstructed()
			val pms = m.permissions_intr
			if (nonEmpty(pms)) requestStaticPermissions(pms, m)
			m
		}
		catch {
			case e: StackOverflowError =>
				val ex = new CyclicBindingException(cls, s"${cls.getName}, ${if (binding != null) binding.getClass.getName else ""}")
				ex.initCause(e)
				throw ex
		}
	}
	private def moduleAdd(m: Module): Unit = {
		val first = modules.isEmpty
		if (!modules.contains(m)) modules += m
		if (first && Modules.aManager.uiContext.isEmpty) KeepAliveService.start()
	}

	def moduleUnbind[M <: Module : Manifest](binding: Module): Option[M] = {
		val cls: Class[M] = implicitly[Manifest[M]].runtimeClass.asInstanceOf[Class[M]]
		moduleUnbind[M](cls, binding)
	}
	def moduleUnbind[M <: Module : Manifest](clas: Class[M], binding: Module): Option[M] = synchronized {
		val cls = getPreferedModuleClass(clas)
		var mOpt: Option[M] = None
		modules.find(m => m.getClass == cls && !m.isDestroying).foreach { m =>
			val self = binding == null || binding.getClass == cls
			if (self) updateRestorables(cls, false)
			//			logD(s"UNBIND [${if (self) "self" else binding.moduleID}] from [${m.moduleID}]; ")
			m.bindingRemove(binding)
			mOpt = Some(m.asInstanceOf[M])
		}
		mOpt
	}
	def moduleUnbindFromAll(implicit m: Module): Unit = {
		modules.foreach(_.bindingRemove(m))
	}
	def moduleDestroyed(implicit m: Module): Unit = {
		modules -= m
		modules.foreach(_.bindingRemove(m))
		updateRestorables(m.getClass, false)
		if (modules.isEmpty) FutureX(onExit())(MainThreadContext)
	}

	def moduleHasPredecessor(implicit module: Module): Boolean = {
		modules.exists(_.isPredecessorOf(module))
	}


	private[app] def isInited: Boolean = inited
	private def onInit(): Unit = {
		inited = true
		logV(s"<<<<<<<<<<<<<<<<<<<<                    MODULES   INITED                    >>>>>>>>>>>>>>>>>>>>")
		initPermissionSubsystem()
		app.modulesInit()
	}
	private def onExit(): Unit = {
		inited = false
		app.modulesExit()
		exitPermissionSubsystem()
		PropType.clean()
		ThreadPoolContext.quit()
		KeepAliveService.stop()
		logV(s"<<<<<<<<<<<<<<<<<<<<                    MODULES   EXITED                    >>>>>>>>>>>>>>>>>>>>")
	}

	/* MISC EVENTS */
	def onConfigurationChanged(newConfig: Configuration): Unit = {
		modules.foreach(_.configurationChanged(newConfig))
	}
	def trimMemory(state: MemoryState): Unit = {
		modules.foreach(_.trimMemory(state))
	}


	/* SUBSTITUTE CLASS */
	def getPreferedModuleClass[S <: Module](clas: Class[S]): Class[S] = app.libResources.preferedModuleClasses match {
		case null => clas
		case map => map get clas match {
			case None => clas
			case Some(subclass) => subclass.asInstanceOf[Class[S]]
		}
	}


	/* ACTIVITY */
	def onActivityCreate(a: Activity): Unit = getActivityModule(a).onActivityCreate()
	def onActivityDestroy(a: Activity): Unit = getActivityModule(a).onActivityDestroy()
	def onActivityRestoreState(a: Activity, state: Bundle): Unit = getActivityModule(a).onRestoreState(state)
	def onActivitySaveState(a: Activity, state: Bundle): Unit = getActivityModule(a).onSaveState(state)
	def getActivityModule(a: Activity): ActivityModule = {
		modules.collectFirst { case m: ActivityModule if m.pairedActivity(a) && !m.isDestroying => m } match {
			case Some(m) => m
			case None => a match {
				case a: TwixActivity[_, _] => moduleConstruct(a.moduleClass).setActivityClass(a.getClass)
				case _ => new ActivityModule {}.setActivityClass(a.getClass).onConstructed() // new inst of new class
			}
		}
	}
}






/* RESTORE SUBSYSTEM */
private[app] trait RestoreSubsysytem {
	mgr: ModuleManager =>
	private[this] val KEY_RESTOR = "restorables_"
	private[this] val restorables = mutable.Set[Class[_]]()

	def checkRestore(): Unit = {
		Prefs[List[String]](KEY_RESTOR) match {
			case null | Nil =>
			case names => updateRestorables(null, false)
				FutureX(restore(names))(MainThreadContext)
		}
	}
	private def restore(modules: List[String]): Unit = modules.foreach { name =>
		try {
			val cls = Class.forName(name)
			logV(s"BEFORE  RESTORED  ${cls.getSimpleName};  not yet created? ${!containsAliveModule(cls)}")
			if (!containsAliveModule(cls)) moduleBind(cls.asInstanceOf[Class[Module]], null, false, true)
			logV(s"AFTER RESTORED  ${cls.getSimpleName}")
		}
		catch loggedE
	}
	def updateRestorables(cls: Class[_], yeap: Boolean): Unit = {
		if (cls == null || (yeap && restorables.add(cls)) || (!yeap && restorables.remove(cls))) {
			Prefs(KEY_RESTOR) = restorables.map(_.getName).toList
			logV(s"Restorables > ${Prefs[String](KEY_RESTOR)}")
		}
	}
}






/* EXCEPTIONS */
abstract class ModuleException(message: String) extends Exception(message) {
	def this() = this("")
}

case class ModuleServiceException(state: ModuleState) extends ModuleException(s"Module cannot serve request in $state state.")

case class BoundParentException(parent: Module) extends ModuleException(s"Sync-bound parent module ${parent.moduleID} failed with  ${parent.failure.foreach(_.getMessage)}")

case class ModulePermissionException(permission: String, moduleClass: Class[_]) extends ModuleException(s"Module ${moduleClass.getName} has no permission $permission")

class NoUiForPermissionRequestException extends ModuleException("Permission request requires currently running Activity.")


/* BINDING EXCEPTIONS */
class ModuleBindingException(val moduleClass: Class[_], cause: Throwable = null, message: String = "") extends ModuleException(s"Failed binding module ${moduleClass.getName}. $message") {
	initCause(cause)
}

class DeadBindingException(moduleClass: Class[_]) extends ModuleBindingException(moduleClass)

class CyclicBindingException(moduleClass: Class[_], trace: String) extends ModuleBindingException(moduleClass, null, s"Cyclic usage detected in chain [$trace]")


