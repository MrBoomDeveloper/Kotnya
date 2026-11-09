package com.mrboomdev.kotnya.compiler

import java.io.File

enum class ProjectTemplate {
    /** A `jvm/app` console project that can be built and run entirely on-device. */
    JvmConsole,

    /** An `android/app` project. Building it requires an Android SDK in the Termux prefix. */
    AndroidApp,
}

data class NewProject(
    val directory: File,
    val name: String,
    val packageName: String,
    val template: ProjectTemplate = ProjectTemplate.JvmConsole,
    val compileSdk: Int = 37,
    val targetSdk: Int = 28,
    val minSdk: Int = 26,
)

/**
 * Generates Kotlin Toolchain projects: a `project.yaml`, one `module.yaml` and the source files.
 */
class KotlinProjectGenerator {

    fun generate(project: NewProject): File {
        require(NAME.matches(project.name)) {
            "Invalid project name '${project.name}': use letters, digits, '-' and '_', starting with a letter"
        }
        require(PACKAGE.matches(project.packageName)) {
            "Invalid package name '${project.packageName}'"
        }
        require(!project.directory.exists() || project.directory.list()?.isEmpty() == true) {
            "Directory ${project.directory.absolutePath} is not empty"
        }

        project.directory.mkdirs()

        when (project.template) {
            ProjectTemplate.JvmConsole -> generateJvmConsole(project)
            ProjectTemplate.AndroidApp -> generateAndroidApp(project)
        }

        return project.directory
    }

    private fun generateJvmConsole(project: NewProject) {
        project.directory.write(
            "project.yaml",
            """
            modules:
              - app
            """.trimIndent() + "\n",
        )

        project.directory.write(
            "app/module.yaml",
            """
            product: jvm/app
            """.trimIndent() + "\n",
        )

        project.directory.write(
            "app/src/Main.kt",
            """
            package ${project.packageName}

            fun main() {
                println("Hello from ${project.name}!")
            }
            """.trimIndent() + "\n",
        )
    }

    private fun generateAndroidApp(project: NewProject) {
        project.directory.write(
            "project.yaml",
            """
            modules:
              - app
            """.trimIndent() + "\n",
        )

        project.directory.write(
            "app/module.yaml",
            """
            product: android/app

            settings:
              android:
                namespace: ${project.packageName}
                applicationId: ${project.packageName}
                versionCode: 1
                versionName: "1.0.0"
                minSdk: ${project.minSdk}
                compileSdk: ${project.compileSdk}
                targetSdk: ${project.targetSdk}
            """.trimIndent() + "\n",
        )

        project.directory.write(
            "app/src/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                <application
                    android:allowBackup="true"
                    android:label="${project.name}"
                    android:supportsRtl="true"
                    android:theme="@android:style/Theme.Material.Light.NoActionBar">

                    <activity
                        android:exported="true"
                        android:name=".MainActivity">

                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent() + "\n",
        )

        project.directory.write(
            "app/src/MainActivity.kt",
            """
            package ${project.packageName}

            import android.app.Activity
            import android.os.Bundle
            import android.widget.TextView

            class MainActivity : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(TextView(this).apply { text = "Hello from ${project.name}!" })
                }
            }
            """.trimIndent() + "\n",
        )
    }

    private fun File.write(relativePath: String, content: String) {
        val file = File(this, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private companion object {
        val NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")
        val PACKAGE = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")
    }
}
