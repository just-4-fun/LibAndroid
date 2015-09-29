package just4fun.android.libtest.test10

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.TextView
import just4fun.android.core.app._
import just4fun.android.core.async.{FutureX, ThreadPoolContextHolder}
import just4fun.android.core.vars.Prefs
import just4fun.android.core.vars.Prefs._
import just4fun.android.libtest.{TestModule, R}
import just4fun.utils.logger.Logger._

/*
TEST Ability of restoring Module with one Boolean argument constructor that was not terminated properly.
EXPECTED:  Run app first time and wait Module_1 to activate. Than termnate app via rerun within 20 seconds. Module_1 should be restored with restoredAndSelfBound=true  at app start not because MainModule bind.
* */

class TestActivity extends TwixActivity[TestActivity, MainModule] {
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
	}
	override def onDestroy(): Unit = {
		Modules.bind[Module_2]
		super.onDestroy()
	}
}


class MainModule extends TwixModule[TestActivity, MainModule]
with TestModule {
	startAfter = 1000
	stopAfter = 1000
	bindSync[Module_1]
}


class Module_1(restored: Boolean) extends TestModule {
	logD(s"[$moduleID]:  restored? $restored")
	startAfter = 1000
	stopAfter = 1000
//	restorableAfterAbort = true
	bindSelf
	override protected[this] def onActivatingFinish(initial: Boolean): Unit = {
		super.onActivatingFinish(initial)
		FutureX.post(20000)(unbindSelf)
	}
}


class Module_2(restored: Boolean) extends TestModule {
	logD(s"[$moduleID]:  restored? $restored")
	startAfter = 1000
	stopAfter = 1000
//	restorableAfterAbort = true
	bindSelf
	override protected[this] def onActivatingFinish(initial: Boolean): Unit = {
		super.onActivatingFinish(initial)
		FutureX.post(20000)(unbindSelf)
	}
}
