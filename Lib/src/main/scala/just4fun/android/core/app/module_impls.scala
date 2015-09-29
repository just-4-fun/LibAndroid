package just4fun.android.core.app

import scala.language.experimental.macros

import android.app.Activity
import android.os.Bundle
import just4fun.android.core.vars.TempVar


/* ACTIVITY HOLDER */
trait ActivityModule extends Module {
	private[this] var activityClass: Class[_] = null
	private[this] var counter = 0
	private[this] var tempVars = List[TempVar[_]]()
	bindSelf

	private[app] def setActivityClass(aCls: Class[_]): ActivityModule = {
		activityClass = aCls
		this
	}
	private[app] def pairedActivity(a: Activity): Boolean = a.getClass == activityClass
	private[app] def onActivityCreate(): Unit = {
		counter += 1
	}
	private[app] def onActivityDestroy(): Unit = {
		counter -= 1
		if (counter == 0) unbindSelf
	}

	private[core] def registerTempVar(v: TempVar[_]): Unit = tempVars = v :: tempVars
	private[app] def onSaveState(state: Bundle): Unit = tempVars.foreach(_.save(state))
	private[app] def onRestoreState(state: Bundle): Unit = tempVars.foreach(_.load(state))
}





/* TWIX MODULE */
abstract class TwixModule[A <: TwixActivity[A, M] : Manifest, M <: TwixModule[A, M]] extends ActivityModule {
	// TODO disable other bindings
	// can be optimized if track twixes
	def ui: Option[A] = Modules.uiContext match {
		case Some(a: A) => Some(a)
		case _ => None
	}
}




/* TWIX ACTIVITY */
class TwixActivity[A <: TwixActivity[A, M], M <: TwixModule[A, M] : Manifest] extends Activity {
	implicit protected val _this: this.type = this
	Modules.aManager.onActivityConstructed(this)
	val module: M = Modules.mManager.getActivityModule(this).asInstanceOf[M]

	private[app] def moduleClass: Class[M] = implicitly[Manifest[M]].runtimeClass.asInstanceOf[Class[M]]
}
