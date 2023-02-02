package dev.programadorthi.migration.processor

import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ex.util.EditorScrollingPositionKeeper
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import com.intellij.util.SlowOperations
import dev.programadorthi.migration.helper.CheckAndMigrateFile
import dev.programadorthi.migration.notification.MigrationNotification
import java.util.concurrent.FutureTask

class MigrationProcessor : AbstractLayoutCodeProcessor {
    private val log = Logger.getInstance(MigrationProcessor::class.java)
    private val packageName: String

    constructor(
        project: Project,
        packageName: String,
    ) : super(
        project,
        getCommandName(),
        getProgressText(),
        false,
    ) {
        this.packageName = packageName
    }

    constructor(
        project: Project,
        module: Module,
        packageName: String,
    ) : super(
        project,
        module,
        getCommandName(),
        getProgressText(),
        false,
    ) {
        this.packageName = packageName
    }

    constructor(
        project: Project,
        directory: PsiDirectory,
        includeSubdirs: Boolean,
        packageName: String,
    ) : super(
        project,
        directory,
        includeSubdirs,
        getProgressText(),
        getCommandName(),
        false,
    ) {
        this.packageName = packageName
    }

    override fun prepareTask(file: PsiFile, processChangedTextOnly: Boolean): FutureTask<Boolean> {
        val fileToProcess = ReadAction.compute<PsiFile?, IncorrectOperationException> {
            ensureValid(file)
        } ?: return FutureTask { false }
        return FutureTask {
            doMigration(fileToProcess)
        }
    }

    private fun doMigration(file: PsiFile): Boolean {
        try {
            val document = PsiDocumentManager.getInstance(myProject).getDocument(file)
            log.assertTrue(infoCollector == null || document != null)
            val before = document?.immutableCharSequence
            try {
                EditorScrollingPositionKeeper.perform(document, true) {
                    SlowOperations.allowSlowOperations<Nothing> {
                        if (document != null) {
                            // In languages that are supported by a non-commit typing assistant (such as C++ and Kotlin),
                            // the `document` here can be in an uncommitted state. In the case of an external formatter,
                            // this may be the cause of formatting artifacts
                            PsiDocumentManager.getInstance(myProject).commitDocument(document)
                        }
                        CheckAndMigrateFile.migrate(file, packageName)
                    }
                }
            } catch (pce: ProcessCanceledException) {
                log.error(pce)
                if (before != null) {
                    document.setText(before)
                }
                MigrationNotification.showError("Process cancelled")
                return false
            }
            return true
        } catch (ioe: IncorrectOperationException) {
            log.error(ioe)
            MigrationNotification.showError("Operation not supported")
        }
        return false
    }

    private fun ensureValid(file: PsiFile): PsiFile? {
        if (file.isValid) {
            return file
        }
        val virtualFile = file.virtualFile
        if (virtualFile.isValid) {
            return null
        }
        val provider = file.manager.findViewProvider(virtualFile) ?: return null
        val language = file.language
        return if (provider.hasLanguage(language)) {
            provider.getPsi(language)
        } else {
            provider.getPsi(provider.baseLanguage)
        }
    }

    companion object {
        private fun getProgressText(): String = "Migrating code..."
        fun getCommandName(): String = "Synthetic to ViewBinding"
    }
}