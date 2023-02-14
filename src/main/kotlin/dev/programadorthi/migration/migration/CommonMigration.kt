package dev.programadorthi.migration.migration

import com.intellij.psi.PsiReference
import dev.programadorthi.migration.model.AndroidView
import dev.programadorthi.migration.visitor.SyntheticReferenceRecursiveVisitor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtPsiFactory

abstract class CommonMigration(
    private val ktClass: KtClass,
) {
    private val mutableBindingsToImport = mutableSetOf<String>()
    protected val psiFactory = KtPsiFactory(ktClass.project)

    val bindingsToImport: Set<String>
        get() = mutableBindingsToImport

    fun doMigration() {
        val visitor = SyntheticReferenceRecursiveVisitor()
        ktClass.accept(visitor)
        if (visitor.androidViews.isEmpty()) return
        process(visitor.androidViews, visitor.viewHolderItemViews)
    }

    protected fun addGenericImport(import: String) {
        mutableBindingsToImport += import
    }

    protected abstract fun process(androidViews: List<AndroidView>, viewHolderItemViews: List<PsiReference>)
}