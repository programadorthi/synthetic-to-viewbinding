package dev.programadorthi.migration.migration

import dev.programadorthi.migration.model.BindingFunction
import dev.programadorthi.migration.model.BindingType
import org.jetbrains.kotlin.psi.KtClass

internal class ViewMigration(
    packageName: String,
    private val ktClass: KtClass,
) : CommonAndroidClassMigration(packageName, ktClass) {
    override fun mapToFunctionAndType(
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