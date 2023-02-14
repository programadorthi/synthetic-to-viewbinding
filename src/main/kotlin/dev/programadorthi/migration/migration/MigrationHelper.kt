package dev.programadorthi.migration.migration

import com.android.tools.idea.databinding.psiclass.LightBindingClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.InheritanceUtil
import dev.programadorthi.migration.model.BindingData
import org.jetbrains.kotlin.android.synthetic.AndroidConst
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

internal object MigrationHelper {

    fun tryMigrate(
        psiClass: PsiClass,
        ktClass: KtClass,
        bindingData: List<BindingData>,
        bindingClass: List<LightBindingClass>,
    ): Set<String> {
        val bindingsToImport = mutableSetOf<String>()
        val parents = InheritanceUtil.getSuperClasses(psiClass)
        for (parent in parents) {
            val migration: CommonMigration = when (parent.qualifiedName) {
                AndroidConst.ACTIVITY_FQNAME,
                AndroidConst.DIALOG_FQNAME -> ClassWithSetContentViewMigration(ktClass, bindingData, bindingClass)

                AndroidConst.VIEW_FQNAME -> ViewMigration(ktClass, bindingData, bindingClass)

                /*FileMigration.GROUPIE_ITEM_CLASS,
                FileMigration.GROUPIE_VIEW_HOLDER_CLASS -> GroupieMigration(
                    packageName = packageName,
                    ktClass = currentClass,
                )*/

                else -> continue
            }
            migration.doMigration()
            bindingsToImport.addAll(migration.bindingsToImport)
        }
        return bindingsToImport
    }

    private fun findGroupieFunctions(ktClass: KtClass): List<PsiElement> {
        val allClassFunctions = ktClass.allClassFunctions()
        if (allClassFunctions.isEmpty()) return emptyList()

        val result = mutableListOf<PsiElement>()
        for (function in allClassFunctions) {
            val parameters = function.valueParameterList?.parameters ?: continue
            val groupieViewHolderParameters = parameters
                .map { it.children.toList() }
                .flatten()
                .filter { it.text.startsWith("GroupieViewHolder") }
            if (groupieViewHolderParameters.isNotEmpty()) {
                result += function
            }
        }

        return result
    }

    private fun KtClass.allClassFunctions(): List<KtNamedFunction> =
        body?.children?.filterIsInstance<KtNamedFunction>() ?: emptyList()
}