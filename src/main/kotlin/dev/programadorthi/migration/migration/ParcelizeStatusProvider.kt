package dev.programadorthi.migration.migration

import dev.programadorthi.migration.model.MigrationStatus

internal interface ParcelizeStatusProvider {
    fun currentParcelizeStatus(path: String): MigrationStatus

    fun updateParcelizeStatus(path: String, status: MigrationStatus)
}