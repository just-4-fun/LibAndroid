package just4fun.android.libtest.test11

import android.app.Activity
import android.content.Intent
import android.os.{Build, Bundle}
import android.view.View
import android.view.View.OnClickListener
import android.widget.Button
import just4fun.android.core.app.{TwixModule, Modules, TwixActivity}
import just4fun.android.core.async.FutureX
import just4fun.android.libtest.{test11, TestModule, R}
import just4fun.utils.logger.Logger._

class TestActivity extends TwixActivity[TestActivity, MainModule] {
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)
		findViewById(R.id.button1).asInstanceOf[Button].setOnClickListener(new OnClickListener {
			override def onClick(v: View): Unit = {
				module.startActivity()
			}
		})
	}
}

class NoActivity extends Activity {
	override def onCreate(savedInstanceState: Bundle) {
		super.onCreate(savedInstanceState)
		logD(s"NO ACTIVITY created >>>>>>>>>>>>>>>>>>>>>")
		val perms = android.Manifest.permission.BODY_SENSORS ::
		  android.Manifest.permission.CALL_PHONE ::
		  android.Manifest.permission.SEND_SMS ::
		  android.Manifest.permission.INTERNET ::
		  Nil
//		if (Build.VERSION.SDK_INT >= 23) requestPermissions(perms.toArray, 1)
//		finish()
	}

//	override def onRequestPermissionsResult(requestCode: Int, permissions: Array[String], grantResults: Array[Int]): Unit = {
//		logD(s"$requestCode: PERMISSIONS>> ${permissions.zip(grantResults)}")
//		finish()
////		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//	}
	override def onDestroy(): Unit = {
		super.onDestroy()
		logD(s"NO ACTIVITY destroyed <<<<<<<<<<<<<<<<<<<<<")
	}
}



class MainModule extends TwixModule[TestActivity, MainModule]
with TestModule {
	startAfter = 1000
	stopAfter = 1000
	val perms = android.Manifest.permission.BODY_SENSORS ::
	  android.Manifest.permission.CALL_PHONE ::
	  android.Manifest.permission.SEND_SMS ::
	  android.Manifest.permission.INTERNET ::
	  Nil
	bind[Module_1]
	//	bindSync[Module_1]
	/* PERMISSIONS API */
//	override protected[this] def requiredPermissions: Seq[String] = {
//		perms
//	}
	override protected[this] def onActivatingStart(firstTime: Boolean): Unit = {
//		logD(s"Permissions: \n${perms.map(p => p+" ? "+hasPermission(p)).mkString("\n")}")
		super.onActivatingStart(firstTime)
	}
	def startActivity(): Unit = {
		appContext.	startActivity{
			val i = new Intent(appContext, classOf[NoActivity])
			i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			i
		}

	}
}


class Module_1 extends TestModule {
	startAfter = 1000
	stopAfter = 1000
}

