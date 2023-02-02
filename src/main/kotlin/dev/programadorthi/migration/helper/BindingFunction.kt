package dev.programadorthi.migration.helper

enum class BindingFunction(val value: String) {
    AS_CHILD("viewBindingAsChild"),
    AS_MERGE("viewBindingMergeTag"),
    DEFAULT("viewBinding"),
}