package dev.programadorthi.migration.migration

import com.intellij.psi.PsiElement
import dev.programadorthi.migration.model.BindingFunction
import dev.programadorthi.migration.model.BindingType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody

internal class ClassWithSetContentViewMigration(
    packageName: String,
    private val ktClass: KtClass,
) : CommonAndroidClassMigration(packageName, ktClass) {

    override fun mapToFunctionAndType(
        bindingName: String,
        propertyName: String,
        rootTag: String,
    ): Pair<BindingFunction, BindingType> {
        val body = ktClass.body ?: error("${ktClass.name} has null body")
        val setContentView = tryFindSetContentViewInsideOnCreate(body)
        val layoutName = setContentView.text.substringAfterLast('.').removeSuffix(")")
        val layoutNameAsBinding = layoutNameAsBinding(layoutName)
        if (layoutNameAsBinding == bindingName) {
            val setContentViewWithBinding = SET_CONTENT_VIEW_TEMPLATE.format(propertyName)
            val expression = psiFactory.createExpression(setContentViewWithBinding)
            setContentView.replace(expression)
        }
        return BindingFunction.DEFAULT to BindingType.INFLATE
    }

    private fun tryFindSetContentViewInsideOnCreate(body: KtClassBody): PsiElement {
        for (func in body.functions) {
            if (func.name != "onCreate") continue
            val children = func.bodyBlockExpression?.children ?: continue
            return children.firstOrNull { element -> element.text.contains("setContentView") } ?: continue
        }
        error("Invalid class ${ktClass.name} because it body has no onCreate and setContentView")
    }

    private companion object {
        private const val SET_CONTENT_VIEW_TEMPLATE = "setContentView(%s.root)"
    }
}