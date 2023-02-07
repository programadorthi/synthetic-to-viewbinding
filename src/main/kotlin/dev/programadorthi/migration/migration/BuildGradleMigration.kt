package dev.programadorthi.migration.migration

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.programadorthi.migration.model.BuildGradleItem
import dev.programadorthi.migration.model.MigrationStatus
import dev.programadorthi.migration.notification.MigrationNotification
import dev.programadorthi.migration.visitor.BuildGradleVisitor
import org.jetbrains.kotlin.idea.configuration.externalProjectPath
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory
import java.nio.file.Paths

internal object BuildGradleMigration {
    const val BUILD_GRADLE_FILE_NAME = "build.gradle"
    const val BUILD_GRADLE_KTS_FILE_NAME = "build.gradle.kts"

    private val log = Logger.getInstance(BuildGradleMigration::class.java)

    fun migrateScript(psiFile: PsiFile, buildGradleStatusProvider: BuildGradleStatusProvider) {
        val modulePath = psiFile.module?.externalProjectPath ?: return
        if (buildGradleStatusProvider.currentBuildGradleStatus(modulePath) != MigrationStatus.NOT_STARTED) return

        runCatching {
            buildGradleStatusProvider.updateBuildGradleStatus(modulePath, MigrationStatus.IN_PROGRESS)

            if ((psiFile is KtFile && psiFile.isScript() && psiFile.name == BUILD_GRADLE_KTS_FILE_NAME) ||
                (psiFile is GroovyFile && psiFile.name == BUILD_GRADLE_FILE_NAME)
            ) {
                migrateScript(psiFile)
            } else {
                var virtualFile = VfsUtil.findFile(Paths.get(modulePath, BUILD_GRADLE_KTS_FILE_NAME), false)
                if (virtualFile?.exists() != true) {
                    virtualFile = VfsUtil.findFile(Paths.get(modulePath, BUILD_GRADLE_FILE_NAME), false)
                }

                if (virtualFile?.exists() == true) {
                    migrateScript(PsiManager.getInstance(psiFile.project).findFile(virtualFile))
                }
            }
        }.onFailure {
            log.error("Failed migrate gradle file android extensions setups", it)
            buildGradleStatusProvider.updateBuildGradleStatus(modulePath, MigrationStatus.NOT_STARTED)
        }.onSuccess {
            buildGradleStatusProvider.updateBuildGradleStatus(modulePath, MigrationStatus.DONE)
            MigrationNotification.showInfo("${psiFile.name} migration successfully!")
        }
    }

    private fun migrateScript(psiFile: PsiFile?) {
        val file = psiFile ?: return
        val visitor = BuildGradleVisitor()
        file.accept(visitor)
        for (item in visitor.buildGradleItems) {
            if (item is BuildGradleItem.ToDelete) {
                item.element.delete()
            } else if (item is BuildGradleItem.ToAdd) {
                item.anchor.run {
                    parent.addAfter(createExpression(file, item.expression), this)
                    parent.addAfter(createWhiteSpace(file), this)
                }
            }
        }
    }

    private fun createWhiteSpace(psiFile: PsiFile): PsiElement {
        return when (psiFile) {
            is KtFile -> KtPsiFactory(psiFile.project).createWhiteSpace("\n")
            else -> GroovyPsiElementFactory.getInstance(psiFile.project).createLineTerminator(0)
        }
    }

    private fun createExpression(psiFile: PsiFile, expression: String): PsiElement {
        return when (psiFile) {
            is KtFile -> KtPsiFactory(psiFile.project).createExpression(expression)
            else -> GroovyPsiElementFactory.getInstance(psiFile.project).createExpressionFromText(expression)
        }
    }
}