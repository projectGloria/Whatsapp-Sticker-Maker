package com.stickerstudio

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.*

class StudioUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val packName = "Flow ${UUID.randomUUID().toString().take(8)}"

    @After
    fun cleanup() {
        val repo = Repository(InstrumentationRegistry.getInstrumentation().targetContext)
        repo.dao.all().filter { it.pack.name == packName }.forEach { repo.removePack(it.pack.id) }
    }

    private fun waitFor(text: String) {
        try {
            rule.waitUntil(20000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("Waiting for: $text\n" + rule.onRoot().printToString(), e)
        }
    }

    private fun share(vararg names: String) {
        val uris =
            ArrayList(names.map { Uri.parse("content://com.stickerstudio.test.fixtures/$it") })
        val intent =
            Intent(if (names.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
                .setClassName(rule.activity, "com.stickerstudio.MainActivity")
                .setType("image/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (names.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris[0])
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        InstrumentationRegistry.getInstrumentation().context.startActivity(intent)
    }

    @Test
    fun createShareEditAndReopenCollection() {
        waitFor("My packs")
        rule.onNodeWithContentDescription("New pack").performClick()
        rule.onNodeWithText("Pack name").performTextInput(packName)
        rule.onNodeWithText("Save pack").performClick()
        waitFor("0 of 30 stickers")
        share("source.png")
        waitFor("1 files ready to preview")
        rule.onNodeWithTag("import-list").performScrollToNode(hasText(packName))
        rule.onNodeWithText(packName).performClick()
        rule.onNodeWithTag("import-list").performScrollToNode(hasText("Save stickers"))
        rule.onNodeWithText("Save stickers").performClick()
        waitFor("Saved 1 sticker")
        rule.onNodeWithText("OK").performClick()
        waitFor("1 of 30 stickers")
        rule.onNodeWithContentDescription("Sticker 1").performClick()
        rule.onNodeWithText("Edit sticker").performClick()
        waitFor("Photo studio")
        rule.onNodeWithText("Text", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Sticker text").performScrollTo().performTextInput("Hello!")
        rule.onNodeWithText("Save", substring = false).performClick()
        waitFor("Sticker saved")
        rule.onNodeWithText("OK").performClick()
        rule.activityRule.scenario.recreate()
        waitFor(packName)
        rule.onNodeWithText(packName).performClick()
        waitFor("1 of 30 stickers")
    }

    @Test
    fun mixedAndMalformedSharedFilesAreReportedIndividually() {
        share("source.png", "disposal.gif", "bad.png")
        waitFor("Some files need attention")
        rule.onNodeWithText("Got it").performClick()
        waitFor("2 files ready to preview")
        rule.onNodeWithText("Static image").assertExists()
        rule.onNodeWithText("Animated · 0.4s").assertExists()
        rule.onNodeWithTag("import-list").performScrollToNode(hasText("Discard imports"))
        rule.onNodeWithText("Discard imports").performClick()
        waitFor("My packs")
    }
}
