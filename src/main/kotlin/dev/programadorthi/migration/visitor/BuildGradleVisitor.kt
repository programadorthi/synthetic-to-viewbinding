package dev.programadorthi.migration.visitor

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import dev.programadorthi.migration.model.BuildGradleItem
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyFileBase
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression

class BuildGradleVisitor : PsiElementVisitor() {
    private val mutableBuildGradleItems = mutableListOf<BuildGradleItem>()

    val buildGradleItems: List<BuildGradleItem>
        get() = mutableBuildGradleItems

    /**
     * build.gradle or build.gradle.kts file
     */
    override fun visitFile(file: PsiFile) {
        super.visitFile(file)
        when (file) {
            is KtFile -> visitKtFile(file)
            is GroovyFile -> visitGroovyFile(file)
        }
    }

    /**
     * build.gradle file
     */
    private fun visitGroovyFile(file: GroovyFileBase) {
        for (child in file.children) {
            if (child is GrMethodCallExpression) {
                visitMethodCallExpression(child)
            }
        }
    }

    /**
     * build.gradle.kts file
     */
    private fun visitKtFile(file: KtFile) {
        file.children
            .filterIsInstance<KtScript>()
            .forEach(::visitScript)
    }

    /**
     * Each top-level configuration block as:
     *
     * plugins {}
     * android {}
     * dependencies {}
     * androidExtensions {}
     */
    private fun visitMethodCallExpression(methodCallExpression: GrMethodCallExpression) {
        val expression = methodCallExpression.children.firstIsInstanceOrNull<GrReferenceExpression>() ?: return
        checkExpression(methodCallExpression, expression)
    }

    /**
     * .kts body without packages and imports
     */
    private fun visitScript(script: KtScript) {
        script.blockExpression.children
            .filterIsInstance<KtScriptInitializer>()
            .forEach(::visitScriptInitializer)
    }

    /**
     * Each top-level configuration block as:
     *
     * plugins {}
     * android {}
     * dependencies {}
     * androidExtensions {}
     */
    private fun visitScriptInitializer(initializer: KtScriptInitializer) {
        val parent = initializer.children.firstIsInstanceOrNull<KtCallExpression>() ?: return
        val expression = parent.referenceExpression()
        checkExpression(parent, expression)
    }

    private fun checkExpression(element: PsiElement, expression: PsiElement?) {
        val content = expression?.text ?: return
        when (content) {
            ANDROID_EXTENSIONS_SECTION -> {
                mutableBuildGradleItems += BuildGradleItem.ToDelete(element = element)
            }

            CONFIGURE_SECTION -> lookupForAndroidExtensionType(element)
            else -> lookupFor(element, content)
        }
    }

    private fun lookupForAndroidExtensionType(element: PsiElement) {
        val content = element.text ?: return
        if (content.contains("AndroidExtensionsExtension")) {
            mutableBuildGradleItems += BuildGradleItem.ToDelete(element = element)
        }
    }

    private fun lookupFor(element: PsiElement, content: String) {
        val lookupFunction: (PsiElement?) -> Unit = when (content) {
            ANDROID_SECTION -> ::lookupForViewBindingSetup
            PLUGINS_SECTION -> ::lookupForPlugins
            else -> return
        }
        if (element is GrMethodCallExpression) {
            for (argument in element.closureArguments) {
                lookupFunction(argument)
            }
            return
        }
        if (element is KtCallExpression) {
            for (argument in element.lambdaArguments) {
                val body = argument.getLambdaExpression()?.functionLiteral?.bodyBlockExpression
                lookupFunction(body)
            }
        }
    }

    private fun lookupForViewBindingSetup(body: PsiElement?) {
        val children = body?.children ?: return
        for (child in children) {
            if (child.text.contains("viewBinding")) return
        }
        val anchor = when (body) {
            is GrClosableBlock -> body.lBrace
            is KtBlockExpression -> body.lBrace
            else -> null
        }
        if (anchor != null) {
            mutableBuildGradleItems += BuildGradleItem.ToAdd(
                anchor = anchor,
                expression = "viewBinding.enable = true",
            )
        }
    }

    private fun lookupForPlugins(body: PsiElement?) {
        val children = body?.children ?: return
        for (child in children) {
            if (child.text.contains(androidExtensionsRegex)) {
                mutableBuildGradleItems += BuildGradleItem.ToDelete(element = child)
            }
        }
    }

    companion object {
        private const val ANDROID_SECTION = "android"
        private const val ANDROID_EXTENSIONS_SECTION = "androidExtensions"
        private const val CONFIGURE_SECTION = "configure"
        const val PLUGINS_SECTION = "plugins"

        private val androidExtensionsRegex = """android[.-]extensions""".toRegex()
    }
}