package com.stickerstudio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Reproduces loss of an unsaved edit during Activity recreation. */
class ReviewLifecycleAuditTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun auditUnsavedTextDisappearsOnActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file =
            File.createTempFile("audit_lifecycle_", ".png", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("source.png").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val media = MediaEngine.inspect(file)
        try {
            rule.runOnIdle {
                ViewModelProvider(rule.activity)[StudioModel::class.java].editor =
                    EditorTarget(media, Edit())
            }
            rule.waitUntil(10000) {
                rule.onAllNodesWithText("Photo studio").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNode(hasText("Text") and hasClickAction()).performSemanticsAction(
                SemanticsActions.OnClick
            ) {
                it()
            }
            rule.waitUntil(10000) {
                rule.onAllNodesWithText("Sticker text").fetchSemanticsNodes().isNotEmpty()
            }
            rule
                .onNodeWithText("Sticker text")
                .performScrollTo()
                .performTextInput("Unsaved audit text")
            rule.onNodeWithText("Unsaved audit text").assertExists()
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10000) {
                rule.onAllNodesWithText("Photo studio").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNode(hasText("Text") and hasClickAction()).performSemanticsAction(
                SemanticsActions.OnClick
            ) {
                it()
            }
            rule.onNodeWithText("Unsaved audit text").assertExists()
        } finally {
            file.delete()
        }
    }
}
