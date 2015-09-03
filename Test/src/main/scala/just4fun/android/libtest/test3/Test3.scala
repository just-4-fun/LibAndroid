package just4fun.android.libtest.test3

import android.os.Bundle
import just4fun.android.core.app.{Module, TwixActivity, TwixModule}
import just4fun.android.core.vars.Prefs
import just4fun.android.libtest.{R, TestModule}
import just4fun.utils.devel.ILogger._

class TestActivity extends TwixActivity[TestActivity, MainModule] with Loggable {
	implicit val context = this
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
	}
}


class MainModule extends TwixModule[TestActivity, MainModule]
//with NewThreadFeature
//with ParallelThreadFeature
with TestModule {
	override val activatingTimeout = 10000
	override val deactivatingTimeout = 10000
	startAfter = 1000
	stopAfter = 1000
	val u1 = bind[UserModule_1]
	val u2 = bind[UserModule_2]
	override protected[this] def onFinishActivating(firstTime: Boolean): Unit = {
		u1.startUse()
		u2.startUse()
	}
}


abstract class MetaModule extends Module with TestModule {
	startAfter = 1000
	stopAfter = 1000
	setPassiveMode()
	def useMe(msg: String) = execAsync{
		logV(s"<<<<<<<<<<<<<  USE ME [$msg]  >>>>>>>>>>>>>")
	}
}

abstract class MetaUserModule[M <: MetaModule: Manifest] extends Module with TestModule {
	startAfter = 1000
	stopAfter = 1000
	setPassiveMode()
	val m = dependOn[M]
	override protected[this] def onFinishActivating(firstTime: Boolean): Unit = {
		logV(s"<<<<<<<<<<<<<  ACTIVE [${getClass.getSimpleName}]  >>>>>>>>>>>>>")
	}
	def startUse(): Unit = execAsync{
		m.useMe(getClass.getSimpleName)
	}
}

class MetaModuleX  /*private()  !!! throws runtime error*/ extends MetaModule {

}

class UserModule_1 extends MetaUserModule[MetaModuleX] {

}
class UserModule_2 extends MetaUserModule[MetaModuleX] {

}