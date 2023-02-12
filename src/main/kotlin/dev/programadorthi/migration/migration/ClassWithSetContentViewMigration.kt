package dev.programadorthi.migration.migration

import android.databinding.tool.writer.ViewBinder
import com.intellij.psi.PsiElement
import dev.programadorthi.migration.model.BindingData
import dev.programadorthi.migration.model.BindingFunction
import dev.programadorthi.migration.model.BindingType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

internal class ClassWithSetContentViewMigration(
    private val ktClass: KtClass,
    bindingData: List<BindingData>,
) : CommonAndroidClassMigration(ktClass, bindingData) {

    override fun mapToFunctionAndType(
        bindingClassName: String,
        propertyName: String,
        rootNode: ViewBinder.RootNode,
    ): Pair<BindingFunction, BindingType> {
        val setContentView = findSetContentView(ktClass) ?: return BindingFunction.DEFAULT to BindingType.INFLATE
        val layoutNameAsBinding = setContentView.text
            .substringAfterLast('.')
            .removeSuffix(")")
            .layoutToBindingName()
        if (layoutNameAsBinding == bindingClassName) {
            val setContentViewWithBinding = SET_CONTENT_VIEW_TEMPLATE.format(propertyName)
            val expression = psiFactory.createExpression(setContentViewWithBinding)
            setContentView.replace(expression)
        }
        return BindingFunction.DEFAULT to BindingType.INFLATE
    }

    private fun findSetContentView(ktClass: KtClass): PsiElement? {
        val allBodyFunctions = ktClass.body?.children?.filterIsInstance<KtNamedFunction>() ?: return null
        return allBodyFunctions
            .filter { it.name == "onCreate" }
            .mapNotNull { it.bodyBlockExpression?.children?.toList() }
            .flatten()
            .firstOrNull { element -> element.text.contains("setContentView(R.layout.") }
    }

    private companion object {
        private const val SET_CONTENT_VIEW_TEMPLATE = "setContentView(%s.root)"
    }
}