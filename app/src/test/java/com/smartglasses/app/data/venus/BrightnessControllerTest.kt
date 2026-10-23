package com.smartglasses.app.data.venus

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrightnessControllerTest {

    private class FakeLink(
        var reading: BrightnessReading? = BrightnessReading(level = 128, auto = false),
        var setResult: VenusResult = VenusResult.SUCCESS,
        var autoResult: VenusResult = VenusResult.SUCCESS,
    ) : BrightnessLink {
        /** "set:<level>" and "auto:<enabled>" in call order. */
        val calls = mutableListOf<String>()

        override suspend fun read() = reading
        override suspend fun set(level: Int): VenusResult {
            calls += "set:$level"
            return setResult
        }
        override suspend fun setAuto(enabled: Boolean): VenusResult {
            calls += "auto:$enabled"
            return autoResult
        }
    }

    private fun TestScope.controller(link: FakeLink, problems: MutableList<BrightnessProblem> = mutableListOf()): BrightnessController {
        val controller = BrightnessController(link, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.problems.toList(problems) }
        return controller
    }

    /** The controller runs in backgroundScope, which advanceUntilIdle does not wait for. */
    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun `percent and glasses level convert both ways`() {
        for (percent in BrightnessScale.MIN_PERCENT..100) {
            assertEquals(percent, BrightnessScale.toPercent(BrightnessScale.toLevel(percent)))
        }
        assertEquals(255, BrightnessScale.toLevel(100))
        assertEquals(BrightnessScale.toLevel(BrightnessScale.MIN_PERCENT), BrightnessScale.toLevel(0))
        assertEquals(BrightnessScale.MIN_PERCENT, BrightnessScale.toPercent(0))
        assertEquals(27, BrightnessScale.toPercent(69))
    }

    @Test
    fun `opening shows the glasses' current values`() = runTest {
        val controller = controller(FakeLink(BrightnessReading(level = 69, auto = true)))
        controller.load()
        settle()
        assertFalse(controller.state.value.loading)
        assertEquals(27, controller.state.value.percent)
        assertTrue(controller.state.value.auto)
    }

    @Test
    fun `dragging sends the newest level and always the final one`() = runTest {
        val link = FakeLink()
        val controller = controller(link)
        controller.load()
        settle()
        for (percent in 20..60) controller.setPercent(percent)
        settle()
        assertEquals("set:${BrightnessScale.toLevel(60)}", link.calls.last())
        assertTrue("sends were not throttled: ${link.calls}", link.calls.size < 41)
        assertEquals(60, controller.state.value.percent)
    }

    @Test
    fun `moving the slider turns auto brightness off first`() = runTest {
        val link = FakeLink(BrightnessReading(level = 128, auto = true))
        val controller = controller(link)
        controller.load()
        settle()
        controller.setPercent(40)
        assertFalse("switch flips at once", controller.state.value.auto)
        settle()
        assertEquals(listOf("auto:false", "set:${BrightnessScale.toLevel(40)}"), link.calls)
    }

    @Test
    fun `a rejected level puts the slider back and reports it`() = runTest {
        val link = FakeLink(setResult = VenusResult.TIMEOUT)
        val problems = mutableListOf<BrightnessProblem>()
        val controller = controller(link, problems)
        controller.load()
        settle()
        controller.setPercent(90)
        settle()
        assertEquals(BrightnessScale.toPercent(128), controller.state.value.percent)
        assertEquals(listOf(BrightnessProblem.SET_FAILED), problems)
    }

    @Test
    fun `glasses without a light sensor lose the auto switch`() = runTest {
        val problems = mutableListOf<BrightnessProblem>()
        val controller = controller(FakeLink(autoResult = VenusResult.FAILED), problems)
        controller.load()
        settle()
        controller.setAuto(true)
        settle()
        assertFalse(controller.state.value.auto)
        assertFalse(controller.state.value.autoSupported)
        assertEquals(listOf(BrightnessProblem.AUTO_UNSUPPORTED), problems)
    }

    @Test
    fun `an unanswered read still opens the slider and reports it`() = runTest {
        val problems = mutableListOf<BrightnessProblem>()
        val controller = controller(FakeLink(reading = null), problems)
        controller.load()
        settle()
        assertFalse(controller.state.value.loading)
        assertEquals(listOf(BrightnessProblem.READ_FAILED), problems)
    }
}
