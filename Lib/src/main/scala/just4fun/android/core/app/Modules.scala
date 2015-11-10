package just4fun.android.core.app

import android.app.{Activity, Application, Notification}
import android.content.res.Configuration
import android.content.{Context, Intent}
import android.os.{Build, Process}
import android.util.Log
import just4fun.android.core.LibRoot
import just4fun.android.core.vars.Prefs
import just4fun.utils.logger.Logger._
import just4fun.utils.logger.{Logger, LoggerConfig}

import scala.collection.mutable
import scala.language.experimental.macros

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
	private val KEY_LAUNCHED = "app_launched_"
	private var i: Modules = null
	private[app] var mManager: ModuleManager = null
	private[app] var aManager: ActivityManager = null
	private var firstRun = false
	private[app] lazy val libResources = i.libResources
	private val singletons = mutable.Set[AppSingleton]()
	
	/* PUBLIC */
	val processID = Process.myPid()
	val processUID = Process.myUid()
	lazy val context: Context = i
	def uiContext = aManager.uiContext
	def uiVisible = aManager.uiVisible
	def uiAlive = aManager.uiAlive
	def isFirstRun = firstRun
	
	
	def use[M <: Module : Manifest](implicit context: Context): M = macro Macros.use[M]
	def unchecked_use[M <: Module : Manifest](implicit context: Context): M = {
		mManager.moduleUse[M]
	}
	def bind[M <: Module : Manifest](implicit activity: Activity): M = macro Macros.bindA[M]
	def unchecked_bind[M <: Module : Manifest](implicit activity: Activity): M = {
		mManager.moduleBind[M](mManager.getActivityModule(activity))
	}
	def bind[M <: Module : Manifest](clas: Class[M])(implicit activity: Activity): M = macro Macros.bindAC[M]
	def unchecked_bind[M <: Module : Manifest](clas: Class[M])(implicit activity: Activity): M = {
		mManager.moduleBind[M](clas, mManager.getActivityModule(activity), false, false)
	}
	def unbind[M <: Module : Manifest](implicit activity: Activity): Unit = macro Macros.unbindA[M]
	def unchecked_unbind[M <: Module : Manifest](implicit activity: Activity): Unit = {
		mManager.moduleUnbind[M](mManager.getActivityModule(activity))
	}
	def unbind[M <: Module : Manifest](clas: Class[M])(implicit activity: Activity): Unit = macro Macros.unbindAC[M]
	def unchecked_unbind[M <: Module : Manifest](clas: Class[M])(implicit activity: Activity): Unit = {
		mManager.moduleUnbind[M](clas, mManager.getActivityModule(activity))
	}
	def startForeground(id: Int, notification: Notification): Unit = {
		KeepAliveService.startForeground(id, notification)
	}
	def stopForeground(removeNotification: Boolean): Unit = {
		KeepAliveService.stopForeground(removeNotification)
	}
	// todo def setStatusNotification
	
	/* LOCAL LIB API */
	def systemService[T](contextServiceName: String): T = {
		context.getSystemService(contextServiceName).asInstanceOf[T]
	}
	def hasPermission(pm: String): Boolean = {
		mManager.hasPermission(pm)
	}
	
	
	/* INTERNAL API */
	private def onCreate(app: Modules): Unit = {
		i = app
		//
		implicit val cache = Prefs.syscache
		firstRun = !Prefs.contains(KEY_LAUNCHED)
		if (firstRun) Prefs(KEY_LAUNCHED) = 1
		//
		mManager = new ModuleManager(i)
		aManager = new ActivityManager(i, mManager)
		mManager.checkRestore()
	}
	private def onInit(): Unit = {}
	private def onTrimMemory(level: Int): Unit = {
		MemoryState.level2state(level) match {
			case null =>
			case state => mManager.trimMemory(state)
				singletons.foreach(_.trimMemory(state))
		}
	}
	private def onExit(): Unit = {
		singletons.foreach(_.trimMemory(MemoryState.DROP))
	}
	private[app] def addSingleton(s: AppSingleton): Unit = {
		singletons.add(s)
	}
}






/* ANDROID APP  */

trait Modules extends Application {
	/** Override to adapt library resources to your app. */
	val libResources: LibResources = new LibResources

	LoggerConfig.debug(true).logDef(Log.println(_, _, _))
	
	
	protected[this] def onModulesInit(): Unit = {}
	protected[this] def onModulesExit(): Unit = {}
	
	override def onCreate(): Unit = {
		super.onCreate()
		LoggerConfig.debug(isDebug)
		  .addPackageRoot(classOf[LibRoot].getPackage.getName)
		  .addPackageRoot(getPackageName)
		logV(s"<<<<<<<<<<<<<<<<<<<<                    APP   CREATED                    >>>>>>>>>>>>>>>>>>>>")
		checkRequiredManifestEntries()
		Modules.onCreate(this)
	}
	
	override def onTrimMemory(level: Int): Unit = Modules.onTrimMemory(level)
	
	override def onConfigurationChanged(newConfig: Configuration): Unit = Modules.mManager.onConfigurationChanged(newConfig)

	private[app] def modulesInit(): Unit = {
		Modules.onInit()
		try onModulesInit() catch loggedE
	}
	private[app] def modulesExit(): Unit = {
		try onModulesExit() catch loggedE
		Modules.onExit()
	}
	
	private[this] def isDebug: Boolean = {
		try {
			val clas = Class.forName(getPackageName + ".BuildConfig")
			val valueF = clas.getDeclaredField("DEBUG")
			valueF.setAccessible(true)
			valueF.getBoolean(null)
		} catch {case e: Throwable => println(e); false}
	}
	private[this] def checkRequiredManifestEntries(): Unit = {
		val warn = new StringBuilder()
		// check KeepAliveService
		val clas: Class[_] = classOf[KeepAliveService]
		val intent = new Intent(this, clas)
		val resolvers: java.util.List[_] = getPackageManager.queryIntentServices(intent, 0)
		if (resolvers == null || resolvers.size() == 0) warn ++= s"""\n<service android:name="${clas.getName}"/>"""
		if (warn.nonEmpty) throw new Exception(s"The following components are required by ${classOf[LibRoot].getPackage.getName} library and should be declared in your AndroidManifest.xml:$warn")
	}
}







/* APP LIFECYCLE EVENT LISTENER */
object MemoryState extends Enumeration {
	import android.content.ComponentCallbacks2._
	type MemoryState = Value
	val DROP, CRITICAL, LOW, MODERATE, UI_HIDDEN = Value
	
	private[app] def level2state(level: Int): MemoryState = level match {
		case TRIM_MEMORY_UI_HIDDEN => UI_HIDDEN
		case TRIM_MEMORY_RUNNING_CRITICAL | TRIM_MEMORY_COMPLETE => CRITICAL
		case TRIM_MEMORY_RUNNING_LOW | TRIM_MEMORY_MODERATE => LOW
		case TRIM_MEMORY_RUNNING_MODERATE | TRIM_MEMORY_BACKGROUND => MODERATE
		case _ => null
	}
}

/** Use only lazy vars that can be reset and reinitialized again.*/
trait AppSingleton {
	import MemoryState._
	Modules.addSingleton(this)

	protected[this] def onTrimMemory(state: MemoryState): Unit
	
	private[app] def trimMemory(state: MemoryState) = { try onTrimMemory(state) catch loggedE }
}
