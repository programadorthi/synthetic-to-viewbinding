package dev.programadorthi.migration.model

import com.intellij.psi.PsiReference

data class AndroidView(
    val reference: PsiReference?,
    val isIncludeTag: Boolean,
    val layoutNameWithoutExtension: String,
    val rootTagName: String,
    val viewId: String
)
