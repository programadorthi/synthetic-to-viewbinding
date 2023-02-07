package dev.programadorthi.migration.visitor

import dev.programadorthi.migration.model.BuildGradleItem
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

class KotlinBuildGradleVisitor : KtVisitorVoid() {
    private val mutableBuildGradleItems = mutableListOf<BuildGradleItem>()

    val buildGradleItems: List<BuildGradleItem>
        get() = mutableBuildGradleItems

    /**
     * File with .kts extension
     */
    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        for (child in file.children) {
            child.accept(this)
        }
    }

    /**
     * .kts body without packages and imports
     */
    override fun visitScript(script: KtScript) {
        super.visitScript(script)
        for (child in script.blockExpression.children) {
            child.accept(this)
        }
    }

    /**
     * Each top-level configuration block as:
     *
     * plugins {}
     * android {}
     * dependencies {}
     * androidExtensions {}
     */
    override fun visitScriptInitializer(initializer: KtScriptInitializer) {
        super.visitScriptInitializer(initializer)
        checkInitializer(initializer)
    }

    private fun checkInitializer(initializer: KtScriptInitializer) {
        val callExpression = initializer.children.firstIsInstanceOrNull<KtCallExpression>() ?: return
        val referenceExpression = callExpression.referenceExpression() ?: return
        when (referenceExpression.text) {
            ANDROID_SECTION -> lookupForViewBindingSetup(callExpression.lambdaArguments)
            ANDROID_EXTENSIONS_SECTION -> {
                mutableBuildGradleItems += BuildGradleItem.ToDelete(element = initializer)
            }

            PLUGINS_SECTION -> lookupForPlugins(callExpression.lambdaArguments)
            CONFIGURE_SECTION -> lookupForAndroidExtensionType(callExpression)
        }
    }

    private fun lookupForPlugins(lambdaArguments: List<KtLambdaArgument>) {
        val functionLiteral = lambdaArguments.firstOrNull()
            ?.getLambdaExpression()
            ?.functionLiteral ?: return
        val body = functionLiteral.bodyBlockExpression ?: return
        val callExpressions = body.children.filterIsInstance<KtCallExpression>()
        for (call in callExpressions) {
            val content = call.text
            if (content.contains(idPluginRegex) || content.contains(kotlinPluginRegex)) {
                mutableBuildGradleItems += BuildGradleItem.ToDelete(element = call)
            }
        }
    }

    private fun lookupForViewBindingSetup(lambdaArguments: List<KtLambdaArgument>) {
        val functionLiteral = lambdaArguments.firstOrNull()
            ?.getLambdaExpression()
            ?.functionLiteral ?: return
        val body = functionLiteral.bodyBlockExpression ?: return
        for (child in body.children) {
            if (child is KtBinaryExpression && child.right is KtConstantExpression) {
                val left = child.left?.text ?: continue
                if (left.startsWith("viewBinding")) return
            }
        }
        mutableBuildGradleItems += BuildGradleItem.ToAdd(
            anchor = functionLiteral.lBrace,
            expression = "viewBinding.enable = true",
        )
    }

    private fun lookupForAndroidExtensionType(callExpression: KtCallExpression) {
        val content = callExpression.typeArgumentList?.text ?: return
        if (content.contains("AndroidExtensionsExtension")) {
            mutableBuildGradleItems += BuildGradleItem.ToDelete(element = callExpression)
        }
    }

    private companion object {
        private const val ANDROID_SECTION = "android"
        private const val ANDROID_EXTENSIONS_SECTION = "androidExtensions"
        private const val CONFIGURE_SECTION = "configure"
        private const val PLUGINS_SECTION = "plugins"

        private val idPluginRegex = """^\s*id\("org\.jetbrains\.kotlin\.android\.extensions"\)""".toRegex()
        private val kotlinPluginRegex = """^\s*kotlin\("android.extensions"\)""".toRegex()
    }
}