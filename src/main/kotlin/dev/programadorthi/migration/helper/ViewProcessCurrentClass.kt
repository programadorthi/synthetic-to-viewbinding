package dev.programadorthi.migration.helper

import org.jetbrains.kotlin.psi.KtClass

internal class ViewProcessCurrentClass(
    packageName: String,
    private val ktClass: KtClass,
) : ProcessCurrentClass(packageName, ktClass) {
    override fun process(
        bindingName: String,
        propertyName: String,
        rootTag: String,
    ): Pair<BindingFunction, BindingType> = when {
        // private val propertyName by viewBinding(viewBindingName::bind)
        rootTag.endsWith("${ktClass.name}") -> BindingFunction.DEFAULT to BindingType.BIND
        // private val propertyName by viewBindingMergeTag(viewBindingName::inflate)
        rootTag == "merge" -> BindingFunction.AS_MERGE to BindingType.INFLATE
        // private val propertyName by viewBindingAsChild(viewBindingName::inflate)
        else -> BindingFunction.AS_CHILD to BindingType.INFLATE
    }
}