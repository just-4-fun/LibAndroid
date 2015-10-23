package just4fun.android.libtest

import android.app.Application
import just4fun.android.core.app._
import just4fun.utils.logger.Logger._
import just4fun.utils.logger.LoggerConfig

class App extends Application with Modules {
	//	LoggerConfig.disableNonErrorLogs()
	override val libResources: LibResources = new AppResources

}


class AppResources extends LibResources {
	override def permissionsInfo: Seq[PermissionExtra] = {
		import android.Manifest.permission._
		PermissionCritical(WRITE_CONTACTS, R.string.app_name) ::
//		  PermissionCritical(READ_CONTACTS) ::
//		  PermissionDynamic(READ_PHONE_STATE) ::
//		  PermissionDynamic(CALL_PHONE) ::
//		  PermissionDynamic(RECEIVE_SMS) ::
//		  PermissionDynamic(SEND_SMS) ::
		  PermissionDynamic(CAMERA) ::
//		  PermissionDynamic(INTERNET) ::
//		  PermissionDynamic(FLASHLIGHT) ::
		  Nil
	}
	override val permDialogTitle = R.string.permDialogTitle
	override val permDialogMessage = R.string.permDialogMessage
	override val permDialogDontAsk = R.string.permDialogDontAsk

}
