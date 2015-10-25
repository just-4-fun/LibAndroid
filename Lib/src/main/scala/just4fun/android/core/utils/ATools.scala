package just4fun.android.core.utils

import android.content.Context

object ATools {
	def dp2pix(dp: Int)(implicit context: Context): Int = {
		val displayMetrics = context.getResources.getDisplayMetrics
		((dp * displayMetrics.density) + 0.5).toInt
	}
	def pix2dp(pix: Int)(implicit context: Context): Int = {
		val displayMetrics = context.getResources.getDisplayMetrics
		((pix / displayMetrics.density) + 0.5).toInt
	}
}
