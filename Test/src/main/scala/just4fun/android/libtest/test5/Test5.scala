package just4fun.android.libtest.test5

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.TextView
import just4fun.android.core.app.{Modules, Module, TwixModule, TwixActivity}
import just4fun.android.core.async.NewThreadContextHolder
import just4fun.android.libtest.{TestModule, R}
import just4fun.utils.devel.ILogger._


class TestActivity extends TwixActivity[TestActivity, MainModule] with Loggable {
//class TestActivity extends Activity with Loggable {
	implicit val context = this
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
		findViewById(R.id.text).asInstanceOf[TextView].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = startActivity(new Intent(TestActivity.this, classOf[TestActivity]))
		})
		//		logV(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>  " + getApplicationContext.getClass)
//		Modules.bind[MainModule]
	}
}


class MainModule extends TwixModule[TestActivity, MainModule] with TestModule {
//class MainModule extends Module with TestModule {
	//	override def restoreAfterCrashPolicy = RestoreAfterCrashPolicy.IF_SELFBOUND
	override val activatingTimeout = 10000
	override val deactivatingTimeout = 10000
	startAfter = 1000
	stopAfter = 9000
	dependOn[Module_1]
}



class Module_1 extends Module with TestModule with NewThreadContextHolder with Loggable {
	startAfter = 1000
	stopAfter = 1000
}
