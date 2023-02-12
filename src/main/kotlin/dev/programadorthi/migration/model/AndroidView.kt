package dev.programadorthi.migration.model

import com.intellij.psi.PsiReference

data class AndroidView(
    val reference: PsiReference?,
    val layoutNameWithoutExtension: String,
    val rootTagName: String,
    val viewId: String,
    val includeLayoutName: String? = null,
    val viewStubLayoutName: String? = null,
)
