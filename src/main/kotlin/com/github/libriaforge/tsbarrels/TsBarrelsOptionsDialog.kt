package com.github.libriaforge.tsbarrels

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

data class TsBarrelsOptions(
    var all: Boolean = false,
    var force: Boolean = false,
    var filename: String = "index.ts"
)

class TsBarrelsOptionsDialog(
    project: Project,
    private val targetFolder: String
) : DialogWrapper(project) {

    val options = TsBarrelsOptions()

    init {
        title = "Generate TypeScript Barrels"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                label("Target folder: $targetFolder")
            }
            separator()
            row {
                checkBox("Recursive (--all)")
                    .bindSelected(options::all)
                    .comment("Generate barrels recursively from leaves to root")
            }
            row {
                checkBox("Force (--force)")
                    .bindSelected(options::force)
                    .comment("Override existing barrel files (ignores skip)")
            }
            row("Filename:") {
                textField()
                    .bindText(options::filename)
                    .comment("Name for barrel files (default: index.ts)")
            }
        }
    }
}
