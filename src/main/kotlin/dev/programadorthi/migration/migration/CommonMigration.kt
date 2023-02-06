package dev.programadorthi.migration.migration

import com.intellij.psi.PsiReference
import dev.programadorthi.migration.model.AndroidView
import dev.programadorthi.migration.visitor.SyntheticReferenceRecursiveVisitor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtPsiFactory

abstract class CommonMigration(
    private val packageName: String,
    private val ktClass: KtClass,
) {
    private val mutableBindingsToImport = mutableSetOf<String>()
    protected val psiFactory = KtPsiFactory(ktClass.project)

    val bindingToImport: Set<String>
        get() = mutableBindingsToImport

    fun doMigration() {
        val visitor = SyntheticReferenceRecursiveVisitor()
        ktClass.containingKtFile.accept(visitor)
        if (visitor.androidViews.isEmpty()) return
        process(visitor.androidViews, visitor.viewHolderItemViews)
    }

    protected fun layoutNameAsBinding(layoutName: String): String =
        layoutName.split("_").joinToString(
            separator = "",
            postfix = "Binding"
        ) { name ->
            name.replaceFirstChar { it.uppercase() }
        }

    protected fun provideBindingExpression(view: AndroidView): String =
        if (view.isIncludeTag) "${view.viewId}.root" else view.viewId

    protected fun addBindingToImport(layoutNameAsBinding: String) {
        mutableBindingsToImport += VIEW_BINDING_IMPORT_TEMPLATE.format(packageName, layoutNameAsBinding)
    }

    protected fun addGenericImport(import: String) {
        mutableBindingsToImport += import
    }

    protected abstract fun process(androidViews: List<AndroidView>, viewHolderItemViews: List<PsiReference>)

    private companion object {
        private const val VIEW_BINDING_IMPORT_TEMPLATE = "%s.databinding.%s"
    }
}