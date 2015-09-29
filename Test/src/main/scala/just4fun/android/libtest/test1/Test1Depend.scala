package just4fun.android.libtest.test1

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.View.OnClickListener
import android.widget.TextView
import just4fun.android.core.app.{Module, TwixActivity, TwixModule}
import just4fun.android.core.async.ThreadPoolContextHolder
import just4fun.android.libtest.{R, TestModule}

class TestActivity extends TwixActivity[TestActivity, MainModule] {
	implicit val context = this
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
		findViewById(R.id.text).asInstanceOf[TextView].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = startActivity(new Intent(TestActivity.this, classOf[TestActivity]))
		})
//		logV(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>  " + getApplicationContext.getClass)
//		Services.bind[MainService]
	}
}



class MainModule extends TwixModule[TestActivity, MainModule] with TestModule {
	startAfter = 1000
	stopAfter = 1000
	//	dependsOn[MainService]
	bindSync[Module_1]
	bindSync[Module_2]
	bindSync[Module_3]
	bindSync[Module_4]
}


class Module_1 extends Module with TestModule with ThreadPoolContextHolder  {
	startAfter = 1000
	stopAfter = 1000
	bindSync[Module_2]
}


class Module_2 extends Module with TestModule {
	startAfter = 1000
	stopAfter = 1000
	bindSync[Module_3]
}


class Module_3 extends Module with TestModule {
	startAfter = 1000
	stopAfter = 1000
	bindSync[Module_4]
}


class Module_4  /*(v: Int)*/ extends Module with ThreadPoolContextHolder with TestModule {
	startAfter = 1000
	stopAfter = 1000
//	private def this() = this(0)
}
