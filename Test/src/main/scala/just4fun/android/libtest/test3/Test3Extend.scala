package just4fun.android.libtest.test3

import android.os.Bundle
import just4fun.android.core.app.{Module, TwixActivity, TwixModule}
import just4fun.android.core.vars.Prefs
import just4fun.android.libtest.{R, TestModule}
import just4fun.utils.logger.Logger
import Logger._

class TestActivity extends TwixActivity[TestActivity, MainModule]  {
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
	startAfter = 1000
	stopAfter = 1000
	val u1 = bind[UserModule_1]
	val u2 = bind[UserModule_2]
	override protected[this] def onActivatingFinish(firstTime: Boolean): Unit = {
		u1.startUse()
		u2.startUse()
	}
}


abstract class MetaModule extends Module with TestModule {
	startAfter = 1000
	stopAfter = 1000
	standbyMode = true
	def useMe(msg: String) = serveAsync{
		logV(s"<<<<<<<<<<<<<  USE ME [$msg]  >>>>>>>>>>>>>")
	}
}

abstract class MetaUserModule[M <: MetaModule: Manifest] extends Module with TestModule {
	startAfter = 1000
	stopAfter = 1000
	standbyMode = true
	val m = bindSync[M]
	override protected[this] def onActivatingFinish(firstTime: Boolean): Unit = {
		logV(s"<<<<<<<<<<<<<  ACTIVE [${getClass.getSimpleName}]  >>>>>>>>>>>>>")
	}
	def startUse(): Unit = serveAsync{
		m.useMe(getClass.getSimpleName)
	}
}

class MetaModuleX  /*private()  !!! throws runtime error*/ extends MetaModule {

}

class UserModule_1 extends MetaUserModule[MetaModuleX] {

}
class UserModule_2 extends MetaUserModule[MetaModuleX] {

}