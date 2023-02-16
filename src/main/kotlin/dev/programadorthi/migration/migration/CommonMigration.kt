package dev.programadorthi.migration.migration

import com.android.tools.idea.databinding.psiclass.LightBindingClass
import com.intellij.psi.PsiReference
import dev.programadorthi.migration.model.AndroidView
import dev.programadorthi.migration.model.BindingData
import dev.programadorthi.migration.visitor.SyntheticReferenceRecursiveVisitor
import org.jetbrains.kotlin.android.synthetic.AndroidConst
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

    companion object {
        const val GROUPIE_PACKAGE_PREFIX = "com.xwray.groupie.kotlinandroidextensions"
        private const val GROUPIE_ITEM_CLASS = "${GROUPIE_PACKAGE_PREFIX}.Item"
        private const val GROUPIE_VIEW_HOLDER_CLASS = "${GROUPIE_PACKAGE_PREFIX}.GroupieViewHolder"

        fun getInstance(
            parents: Set<String>,
            ktClass: KtClass,
            bindingData: List<BindingData>,
            bindingClass: List<LightBindingClass>,
        ): CommonMigration? {
            if (parents.contains(AndroidConst.ACTIVITY_FQNAME) || parents.contains(AndroidConst.DIALOG_FQNAME)) {
                return ClassWithSetContentViewMigration(ktClass, bindingData, bindingClass)
            }
            if (parents.contains(AndroidConst.VIEW_FQNAME)) {
                return ViewMigration(ktClass, bindingData, bindingClass)
            }
            if (parents.contains(GROUPIE_ITEM_CLASS) || parents.contains(GROUPIE_VIEW_HOLDER_CLASS)) {
                return GroupieMigration(ktClass, bindingClass)
            }
            return null
        }
    }
}