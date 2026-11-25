package com.claritykey.keyboard

import com.claritykey.keyboard.engine.BreenoDictionaryEngine
import com.claritykey.keyboard.engine.InlineCalculator
import com.claritykey.keyboard.engine.RamblerSpeechProcessor
import com.claritykey.keyboard.engine.RamblerStyle
import com.claritykey.keyboard.engine.TextPowerTools
import com.claritykey.keyboard.ime.OtpMemoryBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTypingEngineTest {

    // ------------------------------------------------------------------
    // 1. Spatial Gaussian Decoder Core Tests
    // ------------------------------------------------------------------
    @Test
    fun spatialDecoder_scoresNeighborSlipsLowerCostThanWrongWords() {
        val centers = mapOf(
            'п' to (100f to 100f),
            'р' to (140f to 100f),
            'и' to (180f to 100f),
            'в' to (220f to 100f),
            'е' to (260f to 100f),
            'т' to (300f to 100f),
            'а' to (500f to 200f)
        )
        // Simulated fast taps slightly shifted from exact centers of "привет"
        val taps = listOf(
            106f to 102f,
            148f to 98f,
            174f to 104f,
            226f to 97f,
            255f to 103f,
            307f to 101f
        )
        val costPrivet = BreenoDictionaryEngine.computeSpatialCost("привет", taps, centers, 40f, 50f)
        val costBad = BreenoDictionaryEngine.computeSpatialCost("привеа", taps, centers, 40f, 50f)
        assertTrue("Expected valid low spatial cost for 'привет', got $costPrivet", costPrivet in 0.0..1.0)
        assertTrue("Expected out-of-range cost for far letter 'а'", costBad < 0.0 || costBad > costPrivet * 5.0)
    }

    // ------------------------------------------------------------------
    // 2. Russian Grammatical Case & Morphology Context Tests
    // ------------------------------------------------------------------
    @Test
    fun morphology_prefersCorrectRussianCasesAfterPrepositionsAndVerbs() {
        assertTrue(
            BreenoDictionaryEngine.scoreWordForContext("для", "работы") >
                BreenoDictionaryEngine.scoreWordForContext("для", "работа")
        )
        assertTrue(
            BreenoDictionaryEngine.scoreWordForContext("хочу", "сделать") >
                BreenoDictionaryEngine.scoreWordForContext("хочу", "сделал")
        )
        assertTrue(
            BreenoDictionaryEngine.scoreWordForContext("с", "удовольствием") >
                BreenoDictionaryEngine.scoreWordForContext("с", "удовольствие")
        )
        assertTrue(
            BreenoDictionaryEngine.scoreWordForContext("новую", "версию") >
                BreenoDictionaryEngine.scoreWordForContext("новую", "версия")
        )
        assertTrue(
            BreenoDictionaryEngine.scoreWordForContext("пять", "дней") >
                BreenoDictionaryEngine.scoreWordForContext("пять", "день")
        )
    }

    // ------------------------------------------------------------------
    // 3. Offline Rambler Speech Processor Tests
    // ------------------------------------------------------------------
    @Test
    fun rambler_removesFillersSelfCorrectionsAndRestoresPunctuation() {
        val raw = "ну типа короче давай в пять нет в шесть встретимся я я еще подумаю что взять"
        val out = RamblerSpeechProcessor.process(raw, RamblerStyle.NATURAL)
        assertEquals("Давай в шесть встретимся я ещё подумаю, что взять.", out)
    }

    @Test
    fun rambler_detectsQuestionsAndConciseOpeners() {
        val q = RamblerSpeechProcessor.process("ну когда ты придешь", RamblerStyle.NATURAL)
        assertEquals("Когда ты придёшь?", q)

        val concise = RamblerSpeechProcessor.process("привет слушай отчет готов", RamblerStyle.CONCISE)
        assertEquals("Отчёт готов.", concise)
    }

    @Test
    fun rambler_formatsBulletList() {
        val list = RamblerSpeechProcessor.process("купить молоко ещё хлеб также кофе", RamblerStyle.LIST)
        assertEquals("• Купить молоко\n• Хлеб\n• Кофе", list)
    }

    // ------------------------------------------------------------------
    // 4. Inline Calculator, Smart Math & Auto-Typography Tests
    // ------------------------------------------------------------------
    @Test
    fun calculatorAndSmartMath_evaluateAccurately() {
        val calc = InlineCalculator.evaluateTail("1450*12=")
        assertNotNull(calc)
        assertEquals("17400", calc?.plainResult)

        assertEquals("1725", TextPowerTools.tryEvalSmartMath("1500+15%="))
        assertEquals("2000", TextPowerTools.tryEvalSmartMath("2500-20%"))
        assertEquals("15:15", TextPowerTools.tryEvalSmartMath("14:30+45м"))
        assertNull(TextPowerTools.tryEvalSmartMath("привет"))
    }

    @Test
    fun autoTypography_andUndoRedoStack_workCorrectly() {
        assertEquals(1 to "—", TextPowerTools.applyAutoTypography("слово -", "-", "RU"))
        assertEquals(2 to "…", TextPowerTools.applyAutoTypography("ну..", ".", "RU"))
        assertEquals(2 to "©", TextPowerTools.applyAutoTypography("test (c", ")", "RU"))
        assertEquals(0 to "«", TextPowerTools.applyAutoTypography("сказал ", "\"", "RU"))
        assertEquals(0 to "»", TextPowerTools.applyAutoTypography("сказал «привет", "\"", "RU"))

        val stack = TextPowerTools.UndoRedoStack(5)
        stack.record("one")
        stack.record("one two")
        assertEquals("one", stack.undo("one two"))
        assertEquals("one two", stack.redo("one"))
    }

    @Test
    fun smartClipboardReply_detectsQuestionsAndAutofillRequests() {
        val emailReply = TextPowerTools.suggestSmartReplyForClipboard(
            copiedText = "Скинь свою почту пожалуйста",
            savedEmail = "user@example.com"
        )
        assertNotNull(emailReply)
        assertEquals("user@example.com", emailReply?.second)

        val timeReply = TextPowerTools.suggestSmartReplyForClipboard("Во сколько завтра встреча?")
        assertNotNull(timeReply)
        assertTrue(timeReply!!.second.contains("15:00"))
    }

    @Test
    fun otpExtractor_extractsRussianAndEnglishSmsAndPushCodes() {
        assertEquals("482910", OtpMemoryBridge.extractOtpFromNotification("Госуслуги", "Код подтверждения: 482910"))
        assertEquals("549201", OtpMemoryBridge.extractOtpFromNotification("Telegram", "Никому не сообщайте код 549-201"))
        assertEquals("7741", OtpMemoryBridge.extractOtpFromNotification("СберБанк", "Пароль для входа: 7741"))
        assertEquals("839201", OtpMemoryBridge.extractOtpFromNotification("Google", "Your verification code is 839201"))
        assertNull(OtpMemoryBridge.extractOtpFromNotification("Календарь", "Встреча сегодня в 14:30"))
    }

    @Test
    fun keyboardNeighborFuzzy_allowsUpTo3AdjacentKeyTyposAndRejectsUnrelatedWords() {
        // 3 neighbor typos on ЙЦУКЕН: сргодгч -> сегодня (р~е, г~н, ч~я)
        assertEquals(3, BreenoDictionaryEngine.keyboardNeighborPenalty("сргодгч", "сегодня", "RU"))
        // 3 neighbor typos: лржвлуйста -> пожалуйста (а~п, р~о, в~а)
        assertEquals(3, BreenoDictionaryEngine.keyboardNeighborPenalty("аржвлуйста", "пожалуйста", "RU"))
        // 2 neighbor typos: лривкт -> привет (а~п, к~е)
        assertEquals(2, BreenoDictionaryEngine.keyboardNeighborPenalty("аривкт", "привет", "RU"))
        // Unrelated word rejected (-1)
        assertEquals(-1, BreenoDictionaryEngine.keyboardNeighborPenalty("сргодгч", "машинка", "RU"))
    }
}