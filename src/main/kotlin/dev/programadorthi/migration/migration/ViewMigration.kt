package dev.programadorthi.migration.migration

import android.databinding.tool.writer.ViewBinder
import com.android.tools.idea.databinding.psiclass.LightBindingClass
import com.android.tools.idea.kotlin.getQualifiedName
import dev.programadorthi.migration.model.BindingData
import dev.programadorthi.migration.model.BindingFunction
import dev.programadorthi.migration.model.BindingType
import org.jetbrains.kotlin.psi.KtClass

internal class ViewMigration(
    private val ktClass: KtClass,
    bindingData: List<BindingData>,
    bindingClass: List<LightBindingClass>,
) : CommonAndroidClassMigration(ktClass, bindingData, bindingClass) {
    override fun mapToFunctionAndType(
        bindingClassName: String,
        propertyName: String,
        rootNode: ViewBinder.RootNode,
    ): Pair<BindingFunction, BindingType> {
        if (rootNode is ViewBinder.RootNode.Merge) {
            // private val propertyName by viewBindingMergeTag(viewBindingName::inflate)
            return BindingFunction.AS_MERGE to BindingType.INFLATE
        }
        if (rootNode is ViewBinder.RootNode.View && rootNode.type.toString() == ktClass.getQualifiedName()) {
            // private val propertyName by viewBinding(viewBindingName::bind)
            return BindingFunction.DEFAULT to BindingType.BIND
        }
        // private val propertyName by viewBindingAsChild(viewBindingName::inflate)
        return BindingFunction.AS_CHILD to BindingType.INFLATE
    }
}