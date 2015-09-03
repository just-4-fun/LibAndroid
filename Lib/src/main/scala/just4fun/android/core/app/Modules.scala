package just4fun.android.core.app

import java.lang.Thread.UncaughtExceptionHandler

import scala.language.experimental.macros
import android.app.{Notification, Activity, Application}
import android.content.res.Configuration
import android.content.{ComponentCallbacks, Context}
import android.util.Log
import just4fun.android.core.vars.{Prefs, PrefVar}
import just4fun.core.schemify.PropType
import just4fun.utils.devel.ILogger
import just4fun.utils.devel.ILogger._
import just4fun.utils.schema.ArrayBufferType

import scala.collection.mutable.ArrayBuffer

/** USAGE
  - extend Application with [[Modules]] trait
  - declare Application in Manifest
  - declare KeepAliveService in Manifest
  - use Modules bind ... etc methods
  */

/*
TODO :
TODO :
TODO :
TODO :
NOTE: sequence:: app constr > app.oncreate > activity constr > ...
NOTE: in extremal resource case the last call is activity.onPause
NOTE: when Home paressed > app stopped and keepAlive started > low memory > app process is killed (wo callbacks) > when app launched again > keepAlive restarted even with ui after 10-30 seconds > when Home pressed > app stopped > keepAlive isnt started > process killed (wo callbacks)
NOTE: app ui stopped > low memo > keepAlive killed wo callbacks > after 4 minutes app constructed > keepAlive recreated

*/

/* APP  */

object Modules {
	var afterCrash = false // TODO

	private var i: Application = null
	private[app] var mManager: ModuleManager = null
	private[app] var aManager: ActivityManager = null
	private var exitCode: () => Unit = null

	/* PUBLIC */
	lazy val context: Context = i
	def uiContext = aManager.uiContext
	def onExited(code: => Unit): Unit = {
		exitCode = () => code
	}

	def bind[M <: Module: Manifest](implicit activity: Activity): M = macro Macros.bindA[M]
	def unchecked_bind[M <: Module: Manifest](implicit activity: Activity): M = {
		mManager.moduleBind[M](mManager.getActivityModule(activity))
	}
	def bind[M <: Module: Manifest](clas: Class[M])(implicit activity: Activity): M = macro Macros.bindAC[M]
	def unchecked_bind[M <: Module : Manifest](clas: Class[M])(implicit activity: Activity): M = {
		mManager.moduleBind[M](clas, mManager.getActivityModule(activity))
	}
	def unbind[M <: Module: Manifest](implicit activity: Activity): Unit = macro Macros.unbindA[M]
	def unchecked_unbind[M <: Module: Manifest](implicit activity: Activity): Unit = {
		mManager.moduleUnbind[M](mManager.getActivityModule(activity))
	}
	def unbind[M <: Module: Manifest](clas: Class[M])(implicit activity: Activity): Unit = macro Macros.unbindAC[M]
	def unchecked_unbind[M <: Module : Manifest](clas: Class[M])(implicit activity: Activity): Unit = {
		mManager.moduleUnbind[M](clas, mManager.getActivityModule(activity))
	}
	def bindSelf[M <: Module: Manifest](implicit context: Context): Unit = macro Macros.bindSelf[M]
	def unchecked_bindSelf[M <: Module: Manifest](implicit context: Context): Unit = {
		mManager.moduleBind[M](null)
	}
	def bindSelf[M <: Module : Manifest](clas: Class[M])(implicit context: Context): Unit =  macro Macros.bindSelfC[M]
	def unchecked_bindSelf[M <: Module : Manifest](clas: Class[M])(implicit context: Context): Unit = {
		mManager.moduleBind[M](clas, null)
	}
	def unbindSelf[M <: Module: Manifest](implicit context: Context): Unit = {
		mManager.moduleUnbind[M](null)
	}
	def unbindSelf[M <: Module : Manifest](clas: Class[M])(implicit context: Context): Unit = {
		mManager.moduleUnbind[M](clas, null)
	}
	def startForeground(id: Int, notification: Notification): Unit = {
		KeepAliveService.startForeground(id, notification)
	}
	def stopForeground(removeNotification: Boolean): Unit = {
		KeepAliveService.stopForeground(removeNotification)
	}
	def setPreferedModuleClass[SUP <: Module, SUB <: SUP](implicit currentClas: Manifest[SUP], preferedClas: Manifest[SUB]): Unit = {
		mManager.setPreferedModuleClass(currentClas, preferedClas)
	}		
		
		/**/
	private  def init(app: Application): Unit = {
		i = app
		mManager = new ModuleManager(i)
		aManager = new ActivityManager(i, mManager)
		mManager.checkRestore()
	}
	private[app] def onExit(): Unit = {
		if (exitCode != null) try exitCode() catch {case e: Throwable => }
	}
}


/* ANDROID APP  */
trait Modules extends Application with Loggable {
	ILogger.logDef(Log.println(_, _, _))
	Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler {
		private val sysErrorHandler = Thread.getDefaultUncaughtExceptionHandler
		override def uncaughtException(thread: Thread, ex: Throwable): Unit = {
			logE(ex)
			if (sysErrorHandler != null) sysErrorHandler.uncaughtException(thread, ex)
			else System.exit(2)
		}
	})
	logV(s"***************                                                              APP   CONSTRUCTED")

	override def onCreate(): Unit = {
		super.onCreate()
		Modules.init(this)
	}
	override def onTrimMemory(level: Int): Unit = Modules.mManager.onTrimMemory(level)
	override def onConfigurationChanged(newConfig: Configuration): Unit = Modules.mManager.onConfigurationChanged(newConfig)
}

