package dev.programadorthi.migration.helper

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parents
import dev.programadorthi.migration.visitor.SyntheticReferenceRecursiveVisitor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.findFunctionByName
import org.jetbrains.kotlin.psi.psiUtil.getValueParameterList

internal class GroupieProcessCurrentClass(
    private val packageName: String,
    private val ktClass: KtClass,
) {
    private val psiFactory = KtPsiFactory(ktClass.project)
    private val mutableBindingsToImport = mutableSetOf<String>()

    val bindingToImport: Set<String>
        get() = mutableBindingsToImport

    fun collectAndMigrate() {
        val visitor = SyntheticReferenceRecursiveVisitor()
        ktClass.containingKtFile.accept(visitor)
        if (visitor.androidViews.isEmpty()) return

        val bindingReferences = mutableSetOf<String>()
        for (view in visitor.androidViews) {
            val parents = view.reference?.element?.parents(true) ?: continue
            for (parent in parents) {
                if (parent !is KtNamedDeclaration) continue
                val parameters = parent.lookupForGroupieViewHolderParameters()
                if (parameters.isEmpty()) continue
                val layoutNameAsBinding = layoutNameAsBinding(view.layoutNameWithoutExtension)
                replaceFunctionParameterType(
                    parameters = parameters,
                    layoutNameAsBinding = layoutNameAsBinding,
                )
                bindingReferences += layoutNameAsBinding
                mutableBindingsToImport += VIEW_BINDING_IMPORT_TEMPLATE.format(packageName, layoutNameAsBinding)
                break
            }
            if (view.isIncludeTag) {
                val expression = psiFactory.createExpression("${view.viewId}.root")
                view.reference.element.replace(expression)
            }
        }

        // itemView.layoutId can't be replaced by root.layoutId
        if (visitor.viewHolderItemViews.isNotEmpty()) {
            val property = psiFactory.createExpression("root")
            for (itemView in visitor.viewHolderItemViews) {
                itemView.element.replace(property)
            }
        }

        // Well, maybe happen and is good to abort
        if (bindingReferences.isEmpty()) return

        // Has current class references to multiple layouts? Is a generic ViewHolder?
        if (bindingReferences.size > 1) {
            mutableBindingsToImport += "androidx.viewbinding.ViewBinding"
        }

        val bindingName = when {
            bindingReferences.size > 2 -> "ViewBinding"
            else -> bindingReferences.first()
        }

        tryUpdateViewAttachedOrDetachedFromWindow(bindingName = bindingName)

        tryReplaceSuperType(bindingName = bindingName)
    }

    private fun tryUpdateViewAttachedOrDetachedFromWindow(bindingName: String) {
        val onViewParams = lookupForFunctionByName(name = "onViewAttachedToWindow") +
                lookupForFunctionByName(name = "onViewDetachedFromWindow")
        if (onViewParams.isEmpty()) return
        replaceFunctionParameterType(
            parameters = onViewParams,
            layoutNameAsBinding = "GroupieViewHolder<$bindingName>",
        )
        mutableBindingsToImport += "com.xwray.groupie.viewbinding.GroupieViewHolder"
    }

    private fun tryReplaceSuperType(bindingName: String) {
        val oldSuperTypes = ktClass.superTypeListEntries.filter { type ->
            type.text.startsWith("Item") || type.text.startsWith("Entry")
        }
        if (oldSuperTypes.isNotEmpty()) {
            val superType = psiFactory.createSuperTypeCallEntry("BindableItem<$bindingName>()")
            for (old in oldSuperTypes) {
                old.replace(superType)
            }
            mutableBindingsToImport += "com.xwray.groupie.viewbinding.BindableItem"
        }
    }

    private fun replaceFunctionParameterType(
        parameters: List<PsiElement>,
        layoutNameAsBinding: String,
    ) {
        if (parameters.isEmpty()) return
        val type = psiFactory.createType(layoutNameAsBinding)
        for (viewHolder in parameters) {
            viewHolder.replace(type)
        }
    }

    private fun lookupForFunctionByName(name: String): List<PsiElement> {
        val func = ktClass.findFunctionByName(name) ?: return emptyList()
        return func.lookupForGroupieViewHolderParameters()
    }

    private fun KtNamedDeclaration.lookupForGroupieViewHolderParameters(): List<PsiElement> {
        val parameters = getValueParameterList()?.parameters ?: return emptyList()
        return parameters.map {
            it.children.toList()
        }.flatten().filter {
            it.text.startsWith("GroupieViewHolder")
        }
    }

    private fun layoutNameAsBinding(layoutName: String): String =
        layoutName.split("_").joinToString(
            separator = "",
            postfix = "Binding"
        ) { name ->
            name.replaceFirstChar { it.uppercase() }
        }

    companion object {
        private const val VIEW_BINDING_IMPORT_TEMPLATE = "%s.databinding.%s"
    }
}