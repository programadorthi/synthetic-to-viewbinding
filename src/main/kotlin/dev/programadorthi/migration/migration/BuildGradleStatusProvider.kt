package dev.programadorthi.migration.migration

import dev.programadorthi.migration.model.MigrationStatus

internal interface BuildGradleStatusProvider {
    fun currentBuildGradleStatus(path: String): MigrationStatus

    fun updateBuildGradleStatus(path: String, status: MigrationStatus)
}