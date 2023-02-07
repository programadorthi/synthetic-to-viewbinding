package dev.programadorthi.migration.migration

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.programadorthi.migration.model.BuildGradleItem
import dev.programadorthi.migration.notification.MigrationNotification
import dev.programadorthi.migration.visitor.KotlinBuildGradleVisitor
import org.jetbrains.kotlin.idea.configuration.externalProjectPath
import org.jetbrains.kotlin.idea.util.module
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Paths

internal object KotlinBuildGradleMigration {
    private const val BUILD_GRADLE_FILE_NAME = "build.gradle.kts"

    fun migrateScript(ktFile: KtFile, buildGradleProvider: BuildGradleProvider) {
        val modulePath = ktFile.module?.externalProjectPath ?: return
        if (buildGradleProvider.hasMigratedAlready(modulePath)) return

        if (ktFile.isScript() && ktFile.name == BUILD_GRADLE_FILE_NAME) {
            migrateScript(ktFile)
            buildGradleProvider.registerModuleMigration(modulePath)
            return
        }

        val path = Paths.get(modulePath, BUILD_GRADLE_FILE_NAME)
        val virtualFile = VfsUtil.findFile(path, false) ?: return
        val buildGradleFile = PsiManager.getInstance(ktFile.project).findFile(virtualFile) ?: return
        migrateScript(buildGradleFile)
        buildGradleProvider.registerModuleMigration(modulePath)
    }

    private fun migrateScript(psiFile: PsiFile) {
        val psiFactory = KtPsiFactory(psiFile.project)
        val visitor = KotlinBuildGradleVisitor()
        psiFile.accept(visitor)
        for (item in visitor.buildGradleItems) {
            if (item is BuildGradleItem.ToDelete) {
                item.element.delete()
            } else if (item is BuildGradleItem.ToAdd) {
                val whitespace = psiFactory.createWhiteSpace("\n")
                val expression = psiFactory.createExpression(item.expression)
                item.anchor.run {
                    parent.addAfter(expression, this)
                    parent.addAfter(whitespace, this)
                }
            }
        }
        MigrationNotification.showInfo("${psiFile.name} migration successfully!")
    }
}