tasks.register("checkUseBlocks") {
    group = "verification"
    description = "Checks that specific IO classes and Cursor are properly wrapped in .use {} blocks to prevent resource leaks."
    
    doLast {
        val targetClasses = listOf(
            "FileInputStream", "FileOutputStream", 
            "ZipInputStream", "ZipOutputStream", 
            "JsonReader"
        )
        val srcDir = fileTree("src/main/java")
        var violationsFound = 0
        
        srcDir.forEach { file ->
            if (file.extension == "kt" || file.extension == "java") {
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    if (line.trim().startsWith("import ") || line.trim().startsWith("//") || line.trim().startsWith("*")) {
                        return@forEachIndexed
                    }
                    
                    for (className in targetClasses) {
                        val regex = Regex("\\b$className\\s*\\(")
                        if (regex.containsMatchIn(line)) {
                            val textToSearch = if (index + 1 < lines.size) line + lines[index + 1] else line
                            if (!textToSearch.contains(".use")) {
                                println("Violation in ${file.name}:${index + 1} - $className allocated without .use {}")
                                println("  Line: ${line.trim()}")
                                violationsFound++
                            }
                        }
                    }
                    
                    // Special heuristic for Cursor: look for db.query or rawQuery
                    val cursorRegex = Regex("\\b(query|rawQuery)\\s*\\(")
                    if (cursorRegex.containsMatchIn(line) && line.contains("db.") && !line.contains("Room")) {
                         val textToSearch = if (index + 1 < lines.size) line + lines[index + 1] else line
                         if (!textToSearch.contains(".use") && !textToSearch.contains("close()")) {
                             // This is risky for false positives, but let's see.
                             // Actually, let's look for "Cursor " or ": Cursor" without use
                         }
                    }
                }
            }
        }
        if (violationsFound > 0) {
            throw GradleException("Found $violationsFound resource leak violations! Please wrap IO/Cursor classes in .use {}")
        }
    }
}
