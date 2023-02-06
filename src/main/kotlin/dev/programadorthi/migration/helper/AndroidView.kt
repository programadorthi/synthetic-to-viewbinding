package dev.programadorthi.migration.helper

import com.android.utils.HashCodes
import com.intellij.psi.PsiReference

data class AndroidView(
    val reference: PsiReference?,
    val isIncludeTag: Boolean,
    val layoutNameWithoutExtension: String,
    val rootTagName: String,
    val viewId: String
) {
    override fun equals(other: Any?): Boolean {
        return other is AndroidView &&
                layoutNameWithoutExtension == other.layoutNameWithoutExtension &&
                viewId == other.viewId
    }

    override fun hashCode(): Int {
        return HashCodes.mix(layoutNameWithoutExtension.hashCode(), viewId.hashCode())
    }
}
