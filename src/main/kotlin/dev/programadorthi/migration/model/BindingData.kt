package dev.programadorthi.migration.model

import android.databinding.tool.writer.BaseLayoutModel
import android.databinding.tool.writer.ViewBinder
import org.jetbrains.kotlin.android.synthetic.res.AndroidResource

data class BindingData(
    val layoutName: String,
    val resources: List<AndroidResource>,
    val baseLayoutModel: BaseLayoutModel,
    val rootNode: ViewBinder.RootNode,
)
