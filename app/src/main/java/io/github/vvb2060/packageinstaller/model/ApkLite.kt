package io.github.vvb2060.packageinstaller.model

import android.content.res.Resources
import android.graphics.drawable.Drawable

class ApkLite(
    val packageName: String,
    val splitName: String?,
    val requiredSplitTypes: String?,
    val splitTypes: String?,
    val versionCode: Long,
    val versionName: String,
    val minSdkVersion: String,
    val targetSdkVersion: String,
    @JvmField
    var label: String?,
    @JvmField
    var icon: Drawable?,
) {
    var zip: Boolean = false

    fun getIcon(): Drawable {
        return icon ?: Resources.getSystem()
            .getDrawable(android.R.drawable.sym_def_app_icon, null)
    }

    fun getLabel(): String {
        return label ?: packageName
    }

    fun isSplit(): Boolean {
        return !zip && splitName != null && splitName.isNotEmpty()
    }

    fun needSplit(): Boolean {
        return !zip && (isSplit() || requiredSplitTypes != null || splitTypes != null)
    }
}
