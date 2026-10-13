package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ColorOsTaskImeBoundsAccessTest {
    @Test
    fun readsCurrentImeBoundsForEachTaskWithoutCachingTheResult() {
        val firstExtension = FakeTaskExt(FakeBounds())
        val firstTask = FakeTask(FakeWrapper(firstExtension))
        val secondExtension = FakeTaskExt(FakeBounds())
        val secondTask = FakeTask(FakeWrapper(secondExtension))
        val access = access()

        assertSame(firstExtension.bounds, access.get(firstTask))
        assertSame(secondExtension.bounds, access.get(secondTask))

        firstExtension.bounds = FakeBounds()
        assertSame(firstExtension.bounds, access.get(firstTask))

        firstExtension.bounds = null
        assertNull(access.get(firstTask))
    }

    @Test
    fun missingWrapperOrExtensionDoesNotLeaveStaleBounds() {
        val access = access()

        assertNull(access.get(FakeTask(null)))
        assertNull(access.get(FakeTask(FakeWrapper(null))))
    }

    @Test
    fun propagatesOriginalAccessorFailure() {
        val failure = IllegalStateException("IME bounds unavailable")
        val extension = FakeTaskExt(null, failure)
        try {
            access().get(FakeTask(FakeWrapper(extension)))
            throw AssertionError("Expected accessor failure")
        } catch (exception: IllegalStateException) {
            assertSame(failure, exception)
        }
    }

    @Test(expected = NoSuchMethodException::class)
    fun rejectsUnexpectedBoundsTypeBeforeInstallingHooks() {
        ColorOsTaskImeBoundsAccess(
            FakeTask::class.java,
            FakeTaskExt::class.java,
            String::class.java,
        )
    }

    private fun access() =
        ColorOsTaskImeBoundsAccess(
            FakeTask::class.java,
            FakeTaskExt::class.java,
            FakeBounds::class.java,
        )

    private class FakeBounds

    private interface TaskExtension

    private class FakeTaskExt(
        var bounds: FakeBounds?,
        private val failure: RuntimeException? = null,
    ) : TaskExtension {
        @Suppress("unused")
        fun getFlexibleTaskPosAfterIme(): FakeBounds? {
            failure?.let { throw it }
            return bounds
        }
    }

    private interface TaskWrapper {
        fun getExtImpl(): TaskExtension?
    }

    private class FakeWrapper(private val extension: TaskExtension?) : TaskWrapper {
        override fun getExtImpl(): TaskExtension? = extension
    }

    private class FakeTask(private val wrapper: TaskWrapper?) {
        @Suppress("unused")
        fun getWrapper(): TaskWrapper? = wrapper
    }
}
