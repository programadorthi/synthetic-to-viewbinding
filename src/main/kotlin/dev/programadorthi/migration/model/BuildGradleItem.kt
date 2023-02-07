package dev.programadorthi.migration.model

import com.intellij.psi.PsiElement

sealed class BuildGradleItem {
    data class ToAdd(
        val anchor: PsiElement,
        val expression: String,
    ) : BuildGradleItem()

    data class ToDelete(val element: PsiElement) : BuildGradleItem()
}
