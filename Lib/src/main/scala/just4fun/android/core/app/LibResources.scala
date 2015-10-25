package just4fun.android.core.app

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.View.OnClickListener
import android.view.{ViewGroup, View, Gravity}
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams._
import android.widget._
import just4fun.android.core.utils.ATools

/** Override anything*/
class LibResources {

	/* Permissions */
	def permissionsInfo: Seq[PermissionExtra] = Nil
	def permissionsDialog(listener: PermissionDialogListener)(implicit context: Activity): PermissionDialogHelper = {
		new DefaultPermissionDialogHelper(listener)
	}
}
