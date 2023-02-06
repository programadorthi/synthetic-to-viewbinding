package dev.programadorthi.migration.helper

import dev.programadorthi.migration.visitor.SyntheticReferenceRecursiveVisitor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtPsiFactory

abstract class ProcessCurrentClass(
    private val packageName: String,
    private val ktClass: KtClass,
) {
    private val bindingPropertyToCreate = mutableSetOf<String>()
    private val mutableBindingsToImport = mutableSetOf<String>()
    private val syntheticBindingPropertyToCreate = mutableSetOf<String>()

    val bindingToImport: Set<String>
        get() = mutableBindingsToImport

    fun collectAndMigrate() {
        val visitor = SyntheticReferenceRecursiveVisitor()
        ktClass.accept(visitor)
        if (visitor.androidViews.isEmpty()) return

        // key: my_view_layout
        // value: layout root tag
        val filesReferencedByCurrentClass = visitor.androidViews.associate { androidView ->
            androidView.layoutNameWithoutExtension to androidView.rootTagName
        }

        // key: my_view_layout
        // value: [androidView1, androidView2, androidView3, ...]
        val syntheticsByLayout = visitor.androidViews.groupBy { androidView ->
            androidView.layoutNameWithoutExtension
        }

        val isThereMultibindingInClass = filesReferencedByCurrentClass.size > 1
        for ((layoutName, rootTag) in filesReferencedByCurrentClass) {
            mapLayoutNameToViewBindingProperty(
                layoutName = layoutName,
                isThereMultibindingInClass = isThereMultibindingInClass,
                rootTag = rootTag,
                androidViewInLayout = syntheticsByLayout[layoutName] ?: emptyList(),
            )
            removeExistingViewInflate(ktClass.body, layoutName)
        }

        for (property in syntheticBindingPropertyToCreate) {
            addProperty(ktClass = ktClass, content = property)
        }

        for (property in bindingPropertyToCreate) {
            addProperty(ktClass = ktClass, content = property)
        }
    }

    private fun mapLayoutNameToViewBindingProperty(
        layoutName: String,
        isThereMultibindingInClass: Boolean,
        rootTag: String,
        androidViewInLayout: List<AndroidView>,
    ) {
        val bindingName = layoutNameAsBinding(layoutName)
        mutableBindingsToImport += VIEW_BINDING_IMPORT_TEMPLATE.format(packageName, bindingName)
        val propertyName = when {
            isThereMultibindingInClass -> bindingName.replaceFirstChar { it.lowercase() }
            else -> DEFAULT_PROPERTY_NAME
        }
        for (view in androidViewInLayout) {
            val propertySuffix = if (view.isIncludeTag) ".root" else ""
            // binding.viewId or binding.viewId.root
            syntheticBindingPropertyToCreate += SYNTHETIC_BINDING_AS_LAZY_TEMPLATE.format(
                view.viewId,
                "$propertyName.${view.viewId}$propertySuffix",
            )
        }
        val (bindingFunction, bindingType) = process(
            bindingName = bindingName,
            propertyName = propertyName,
            rootTag = rootTag,
        )
        bindingPropertyToCreate += provideTemplate(
            propertyName = propertyName,
            viewBindingName = bindingName,
            bindingFunction = bindingFunction,
            bindingType = bindingType,
        )
        mutableBindingsToImport += BINDING_FUNCTION_IMPORT_TEMPLATE.format(bindingFunction.value)
    }

    private fun removeExistingViewInflate(body: KtClassBody?, layoutName: String) {
        val declarations = body?.declarations ?: return
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

    private fun addProperty(ktClass: KtClass, content: String) {
        val psiFactory = KtPsiFactory(ktClass.project)
        val property = psiFactory.createProperty(content)
        val body = ktClass.body ?: return
        body.addAfter(property, body.lBrace)
    }

    private fun provideTemplate(
        propertyName: String,
        viewBindingName: String,
        bindingFunction: BindingFunction,
        bindingType: BindingType,
    ): String = BINDING_PROPERTY_TEMPLATE.format(
        propertyName, bindingFunction.value, viewBindingName, bindingType.value,
    )

    protected fun layoutNameAsBinding(layoutName: String): String =
        layoutName.split("_").joinToString(
            separator = "",
            postfix = "Binding"
        ) { name ->
            name.replaceFirstChar { it.uppercase() }
        }

    abstract fun process(
        bindingName: String,
        propertyName: String,
        rootTag: String,
    ): Pair<BindingFunction, BindingType>

    companion object {
        private const val DEFAULT_PROPERTY_NAME = "binding"
        private const val BINDING_FUNCTION_IMPORT_TEMPLATE = "co.stone.cactus.utils.ktx.%s"
        private const val BINDING_PROPERTY_TEMPLATE = "private val %s by %s(%s::%s)"
        private const val SYNTHETIC_BINDING_AS_LAZY_TEMPLATE = "private val %s by lazy { %s }"
        private const val VIEW_BINDING_IMPORT_TEMPLATE = "%s.databinding.%s"
    }
}