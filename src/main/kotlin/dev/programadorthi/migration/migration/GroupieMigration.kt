package dev.programadorthi.migration.migration

import com.android.tools.idea.databinding.psiclass.LightBindingClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.parents
import dev.programadorthi.migration.ext.layoutToBindingName
import dev.programadorthi.migration.model.AndroidView
import dev.programadorthi.migration.model.BindingData
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.findFunctionByName
import org.jetbrains.kotlin.psi.psiUtil.getValueParameterList
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

internal class GroupieMigration(
    private val ktClass: KtClass,
    private val bindingClass: List<LightBindingClass>,
) : CommonMigration(ktClass) {

    override fun process(androidViews: List<AndroidView>, viewHolderItemViews: List<PsiReference>) {
        // If you are using more functions than bind(GroupieViewHolder, Int), we look for them
        val referencesByFunction = mutableMapOf<KtNamedDeclaration, List<AndroidView>>()
        for (view in androidViews) {
            val parents = view.reference?.element?.parents(true) ?: continue
            parents
                .filterIsInstance<KtNamedDeclaration>()
                .associateWith { it.lookupForGroupieViewHolderParameters() }
                .filterValues { it.isNotEmpty() }
                .forEach { (ktNamedDeclaration, _) ->
                    val currentList = referencesByFunction[ktNamedDeclaration] ?: emptyList()
                    referencesByFunction[ktNamedDeclaration] = currentList + view
                }
        }

        val bindingReferences = mutableSetOf<LightBindingClass>()
        for ((func, views) in referencesByFunction) {
            val candidates = mutableListOf<LightBindingClass>()
            val idsInsideFunction = views.map { it.viewId }.toSet()
            val bindingNames = views.map { androidView ->
                androidView.layoutNameWithoutExtension.layoutToBindingName()
            }.toSet()
            for (bindingName in bindingNames) {
                val candidatesByName = bindingClass
                    .filter { klass -> klass.name == bindingName }
                    .associateWith { it.fields }
                for ((candidateClass, fields) in candidatesByName) {
                    val fieldNames = fields.map { it.name }.toSet()
                    if (fieldNames.containsAll(idsInsideFunction)) {
                        candidates += candidateClass
                    }
                }
            }
            // Not found or multiple references are not supported
            if (candidates.size == 1) {
                val candidate = candidates.first()
                replaceFunctionParameterType(
                    parameters = func.lookupForGroupieViewHolderParameters(),
                    layoutNameAsBinding = candidate.name,
                )
                addGenericImport(candidate.qualifiedName)
            }
            bindingReferences.addAll(candidates)
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
            else -> bindingReferences.first().name
        }

        tryUpdateViewAttachedOrDetachedFromWindow(bindingName)
        tryReplaceSuperType(bindingName)
        tryAddInitializeBindingFunction(bindingName)
    }

    private fun tryUpdateViewAttachedOrDetachedFromWindow(bindingName: String) {
        val onViewParams = lookupForFunctionByName(name = "onViewAttachedToWindow") +
                lookupForFunctionByName(name = "onViewDetachedFromWindow")
        if (onViewParams.isEmpty()) return
        replaceFunctionParameterType(
            parameters = onViewParams,
            layoutNameAsBinding = "$GROUPIE_VIEW_HOLDER<$bindingName>",
        )
        addGenericImport("com.xwray.groupie.viewbinding.$GROUPIE_VIEW_HOLDER")
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
        val function = psiFactory.createFunction(
            "override fun initializeViewBinding(view: View): $bindingName =\n" +
                    "        $bindingName.bind(view)"
        )
        bindFunction.parent.run {
            addBefore(function, bindFunction)
            addBefore(whitespace, bindFunction)
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
        val extensionFunctionType = children.firstIsInstanceOrNull<KtTypeReference>()
        val usedAsExtensionType = extensionFunctionType?.text?.contains(GROUPIE_VIEW_HOLDER)
        val parameters = getValueParameterList()?.parameters ?: emptyList()
        val groupieViewHolderParameters = parameters.map {
            it.children.toList()
        }.flatten().filter {
            it.text.startsWith(GROUPIE_VIEW_HOLDER)
        }
        if (usedAsExtensionType == true) {
            if (groupieViewHolderParameters.isNotEmpty()) {
                // Well, how to solve type as extension and parameter in the same function?
                //
                // fun GroupieViewHolder.doSomething(viewHolder: GroupieViewHolder) {
                //     impossible to know what synthetics are used here
                // }
                return emptyList()
            }
            return listOf(extensionFunctionType)
        }
        return groupieViewHolderParameters
    }

    private companion object {
        private const val GROUPIE_VIEW_HOLDER = "GroupieViewHolder"
    }
}