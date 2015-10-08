package just4fun.android.core.app

import java.lang

import android.content.pm.PackageManager

import scala.collection.mutable
import scala.concurrent.{Promise, Future}
import scala.language.existentials

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import just4fun.android.core.async.{MainThreadContext, FutureX, ThreadPoolContext}
import just4fun.android.core.vars.Prefs
import just4fun.core.schemify.PropType
import just4fun.utils.Utils._
import just4fun.utils.logger.Logger._
import just4fun.utils.schema.ListType
import android.os.Process

private[app] class ModuleManager(val app: Modules) /*extends PermissionSubsystem*/ {

	private[this] val modules = mutable.ArrayBuffer[Module]()
	/** Restoring [[Module]]s */
	private[this] val restorables = mutable.Set[Class[_]]()
	private[this] implicit val listType = new ListType[String]()

	/* MODULE  LIFECYCLE */
	def isEmpty = modules.isEmpty
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
		try {
			val m = if (restore) constrRestorable() else constrEmpty()
//			requirePermissions(m.permissions, m)
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
	def moduleHasPredecessor(implicit module: Module): Boolean = {
		modules.exists(_.isPredecessorOf(module))
	}
	def moduleUnbind[M <: Module : Manifest](binding: Module): Unit = {
		val cls: Class[M] = implicitly[Manifest[M]].runtimeClass.asInstanceOf[Class[M]]
		moduleUnbind[M](cls, binding)
	}
	def moduleUnbind[M <: Module : Manifest](clas: Class[M], binding: Module): Unit = {
		val cls = getPreferedModuleClass(clas)
		modules.find(m => m.getClass == cls && !m.isDestroying).foreach { m =>
			val self = binding == null || binding.getClass == cls
			if (self) updateRestorables(cls, false)
			//			logD(s"UNBIND [${if (self) "self" else binding.moduleID}] from [${m.moduleID}]; ")
			m.bindingRemove(binding)
		}
	}
	def moduleUnbindFromAll(implicit m: Module): Unit = {
		modules.foreach(_.bindingRemove(m))
	}
	def moduleDestroyed(implicit m: Module): Unit = {
		modules -= m
		modules.foreach(_.bindingRemove(m))
		updateRestorables(m.getClass, false)
		if (modules.isEmpty) onExit()
	}
	private def onExit(): Unit = {
		try app.onExit() catch loggedE
//		cleanupPermissionSubsystem()
		PropType.onAppExit()
		ThreadPoolContext.quit()
		KeepAliveService.stop()
		logV(s"<<<<<<<<<<<<<<<<<<<<                    APP   EXITED                    >>>>>>>>>>>>>>>>>>>>")
	}

	/* MISC EVENTS */
	def onConfigurationChanged(newConfig: Configuration): Unit = {
		modules.foreach(_.configurationChanged(newConfig))
	}
	def onTrimMemory(level: Int): Unit = {
		modules.foreach(_.trimMemory(level))
	}

	/* RESTORING */
	def checkRestore(): Unit = {
		Prefs[List[String]]("_restorables_") match {
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
			Prefs("_restorables_") = restorables.map(_.getName).toList
			logV(s"Restorables > ${Prefs[String]("_restorables_")}")
		}
	}

	/* SUBSTITUTE CLASS */
	def getPreferedModuleClass[S <: Module](clas: Class[S]): Class[S] = app.preferedModuleClasses match {
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
				case _ => new ActivityModule {}.setActivityClass(a.getClass) // new inst of new class
			}
		}
	}
}




/* PERMISSIONS */
//private[app] trait PermissionSubsystem extends ActivityCompat.OnRequestPermissionsResultCallback {
//	mgr: ModuleManager =>
//	private[this] var requests: List[Request] = null
//	private[this] var requestId = 1
//	private[this] var requesting = false
//
//
//	def hasPermission(pm: String): Boolean = {
//		// todo class of context
//		app.checkPermission(pm, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
//	}
//	def requestPermissions(perms: Seq[String])(implicit m: Module): Future[Seq[Boolean]] = {
//		val promise = Promise[Seq[Boolean]]()
//		if (perms.forall(hasPermission)) promise.success(List(true).padTo(perms.length, true)) else addRequest(perms, promise)
//		promise.future
//	}
//	def requirePermissions(perms: Seq[String], m: Module): Unit = if (nonEmpty(perms)) {
//		val leftPerms = perms.filterNot(hasPermission)
//		if (leftPerms.nonEmpty) {
//			m.pauseActivating()
//			addRequest(leftPerms, m)
//		}
//	}
//	private def addRequest(perms: Seq[String], moduleOrPromise: AnyRef): Unit = synchronized {
//		val r = Request(perms)
//		r.moduleOrPromise = moduleOrPromise
//		requests = if (requests == null) r :: Nil else r :: requests
//		if (!requesting) FutureX.post(1000, this)(request())(MainThreadContext)
//	}
//	private def request(): Unit = synchronized {
//		if (Modules.aManager.uiContext.isEmpty) {
//			requests.foreach(_.complete())
//			requests = null
//		}
//		else {
//			requesting = true
//			val perms = requests.flatMap { r => r.id = requestId; r.permissions }.distinct.toArray
//			ActivityCompat.requestPermissions(Modules.aManager.uiContext.get, perms, requestId)
//			requestId += 1
//		}
//	}
//	override def onRequestPermissionsResult(id: Int, perms: Array[String], results: Array[Int]): Unit = synchronized {
//		val (curr, next) = requests.partition(_.id == id)
//		requests = if (next.isEmpty) null else next
//		curr.foreach(_.complete())
//		requesting = false
//		if (next.nonEmpty) request()
//	}
//	def cleanupPermissionSubsystem(): Unit = {
//		FutureX.cancel(this)(MainThreadContext)
//	}
//
//	private case class Request(permissions: Seq[String]) {
//		var id = 0
//		var moduleOrPromise: AnyRef = null
//		def complete(): Unit = moduleOrPromise match {
//			case module: Module => module.resumeActivatingProgress()
//			case promise: Promise[Seq[Boolean]] @unchecked => promise.success(permissions.map(hasPermission))
//		}
//	}
//}






/* EXCEPTIONS */
abstract class ModuleException(message: String) extends Exception(message) {
	def this() = this("")
}

class ModuleBindingException(val moduleClass: Class[_], cause: Throwable = null, message: String = "") extends ModuleException(s"Failed binding module ${moduleClass.getName}. $message") {
	initCause(cause)
}

class DeadModuleBindingException(moduleClass: Class[_]) extends ModuleBindingException(moduleClass)


class CyclicBindingException(moduleClass: Class[_], trace: String) extends ModuleBindingException(moduleClass, null, s"Cyclic usage detected in chain [$trace]")

case class ModuleServiceException() extends ModuleException("Module cannot serve request because it's not yet activated.")

case class BoundParentException(parent: Module) extends ModuleException(s"Sync-bound parent module ${parent.moduleID} failed with  ${parent.failure.foreach(_.getMessage)}")

case class ModuleHasNoPermissionException(permission: String, moduleClass: Class[_]) extends ModuleException(s"Module ${moduleClass.getName} has no permission $permission")