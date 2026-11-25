package com.claritykey.keyboard

import android.content.Context
import com.claritykey.keyboard.clipboard.*
import com.claritykey.keyboard.engine.PersonalMemoryEngine
import com.claritykey.keyboard.engine.TextPowerTools
import com.claritykey.keyboard.layout.*
import com.claritykey.keyboard.prefs.BreenoPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacyAndLayoutRegressionTest {
    private lateinit var context: Context
    private lateinit var prefs: BreenoPreferences

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("breeno_keyboard_ru_prefs", 0).edit().clear().commit()
        context.getSharedPreferences("breeno_personal_memory_v1", 0).edit().clear().commit()
        prefs = BreenoPreferences(context)
        prefs.vaultUnlockedUntilMs = 0L
    }

    @Test fun vaultIgnoresPersistedUnlockAndExpiresWhileOpen() {
        val store = context.getSharedPreferences("breeno_keyboard_ru_prefs", 0)
        store.edit().putLong("vault_unlocked_until_ms", Long.MAX_VALUE)
            .putString("vault_items_enc", "raw1:c2VjcmV0").commit()
        assertFalse(prefs.isVaultUnlocked())
        assertTrue(prefs.getVaultItems().isEmpty())
        prefs.vaultUnlockedUntilMs = System.currentTimeMillis() + 30_000L
        assertTrue(prefs.isVaultUnlocked())
        assertEquals(listOf("secret"), prefs.getVaultItems())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        assertFalse(prefs.isVaultUnlocked())
        assertTrue(prefs.getVaultItems().isEmpty())
        assertEquals(1, prefs.getVaultItemCount())
    }

    @Test fun keystoreFailureDoesNotWritePlaintextOrDestroyExistingVault() {
        // JVM Android sandbox has no AndroidKeyStore provider: exercise the failure path.
        val store = context.getSharedPreferences("breeno_keyboard_ru_prefs", 0)
        store.edit().putString("vault_items_enc", "enc1:unreadable-existing-record").commit()
        assertFalse(prefs.addVaultItem("new secret"))
        assertEquals("enc1:unreadable-existing-record", store.getString("vault_items_enc", null))
    }

    @Test fun sensitiveDetectionWorksWithSmartPasteDisabled() {
        prefs.smartPasteEnabled = false
        val analyzer = SmartPasteAnalyzer(prefs)
        try {
            fun result(text: String) = analyzer.analyzeSync(ClipEntry(1, text, "test", false, 0, 0))
            assertTrue(result("Код подтверждения: 482910").hasSensitiveData)
            assertTrue(result("4111 1111 1111 1111").hasSensitiveData)
            assertFalse(result("Встреча в 14:30").hasSensitiveData)
            assertTrue(result("Код подтверждения: 482910").entities.isEmpty())
        } finally { analyzer.release() }
    }

    @Test fun ocrCachedTextStillDetectsSecretsWhenFragmentsAreHidden() {
        prefs.smartPasteEnabled = false
        prefs.visionShowFragments = false
        val analyzer = SmartPasteAnalyzer(prefs)
        val ocr = SmartPasteVisionOcr(context, prefs, MediaClipboardStorage(context), analyzer)
        try {
            val entry = ClipEntry(2, "", "image", false, 0, 0,
                type = ClipItemType.IMAGE, ocrText = "Код подтверждения: 482910")
            val result = ocr.getCached(entry)
            assertNotNull(result)
            assertTrue(result!!.hasSensitiveData)
            assertTrue(result.entities.isEmpty())
        } finally { ocr.release(); analyzer.release() }
    }

    @Test fun clearingMemoryInvalidatesAlreadyRunningEngine() {
        prefs.memoryEffectEnabled = true
        prefs.personalLearningEnabled = true
        val live = PersonalMemoryEngine(context, prefs)
        live.recordCommittedPhrase("мы", "любим", "кофе")
        assertEquals(listOf("кофе"), live.predictNextWords("мы", "любим"))
        PersonalMemoryEngine(context, prefs).clearAll()
        assertTrue(live.predictNextWords("мы", "любим").isEmpty())
        live.flush()
        assertTrue(PersonalMemoryEngine(context, prefs).predictNextWords("мы", "любим").isEmpty())
    }

    @Test fun separateYoAndHardSignFitWithoutLosingLetters() {
        for (hard in listOf(false, true)) {
            val spec = BreenoLayouts.buildLayout(KeyboardMode.ALPHA_RU, separateYo = true, separateHardSign = hard)
            val keys = BreenoLayouts.computeGeometry(spec, 360f, 240f)
            assertEquals(1, keys.count { it.spec.output == "ё" })
            for (key in keys) {
                assertTrue("${key.spec.label} starts outside keyboard", key.visualRect.left >= 0f)
                assertTrue("${key.spec.label} ends outside keyboard", key.visualRect.right <= 360.1f)
            }
        }
    }

    @Test fun adjacentKeyGapsAreClickableButSplitGapStaysEmpty() {
        val normal = BreenoLayouts.computeGeometry(BreenoLayouts.buildLayout(KeyboardMode.ALPHA_RU), 360f, 240f)
        val a = normal[0]; val b = normal[1]
        val x = (a.visualRect.right + b.visualRect.left) / 2f
        val y = a.visualRect.centerY()
        assertTrue(normal.any { it.touchRect.contains(x, y) })
        val split = BreenoLayouts.computeGeometry(BreenoLayouts.buildLayout(KeyboardMode.ALPHA_RU, splitGapS = 0.15f), 720f, 240f)
        val row = split.filter { it.visualRect.top == split[0].visualRect.top }
        val pair = row.zipWithNext().maxBy { it.second.visualRect.left - it.first.visualRect.right }
        val center = (pair.first.visualRect.right + pair.second.visualRect.left) / 2f
        assertFalse(split.any { it.touchRect.contains(center, split[0].visualRect.centerY()) })
    }

    @Test fun keyboardExposesVirtualLettersAndAccessibleClick() {
        val keyboard = com.claritykey.keyboard.view.BreenoKeyboardView(context)
        keyboard.layout(0, 0, 360, 240)
        var clicked: String? = null
        val listenerClass = com.claritykey.keyboard.view.BreenoKeyboardView.Listener::class.java
        keyboard.listener = java.lang.reflect.Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, args ->
            if (method.name == "onLetterTap") clicked = (args!![0] as ComputedKey).spec.output
            null
        } as com.claritykey.keyboard.view.BreenoKeyboardView.Listener
        val provider = keyboard.accessibilityNodeProvider!!
        val node = provider.createAccessibilityNodeInfo(0)!!
        assertEquals("й", node.contentDescription.toString())
        assertTrue(provider.performAction(0, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("й", clicked)
    }

    @Test fun undoHasNoInventedEmptySnapshotAndClearIsolatesEditors() {
        val stack = TextPowerTools.UndoRedoStack(5)
        stack.record("existing text")
        assertNull(stack.undo("existing text"))
        stack.record("existing text edited")
        assertEquals("existing text", stack.undo("existing text edited"))
        stack.clear()
        assertNull(stack.undo("another editor"))
        assertNull(stack.redo("another editor"))
    }
}
