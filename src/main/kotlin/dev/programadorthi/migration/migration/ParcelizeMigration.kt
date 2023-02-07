package dev.programadorthi.migration.migration

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.programadorthi.migration.model.MigrationStatus
import dev.programadorthi.migration.visitor.BuildGradleVisitor
import org.jetbrains.kotlin.idea.configuration.externalProjectPath
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyFileBase
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrApplicationStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression
import java.nio.file.Paths

internal object ParcelizeMigration {
    private const val PLUGIN_NAME = "kotlin-parcelize"
    private const val GROOVY_PARCELIZE_PLUGIN = """id '$PLUGIN_NAME'"""
    private const val KOTLIN_PARCELIZE_PLUGIN = """id("$PLUGIN_NAME")"""

    private val log = Logger.getInstance(ParcelizeMigration::class.java)

    fun migrate(ktFile: KtFile, parcelizeStatusProvider: ParcelizeStatusProvider) {
        val modulePath = ktFile.module?.externalProjectPath ?: return
        if (parcelizeStatusProvider.currentParcelizeStatus(modulePath) != MigrationStatus.NOT_STARTED) return

        runCatching {
            parcelizeStatusProvider.updateParcelizeStatus(modulePath, MigrationStatus.IN_PROGRESS)

            var virtualFile =
                VfsUtil.findFile(Paths.get(modulePath, BuildGradleMigration.BUILD_GRADLE_KTS_FILE_NAME), false)
            if (virtualFile?.exists() != true) {
                virtualFile =
                    VfsUtil.findFile(Paths.get(modulePath, BuildGradleMigration.BUILD_GRADLE_FILE_NAME), false)
            }

            if (virtualFile?.exists() == true) {
                checkOrAddPluginTo(PsiManager.getInstance(ktFile.project).findFile(virtualFile))
            }
        }.onFailure {
            log.error("Failed migrate gradle file parcelize setup", it)
            parcelizeStatusProvider.updateParcelizeStatus(modulePath, MigrationStatus.NOT_STARTED)
        }.onSuccess {
            parcelizeStatusProvider.updateParcelizeStatus(modulePath, MigrationStatus.DONE)
        }
    }

    private fun checkOrAddPluginTo(psiFile: PsiFile?) {
        if (psiFile is KtFile) {
            visitKtFile(psiFile)
        } else if (psiFile is GroovyFile) {
            visitGroovyFile(psiFile)
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
        if (expression.text == BuildGradleVisitor.PLUGINS_SECTION) {
            val closableBlock = methodCallExpression.children.firstIsInstanceOrNull<GrClosableBlock>() ?: return
            addParcelizePlugin(closableBlock, methodCallExpression.project)
        }
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
        if (expression?.text == BuildGradleVisitor.PLUGINS_SECTION) {
            addParcelizePlugin(parent, initializer.project)
        }
    }

    private fun addParcelizePlugin(parent: KtCallExpression, project: Project) {
        val blockExpression = parent
            .lambdaArguments
            .firstOrNull()
            ?.getLambdaExpression()
            ?.functionLiteral
            ?.bodyBlockExpression
        val children = blockExpression?.children ?: return
        for (child in children) {
            if (child.text.contains(PLUGIN_NAME)) return
        }
        val psiFactory = KtPsiFactory(project)
        val whitespace = psiFactory.createWhiteSpace("\n")
        val callExpression = psiFactory.createExpression(KOTLIN_PARCELIZE_PLUGIN)
        blockExpression.addBefore(whitespace, blockExpression.rBrace)
        blockExpression.addBefore(callExpression, blockExpression.rBrace)
    }

    private fun addParcelizePlugin(grClosableBlock: GrClosableBlock, project: Project) {
        var anchor: GrApplicationStatement? = null
        for (child in grClosableBlock.children) {
            if (child.text.contains(PLUGIN_NAME)) return
            if (child is GrApplicationStatement) {
                anchor = child
            }
        }
        if (anchor != null) {
            val callExpression = GroovyPsiElementFactory
                .getInstance(project)
                .createExpressionFromText(GROOVY_PARCELIZE_PLUGIN)
            grClosableBlock.addAfter(callExpression, anchor)
        }
    }
}