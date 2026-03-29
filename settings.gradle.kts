rootProject.name = "CloudstreamPlugins"

// This will automatically find all folders that have a build.gradle.kts
// and add them as sub-projects
val disabled = listOf<String>()

File(rootDir.absolutePath).eachDir { dir ->
    if (dir.name !in disabled && dir.name != "build" &&
        File(dir, "build.gradle.kts").exists()
    ) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
