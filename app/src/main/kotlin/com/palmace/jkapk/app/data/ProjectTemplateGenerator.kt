package com.palmace.jkapk.app.data

import com.palmace.jkapk.app.domain.ProjectLanguage
import com.palmace.jkapk.app.domain.ProjectWorkspace
import java.io.File

class ProjectTemplateGenerator {

    fun createProject(
        parentDir: File,
        projectName: String,
        packageName: String,
        language: ProjectLanguage = ProjectLanguage.KOTLIN,
        minSdk: Int = 24,
        compileSdk: Int = 34,
        javaVersion: Int = 17
    ): ProjectWorkspace {
        val sanitizedFolder = projectName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        val rootDir = File(parentDir, sanitizedFolder)
        if (rootDir.exists()) {
            throw IllegalStateException("Ya existe un proyecto en ${rootDir.absolutePath}")
        }

        val workspace = ProjectWorkspace(
            name = projectName.trim(),
            rootDir = rootDir,
            packageName = packageName.trim(),
            language = language,
            minSdk = minSdk,
            compileSdk = compileSdk,
            javaVersion = javaVersion
        )

        workspace.kotlinDir.mkdirs()
        workspace.javaDir.mkdirs()
        File(workspace.resDir, "values").mkdirs()
        File(workspace.resDir, "layout").mkdirs()
        workspace.assetsDir.mkdirs()
        workspace.libsDir.mkdirs()
        workspace.saveMetadata()

        workspace.manifestFile.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="${workspace.packageName}">

                <application
                    android:label="@string/app_name"
                    android:allowBackup="true">

                    <activity
                        android:name="${workspace.packageName}.MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>

                </application>
            </manifest>
            """.trimIndent() + "\n"
        )

        File(workspace.resDir, "values/strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${escapeXml(workspace.name)}</string>
                <string name="hello_message">Compilado con JKAPK</string>
            </resources>
            """.trimIndent() + "\n"
        )

        File(workspace.resDir, "layout/activity_main.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical"
                android:gravity="center"
                android:padding="24dp">

                <TextView
                    android:id="@+id/tvHello"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/hello_message"
                    android:textSize="20sp" />

            </LinearLayout>
            """.trimIndent() + "\n"
        )

        val packagePath = workspace.packageName.replace('.', '/')
        when (language) {
            ProjectLanguage.KOTLIN -> {
                val ktFile = File(workspace.kotlinDir, "$packagePath/MainActivity.kt")
                ktFile.parentFile?.mkdirs()
                ktFile.writeText(
                    """
                    package ${workspace.packageName}

                    import android.app.Activity
                    import android.os.Bundle
                    import android.widget.TextView

                    class MainActivity : Activity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContentView(R.layout.activity_main)
                            val tvHello = findViewById<TextView>(R.id.tvHello)
                            tvHello.text = getString(R.string.hello_message)
                        }
                    }
                    """.trimIndent() + "\n"
                )
            }
            ProjectLanguage.JAVA -> {
                val javaFile = File(workspace.javaDir, "$packagePath/MainActivity.java")
                javaFile.parentFile?.mkdirs()
                javaFile.writeText(
                    """
                    package ${workspace.packageName};

                    import android.app.Activity;
                    import android.os.Bundle;
                    import android.widget.TextView;

                    public class MainActivity extends Activity {
                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            setContentView(R.layout.activity_main);
                            TextView tvHello = findViewById(R.id.tvHello);
                            tvHello.setText(getString(R.string.hello_message));
                        }
                    }
                    """.trimIndent() + "\n"
                )
            }
        }

        return workspace
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
