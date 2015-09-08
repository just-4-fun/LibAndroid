package just4fun.android.core.app

import just4fun.utils.logger.Logger

import scala.collection.mutable
import scala.language.existentials

import android.app.{Activity, Application}
import android.content.res.Configuration
import android.os.Bundle
import just4fun.android.core.app.Module.RestoreAfterCrashPolicy.IF_SELF_BOUND
import just4fun.android.core.async.ThreadPoolContext
import just4fun.android.core.vars.Prefs
import just4fun.core.schemify.PropType
import Logger._
import just4fun.utils.schema.ListType

private[app] class ModuleManager(app: Application) {

	private[this] val modules = mutable.ArrayBuffer[Module]()
	private[this] val subs = mutable.HashMap[Class[_], Class[_]]()
	/** Restoring [[Module]]s */
	private[this] val restorables = mutable.Set[Class[_]]()
	private[this] implicit val listType = new ListType[String]()

	/* MODULE  LIFECYCLE */
	def isEmpty = modules.isEmpty
	def moduleBind[M <: Module : Manifest](binding: Module): M = {
		val cls: Class[M] = implicitly[Manifest[M]].runtimeClass.asInstanceOf[Class[M]]
		moduleBind[M](cls, binding)
	}
	def moduleBind[M <: Module : Manifest](clas: Class[M], binding: Module): M = {
		val cls = getPreferedModuleClass(clas)
		val m = modules.collectFirst { case m: M if !m.isDestroyed => m } match {
			case Some(m) => m
			case _ => // case when self using while self constructing
				val m = if (binding != null && binding.getClass == cls) binding.asInstanceOf[M]
				else constrModule(cls, binding)
				if (!modules.contains(m)) modules += m
				m
		}
//		logV(s"BIND par= ${m.ID};  ch= ${binding}}")
		m.bindingAdd(binding)
		val selfUse = binding == null || binding.getClass == cls
		if (selfUse && m.restoreAfterCrashPolicy == IF_SELF_BOUND && restorables.add(cls)) updateRestorablesPref()
		m.asInstanceOf[M]
	}
	private def constrModule[M <: Module](cls: Class[M], binding: Module = null): M = {
		try cls.newInstance()
		catch {case e: StackOverflowError => throw CyclicUsageException(s"${cls.getName}, ${if (binding != null) binding.getClass.getName else ""}")}
	}
	def moduleUnbind[M <: Module : Manifest](binding: Module): Option[M] = {
		val cls: Class[M] = implicitly[Manifest[M]].runtimeClass.asInstanceOf[Class[M]]
		moduleUnbind[M](cls, binding)
	}
	def moduleUnbind[M <: Module : Manifest](clas: Class[M], binding: Module): Option[M] = {
		val cls = getPreferedModuleClass(clas)
		modules.collectFirst { case m: M =>
			val selfUse = binding == null || binding.getClass == cls
			if (selfUse && m.isBound && restorables.remove(cls)) updateRestorablesPref()
			m.bindingRemove(binding)
			m
		}
	}
	def moduleDestroyed(m: Module): Unit = {
		modules -= m
		modules.foreach(_.bindingRemove(m))
		if (restorables.remove(m.getClass)) updateRestorablesPref()
		if (modules.isEmpty) onExit()
	}
	private def onExit(): Unit = {
		KeepAliveService.stop()
		Modules.onExit()
		PropType.onAppExit()
		ThreadPoolContext.quit()
		logV(s"<<<<<<<<<<<<<<<<<<<<                    APP   EXITED                    >>>>>>>>>>>>>>>>>>>>")
	}

	/* MISC EVENTS */
	def onConfigurationChanged(newConfig: Configuration): Unit = {
		modules.foreach(s => try s.onConfigurationChanged(newConfig) catch {case e: Throwable => logE(e)})
	}
	def onTrimMemory(level: Int): Unit = {
		modules.foreach(_.trimMemory(level))
	}

	/* RESTORING */
	def checkRestore(): Unit = {
		Prefs[List[String]]("_restorables_") match {
			case null | Nil =>
			case names => updateRestorablesPref()
				names.foreach { name =>
					try {
						val cls = Class.forName(name)
						implicit val c = app
						logV(s"BEFORE  RESTORED  ${cls.getSimpleName}")
						// TODO create mod with restoring param = true
						moduleBind(cls.asInstanceOf[Class[Module]], null)
						logV(s"AFTER RESTORED  ${cls.getSimpleName}")
					}
					catch {case e: Throwable => logE(e)}
				}
		}
	}
	private def updateRestorablesPref(): Unit = {
		Prefs("_restorables_") = restorables.map(_.getName).toList
		logV(s"Restorables > ${Prefs[String]("_restorables_")}")
	}

	/* SUBSTITUTE CLASS */
	def setPreferedModuleClass[SUP <: Module, SUB <: SUP](implicit superClas: Manifest[SUP], subClas: Manifest[SUB]): Unit = {
		subs += superClas.runtimeClass -> subClas.runtimeClass
	}
	def getPreferedModuleClass[S <: Module](clas: Class[S]): Class[S] = subs.get(clas) match {
		case None => clas
		case Some(subclass) => subclass.asInstanceOf[Class[S]]
	}


	/* ACTIVITY */
	def onActivityCreate(a: Activity): Unit = getActivityModule(a).onActivityCreate()
	def onActivityDestroy(a: Activity): Unit = getActivityModule(a).onActivityDestroy()
	def onActivityRestoreState(a: Activity, state: Bundle): Unit = getActivityModule(a).onRestoreState(state)
	def onActivitySaveState(a: Activity, state: Bundle): Unit = getActivityModule(a).onSaveState(state)
	def getActivityModule(a: Activity): ActivityModule = {
		modules.collectFirst { case m: ActivityModule if m.pairedActivity(a) && !m.isDestroyed => m } match {
			case Some(m) => m
			case None => a match {
				case a: TwixActivity[_, _] => constrModule(a.moduleClass()).setActivityClass(a.getClass)
				case _ => new ActivityModule {}.setActivityClass(a.getClass) // new inst of new class
			}
		}
	}

}









/* EXCEPTIONS */
class ModuleException(message: String) extends Exception(message) {
	def this() = this("")
}

object ModuleInactiveException extends ModuleException("Module cannot execute request because it's not yet activated.")

case class DependencyParentException(m: Module) extends ModuleException(s"Dependency parent ${m.moduleID} failed with  ${m.failure.foreach(_.getMessage)}")

case class CyclicUsageException(trace: String) extends ModuleException(s"Cyclic usage detected in chain [$trace]")

