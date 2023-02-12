package dev.programadorthi.migration

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.codeInsight.actions.CodeCleanupCodeProcessor
import com.intellij.codeInsight.actions.DirectoryFormattingOptions
import com.intellij.codeInsight.actions.LayoutDirectoryDialog
import com.intellij.codeInsight.actions.LayoutProjectCodeDialog
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.RearrangeCodeProcessor
import com.intellij.codeInsight.actions.ReformatFilesOptions
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Conditions
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDirectoryContainer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.SearchScope
import com.intellij.util.ArrayUtil
import dev.programadorthi.migration.notification.MigrationNotification
import dev.programadorthi.migration.processor.MigrationProcessor
import java.util.regex.PatternSyntaxException

class MigrationAction : AnAction(), DumbAware, LightEditCompatible {
    private val log = Logger.getInstance(MigrationAction::class.java)

    init {
        isEnabledInModalContext = true
        setInjectedContext(true)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val dataContext = event.dataContext
        val project = requireNotNull(CommonDataKeys.PROJECT.getData(dataContext)) {
            "No project found to do migration"
        }
        MigrationNotification.setProject(project)
        val psiElement = CommonDataKeys.PSI_ELEMENT.getData(dataContext)
        if (psiElement == null) {
            MigrationNotification.showError("Selecting a module or file is required to do a migration")
            return
        }
        val moduleContext = LangDataKeys.MODULE_CONTEXT.getData(dataContext)
        if (moduleContext != null) {
            tryModuleMigration(project = project, moduleContext = moduleContext)
        } else {
            val dir = when (psiElement) {
                is PsiDirectoryContainer -> ArrayUtil.getFirstElement(psiElement.directories)
                is PsiDirectory -> psiElement
                else -> psiElement.containingFile?.containingDirectory
            }
            if (dir == null) {
                MigrationNotification.showError("No directory selected to do migration")
                return
            }
            tryDirectoryMigration(project = project, dir = dir)
        }
    }

    private fun tryModuleMigration(project: Project, moduleContext: Module?) {
        val selectedFlags = getLayoutModuleOptions(project, moduleContext) ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val processor: AbstractLayoutCodeProcessor = if (moduleContext != null) {
            MigrationProcessor(project, moduleContext)
        } else {
            MigrationProcessor(project)
        }

        registerAndRunProcessor(
            project = project,
            initialProcessor = processor,
            selectedFlags = selectedFlags,
        )
    }

    private fun tryDirectoryMigration(project: Project, dir: PsiDirectory) {
        val selectedFlags = getDirectoryFormattingOptions(project, dir) ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val processor: AbstractLayoutCodeProcessor = MigrationProcessor(
            project,
            dir,
            selectedFlags.isIncludeSubdirectories,
        )

        registerAndRunProcessor(
            project = project,
            initialProcessor = processor,
            selectedFlags = selectedFlags,
        )
    }

    private fun registerAndRunProcessor(
        project: Project,
        initialProcessor: AbstractLayoutCodeProcessor,
        selectedFlags: ReformatFilesOptions,
    ) {
        val shouldOptimizeImports = selectedFlags.isOptimizeImports && !DumbService.getInstance(project).isDumb
        var processor = initialProcessor

        registerScopeFilter(processor, selectedFlags.searchScope)
        registerFileMaskFilter(processor, selectedFlags.fileTypeMask)

        if (shouldOptimizeImports) {
            processor = OptimizeImportsProcessor(processor)
        }

        if (selectedFlags.isRearrangeCode) {
            processor = RearrangeCodeProcessor(processor)
        }

        if (selectedFlags.isCodeCleanup) {
            processor = CodeCleanupCodeProcessor(processor)
        }

        processor.run()
    }

    private fun registerFileMaskFilter(processor: AbstractLayoutCodeProcessor, fileTypeMask: String?) {
        if (fileTypeMask == null) return
        val patternCondition = try {
            FindInProjectUtil.createFileMaskCondition(fileTypeMask)
        } catch (ex: PatternSyntaxException) {
            log.error("Error while creating file mask condition: ", ex)
            Conditions.alwaysTrue()
        }
        processor.addFileFilter { file ->
            patternCondition.value(file.nameSequence)
        }
    }

    private fun registerScopeFilter(processor: AbstractLayoutCodeProcessor, scope: SearchScope?) {
        if (scope == null) return
        processor.addFileFilter(scope::contains)
    }

    private fun getLayoutModuleOptions(project: Project, moduleContext: Module?): ReformatFilesOptions? {
        if (ApplicationManager.getApplication().isUnitTestMode) {
            return null
        }
        val text = if (moduleContext != null) {
            CodeInsightBundle.message("process.scope.module", moduleContext.moduleFilePath)
        } else {
            CodeInsightBundle.message("process.scope.project", project.presentableUrl)
        }
        val dialog = LayoutProjectCodeDialog(
            project,
            MigrationProcessor.getCommandName(),
            text,
            false,
        )
        if (dialog.showAndGet()) {
            return dialog
        }
        return null
    }

    private fun getDirectoryFormattingOptions(project: Project, dir: PsiDirectory): DirectoryFormattingOptions? {
        val dialog = LayoutDirectoryDialog(
            project,
            MigrationProcessor.getCommandName(),
            CodeInsightBundle.message("process.scope.directory", dir.virtualFile.path),
            false,
        )
        val enableIncludeDirectoriesCb = dir.subdirectories.isNotEmpty()
        dialog.setEnabledIncludeSubdirsCb(enableIncludeDirectoriesCb)
        dialog.setSelectedIncludeSubdirsCb(enableIncludeDirectoriesCb)
        if (dialog.showAndGet()) {
            return dialog
        }
        return null
    }
}