package com.github.libriaforge.tsbarrels

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

class GenerateBarrelsAction : AnAction() {

    companion object {
        private const val PACKAGE_NAME = "@libria/ts-barrels"
        private const val BINARY_NAME = "lb-ts-barrels"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        // Get selected folder from context, or fall back to project root
        val selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val targetFolder = getTargetFolder(selectedFile, basePath)

        val nodeInterpreter = getNodeInterpreter(project)
        if (nodeInterpreter == null) {
            Messages.showErrorDialog(
                project,
                "Node.js interpreter is not configured.\n\nPlease configure it in Settings → Languages & Frameworks → Node.js.",
                "Node.js Not Found"
            )
            return
        }

        val tsBarrelsBin = tsBarrelsBinary(basePath)

        if (!File(tsBarrelsBin).exists()) {
            val answer = Messages.showYesNoDialog(
                project,
                "$PACKAGE_NAME is not installed in this project.\n\nInstall it as a devDependency?",
                "ts-barrels not found",
                Messages.getQuestionIcon()
            )

            if (answer != Messages.YES) return

            installTsBarrels(project, basePath, nodeInterpreter) {
                showOptionsAndRun(project, basePath, targetFolder, nodeInterpreter)
            }
        } else {
            showOptionsAndRun(project, basePath, targetFolder, nodeInterpreter)
        }
    }

    private fun showOptionsAndRun(project: Project, basePath: String, targetFolder: String, node: NodeJsLocalInterpreter) {
        val dialog = TsBarrelsOptionsDialog(project, targetFolder)
        if (dialog.showAndGet()) {
            runTsBarrels(project, basePath, targetFolder, node, dialog.options)
        }
    }

    private fun getTargetFolder(selectedFile: VirtualFile?, basePath: String): String {
        if (selectedFile == null) return basePath
        return if (selectedFile.isDirectory) {
            selectedFile.path
        } else {
            selectedFile.parent?.path ?: basePath
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun getNodeInterpreter(project: Project): NodeJsLocalInterpreter? {
        val interpreterRef = NodeJsInterpreterManager.getInstance(project).interpreterRef
        val interpreter = interpreterRef.resolve(project)
        return interpreter as? NodeJsLocalInterpreter
    }

    private fun runTsBarrels(project: Project, basePath: String, targetFolder: String, node: NodeJsLocalInterpreter, options: TsBarrelsOptions) {
        val scriptPath = tsBarrelsScript(basePath)

        if (!File(scriptPath).exists()) {
            Messages.showErrorDialog(
                project,
                "Could not find $PACKAGE_NAME script at:\n$scriptPath",
                "Script Not Found"
            )
            return
        }

        // Build command arguments
        val args = mutableListOf(
            node.interpreterSystemDependentPath,
            scriptPath,
            targetFolder
        )
        if (options.all) args.add("--all")
        if (options.force) args.add("--force")
        if (options.filename.isNotBlank() && options.filename != "index.ts") {
            args.add("--name")
            args.add(options.filename)
        }

        val commandLine = GeneralCommandLine(args)
            .withWorkDirectory(basePath)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        try {
            val output = StringBuilder()
            val handler = OSProcessHandler(commandLine)

            handler.addProcessListener(object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {}
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        if (event.exitCode == 0) {
                            showNotification(
                                project,
                                "Barrel files generated successfully in:\n$targetFolder",
                                NotificationType.INFORMATION
                            )
                        } else {
                            showNotification(
                                project,
                                "Failed to generate barrels (exit code ${event.exitCode}):\n${output.toString().take(500)}",
                                NotificationType.ERROR
                            )
                        }
                    }
                }
            })

            handler.startNotify()
            showNotification(project, "Generating barrel files...", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(
                    project,
                    "Failed to run $PACKAGE_NAME: ${ex.message}",
                    "Execution Error"
                )
            }
        }
    }

    private fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("TsBarrels")
            .createNotification(content, type)
            .notify(project)
    }

    private fun installTsBarrels(
        project: Project,
        basePath: String,
        node: NodeJsLocalInterpreter,
        onSuccess: () -> Unit
    ) {
        val packageManager = detectPackageManager(basePath)
        val nodeDir = File(node.interpreterSystemDependentPath).parentFile

        // Get the package manager executable path (npm/yarn/pnpm should be in same dir as node)
        val pmExecutable = when (packageManager) {
            PackageManager.YARN -> if (SystemInfo.isWindows) "yarn.cmd" else "yarn"
            PackageManager.PNPM -> if (SystemInfo.isWindows) "pnpm.cmd" else "pnpm"
            PackageManager.NPM -> if (SystemInfo.isWindows) "npm.cmd" else "npm"
        }

        val pmPath = File(nodeDir, pmExecutable)
        val pmCommand = if (pmPath.exists()) pmPath.absolutePath else pmExecutable

        val installArgs = when (packageManager) {
            PackageManager.YARN -> listOf(pmCommand, "add", "-D", PACKAGE_NAME)
            PackageManager.PNPM -> listOf(pmCommand, "add", "-D", PACKAGE_NAME)
            PackageManager.NPM -> listOf(pmCommand, "install", "-D", PACKAGE_NAME)
        }

        val commandLine = GeneralCommandLine(installArgs)
            .withWorkDirectory(basePath)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        try {
            val handler = OSProcessHandler(commandLine)
            ProcessTerminatedListener.attach(handler)

            handler.addProcessListener(object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {}
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {}
                override fun processTerminated(event: ProcessEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        if (event.exitCode == 0) {
                            onSuccess()
                        } else {
                            Messages.showErrorDialog(
                                project,
                                "Failed to install $PACKAGE_NAME.\nExit code: ${event.exitCode}",
                                "Installation Failed"
                            )
                        }
                    }
                }
            })

            handler.startNotify()
        } catch (ex: Exception) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(
                    project,
                    "Failed to run package manager: ${ex.message}",
                    "Installation Error"
                )
            }
        }
    }

    private fun tsBarrelsBinary(basePath: String): String {
        val binDir = File(basePath, "node_modules/.bin")
        return if (SystemInfo.isWindows) {
            File(binDir, "$BINARY_NAME.cmd").absolutePath
        } else {
            File(binDir, BINARY_NAME).absolutePath
        }
    }

    private fun tsBarrelsScript(basePath: String): String {
        // The actual JS entry point for the package
        return File(basePath, "node_modules/$PACKAGE_NAME/dist/cli.mjs").absolutePath
    }

    private fun detectPackageManager(basePath: String): PackageManager =
        when {
            File(basePath, "pnpm-lock.yaml").exists() -> PackageManager.PNPM
            File(basePath, "yarn.lock").exists() -> PackageManager.YARN
            else -> PackageManager.NPM
        }

    private enum class PackageManager {
        NPM, YARN, PNPM
    }
}