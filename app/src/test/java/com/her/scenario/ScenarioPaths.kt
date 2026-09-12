package com.her.scenario

import java.io.File

object ScenarioPaths {
    fun repoRoot(): File {
        val hinted = System.getProperty("her.repoRoot")?.trim()?.takeIf { it.isNotEmpty() }
        val starts = listOfNotNull(hinted, System.getProperty("user.dir")).map { File(it).absoluteFile }
        for (start in starts) {
            var dir: File? = start
            repeat(8) {
                val current = dir ?: return@repeat
                if (File(current, "settings.gradle.kts").exists() && File(current, "app").isDirectory) {
                    return current
                }
                dir = current.parentFile
            }
        }
        error("Could not find the Her repo root from user.dir=${System.getProperty("user.dir")}")
    }

    fun scenariosDir(root: File = repoRoot()): File = File(root, "scenarios")

    fun envFile(root: File = repoRoot()): File = File(root, ".env")

    fun reportsDir(root: File = repoRoot()): File = File(root, "build/reports/scenarios")
}
