package dev.programadorthi.migration.migration

import android.databinding.tool.ext.parseXmlResourceReference
import android.databinding.tool.store.ResourceBundle
import android.databinding.tool.writer.ViewBinder
import com.intellij.psi.PsiReference
import dev.programadorthi.migration.model.AndroidView
import dev.programadorthi.migration.model.BindingData
import dev.programadorthi.migration.model.BindingFunction
import dev.programadorthi.migration.model.BindingType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassInitializer

abstract class CommonAndroidClassMigration(
    private val ktClass: KtClass,
    private val bindingData: List<BindingData>,
) : CommonMigration(ktClass) {
    private val bindingPropertyToCreate = mutableSetOf<String>()
    private val includePropertyToCreate = mutableSetOf<String>()
    private val syntheticBindingPropertyToCreate = mutableSetOf<String>()

    override fun process(androidViews: List<AndroidView>, viewHolderItemViews: List<PsiReference>) {
        // key: my_view_layout
        // value: [androidView1, androidView2, androidView3, ...]
        val syntheticsByLayout = androidViews.groupBy { androidView ->
            androidView.layoutNameWithoutExtension
        }
        check(syntheticsByLayout.size <= bindingData.size) {
            "Invalid operation. Current class [${ktClass.name}] is referencing more layouts than in the import list"
        }

        // All layouts found in the <include/> tags
        val includedLayouts = bindingData
            .map { it.baseLayoutModel.sortedTargets }
            .flatten()
            .filterNot { it.includedLayout.isNullOrBlank() }
            .associate { it.includedLayout to it.id.parseXmlResourceReference().name }

        for (data in bindingData) {
            val bindings = data.baseLayoutModel.sortedTargets
            val syntheticsById =
                syntheticsByLayout[data.baseLayoutModel.baseFileName]?.associateBy { it.viewId } ?: continue
            check(syntheticsById.size <= bindings.size) {
                "Class has more references than IDs in the layout ${data.layoutName}"
            }

            // Using <include/> ID as property name instead of viewBinding camelCase
            val viewBindingPropertyName = when (val includeId = includedLayouts[data.layoutName]) {
                null -> data.baseLayoutModel.bindingClassName.replaceFirstChar { it.lowercase() }
                else -> includeId
            }

            createBindingProperties(
                bindings = bindings,
                syntheticsById = syntheticsById,
                syntheticsByLayout = syntheticsByLayout,
                viewBindingPropertyName = viewBindingPropertyName,
            )

            // Stopping here because view binding creates <include/> ViewBinding automatically
            if (includedLayouts[data.layoutName] != null) continue

            val (bindingFunction, bindingType) = mapToFunctionAndType(
                bindingClassName = data.baseLayoutModel.bindingClassName,
                propertyName = viewBindingPropertyName,
                rootNode = data.rootNode,
            )
            bindingPropertyToCreate += provideTemplate(
                propertyName = viewBindingPropertyName,
                bindingClassName = data.baseLayoutModel.bindingClassName,
                bindingFunction = bindingFunction,
                bindingType = bindingType,
            )

            addGenericImport(BINDING_FUNCTION_IMPORT_TEMPLATE.format(bindingFunction.value))
            removeExistingViewInflate(layoutName = data.layoutName)
        }

        // The loop order matters
        for (property in syntheticBindingPropertyToCreate) {
            addProperty(content = property)
        }
        addBlankSpace()
        for (property in includePropertyToCreate) {
            addProperty(content = property)
        }
        addBlankSpace()
        for (property in bindingPropertyToCreate) {
            addProperty(content = property)
        }
        addBlankSpace()
    }

    private fun createBindingProperties(
        bindings: List<ResourceBundle.BindingTargetBundle>,
        syntheticsById: Map<String, AndroidView>,
        syntheticsByLayout: Map<String, List<AndroidView>>,
        viewBindingPropertyName: String
    ) {
        for (binding in bindings) {
            // ViewBinding is generated for view having ID only
            val viewId = binding.id?.parseXmlResourceReference()?.name ?: continue

            // Well, ID out of synthetic references are not in usage in the class
            if (syntheticsById[viewId] == null) continue

            if (binding.isBinder && !binding.includedLayout.isNullOrBlank()) {
                createIncludeProperty(
                    syntheticsByLayout = syntheticsByLayout,
                    binding = binding,
                    viewId = viewId,
                    viewBindingPropertyName = viewBindingPropertyName,
                )
            } else if (binding.viewName.endsWith("ViewStub")) {
                createViewStubProperty(
                    viewBindingPropertyName = viewBindingPropertyName,
                    viewId = viewId,
                    layoutName = syntheticsById[viewId]?.viewStubLayoutName,
                )
            } else {
                syntheticBindingPropertyToCreate += SYNTHETIC_BINDING_AS_LAZY_TEMPLATE.format(
                    viewId, viewBindingPropertyName, viewId,
                )
            }
        }
    }

    private fun createIncludeProperty(
        syntheticsByLayout: Map<String, List<AndroidView>>,
        binding: ResourceBundle.BindingTargetBundle,
        viewId: String,
        viewBindingPropertyName: String
    ) {
        val viewsInTheIncludedLayout = syntheticsByLayout[binding.includedLayout] ?: return
        if (viewsInTheIncludedLayout.isEmpty()) return
        val interfaceType = binding.interfaceType.substringAfterLast(".")
        // ViewBinding automatically references <include/> layouts as Binding too
        includePropertyToCreate += SYNTHETIC_BINDING_WITH_INCLUDE_AS_LAZY_TEMPLATE.format(
            viewId, interfaceType, viewBindingPropertyName, viewId
        )
    }

    private fun createViewStubProperty(viewBindingPropertyName: String, viewId: String, layoutName: String?) {
        val bindingClassName = layoutName?.layoutToBindingName() ?: return
        syntheticBindingPropertyToCreate += SYNTHETIC_BINDING_WITH_VIEW_STUB_AS_LAZY_TEMPLATE.format(
            viewId, viewBindingPropertyName, viewId, bindingClassName,
        )
    }

    private fun removeExistingViewInflate(layoutName: String) {
        val declarations = ktClass.body?.declarations ?: return
        val regex = """inflate\(.*R\.layout\.$layoutName""".toRegex()
        val initializers = declarations.filterIsInstance<KtClassInitializer>()
        for (ini in initializers) {
            val children = ini.body?.children ?: continue
            val inflatesToRemove = children.filter { it.text.contains(regex) }
            if (inflatesToRemove.isEmpty()) continue
            if (children.size == 1) {
                ini.delete()
            } else {
                inflatesToRemove.forEach { it.delete() }
            }
        }
    }

    private fun addBlankSpace() {
        val body = ktClass.body ?: return
        val whitespace = psiFactory.createWhiteSpace("\n")
        body.addAfter(whitespace, body.lBrace)
    }

    private fun addProperty(content: String) {
        val body = ktClass.body ?: return
        val property = psiFactory.createProperty(content)
        body.addAfter(property, body.lBrace)
    }

    private fun provideTemplate(
        propertyName: String,
        bindingClassName: String,
        bindingFunction: BindingFunction,
        bindingType: BindingType,
    ): String = BINDING_PROPERTY_TEMPLATE.format(
        propertyName, bindingFunction.value, bindingClassName, bindingType.value,
    )

    abstract fun mapToFunctionAndType(
        bindingClassName: String,
        propertyName: String,
        rootNode: ViewBinder.RootNode,
    ): Pair<BindingFunction, BindingType>

    companion object {
        private const val BINDING_FUNCTION_IMPORT_TEMPLATE = "co.stone.cactus.utils.ktx.%s"
        private const val BINDING_PROPERTY_TEMPLATE = "private val %s by %s(%s::%s)"
        private const val SYNTHETIC_BINDING_AS_LAZY_TEMPLATE = "private val %s by lazy { %s.%s }"
        private const val SYNTHETIC_BINDING_WITH_INCLUDE_AS_LAZY_TEMPLATE = "private val %s:%s by lazy { %s.%s }"
        private const val SYNTHETIC_BINDING_WITH_VIEW_STUB_AS_LAZY_TEMPLATE = """private val %s by lazy {
                val view = %s.%s.inflate()
                %s.bind(view)
            }
        """
    }
}