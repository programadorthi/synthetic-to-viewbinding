package dev.programadorthi.migration.migration

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.parents
import dev.programadorthi.migration.model.AndroidView
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.blockExpressionsOrSingle
import org.jetbrains.kotlin.psi.psiUtil.findFunctionByName
import org.jetbrains.kotlin.psi.psiUtil.getValueParameterList

internal class GroupieMigration(
    packageName: String,
    private val ktClass: KtClass,
) : CommonMigration(packageName, ktClass) {

    override fun process(androidViews: List<AndroidView>, viewHolderItemViews: List<PsiReference>) {
        val bindingReferences = mutableSetOf<String>()
        for (view in androidViews) {
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
                addBindingToImport(layoutNameAsBinding = layoutNameAsBinding)
                break
            }
            if (view.isIncludeTag) {
                val expression = psiFactory.createExpression(provideBindingExpression(view))
                view.reference.element.replace(expression)
            }
        }

        // itemView.layoutId can't be replaced by root.layoutId
        if (viewHolderItemViews.isNotEmpty()) {
            val property = psiFactory.createExpression("root")
            for (itemView in viewHolderItemViews) {
                itemView.element.replace(property)
            }
        }

        // Well, maybe happen and is good to abort
        if (bindingReferences.isEmpty()) return

        // Has current class references to multiple layouts? Is a generic ViewHolder?
        if (bindingReferences.size > 1) {
            addGenericImport("androidx.viewbinding.ViewBinding")
        }

        val bindingName = when {
            bindingReferences.size > 2 -> "ViewBinding"
            else -> bindingReferences.first()
        }

        tryUpdateViewAttachedOrDetachedFromWindow(bindingName = bindingName)
        tryReplaceSuperType(bindingName = bindingName)
        tryAddInitializeBindingFunction(bindingName = bindingName)
    }

    private fun tryUpdateViewAttachedOrDetachedFromWindow(bindingName: String) {
        val onViewParams = lookupForFunctionByName(name = "onViewAttachedToWindow") +
                lookupForFunctionByName(name = "onViewDetachedFromWindow")
        if (onViewParams.isEmpty()) return
        replaceFunctionParameterType(
            parameters = onViewParams,
            layoutNameAsBinding = "GroupieViewHolder<$bindingName>",
        )
        addGenericImport("com.xwray.groupie.viewbinding.GroupieViewHolder")
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
            addGenericImport("com.xwray.groupie.viewbinding.BindableItem")
        }
    }

    private fun tryAddInitializeBindingFunction(bindingName: String) {
        val bindFunction = ktClass.findFunctionByName("bind") ?: return
        val whitespace = psiFactory.createWhiteSpace("\n")
        val function = psiFactory.createFunction("override fun initializeViewBinding(view: View): $bindingName =\n" +
                "        $bindingName.bind(view)")
        bindFunction.addBefore(whitespace, bindFunction)
        bindFunction.addBefore(function, bindFunction)
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
}