package io.github.mangi.flymefreeform.config

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsSnapshotTest {
    @Test
    fun missingKeysUseProductDefaults() {
        val snapshot = ModuleSettingsSnapshot.readFrom(InMemoryPreferences())

        assertEquals(84, snapshot.cornerTriggerRangeDp)
        assertEquals(OutsideTapCloseMode.SingleTap, snapshot.outsideTapCloseMode)
        assertTrue(snapshot.handleSwipeUpToMiniEnabled)
        assertTrue(snapshot.pauseInLandscape)
        assertTrue(snapshot.pauseInGameMode)
    }

    @Test
    fun retiredAppearanceSettingsDoNotAffectCurrentSettings() {
        val preferences = InMemoryPreferences(
            "radial_icon_mask_scale_percent" to "retired",
            "radial_icon_content_scale_percent" to "retired",
            "radial_circular_icons_enabled" to "retired",
            "radial_icon_size_percent_v1" to "retired",
            "radial_icon_roundness_percent_v1" to "retired",
        )
        val expected = ModuleSettingsSnapshot.readFrom(InMemoryPreferences())
        val actual = ModuleSettingsSnapshot.readFrom(preferences)
        assertEquals(expected, actual)
        actual.writeTo(preferences.edit()).commit()
        assertEquals(expected, ModuleSettingsSnapshot.readFrom(preferences))
        assertEquals("retired", preferences.getString("radial_icon_size_percent_v1", null))
        assertEquals("retired", preferences.getString("radial_icon_roundness_percent_v1", null))
    }

    @Test
    fun interactionSettingsRoundTrip() {
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(
            outsideTapCloseMode = OutsideTapCloseMode.DoubleTap,
            handleSwipeUpToMiniEnabled = false,
        ).writeTo(preferences.edit()).commit()

        val restored = ModuleSettingsSnapshot.readFrom(preferences)

        assertEquals(OutsideTapCloseMode.DoubleTap, restored.outsideTapCloseMode)
        assertFalse(restored.handleSwipeUpToMiniEnabled)
    }

    @Test
    fun pauseSwitchesRoundTripIndependently() {
        for (landscape in listOf(false, true)) {
            for (game in listOf(false, true)) {
                val preferences = InMemoryPreferences()
                val expected = ModuleSettingsSnapshot(
                    enabled = true,
                    pauseInLandscape = landscape,
                    pauseInGameMode = game,
                )
                expected.writeTo(preferences.edit()).commit()
                assertEquals(expected, ModuleSettingsSnapshot.readFrom(preferences))
            }
        }
    }

    @Test
    fun eitherEnabledPauseRuleBlocksItsEnvironment() {
        val defaults = ModuleSettingsSnapshot()
        assertFalse(defaults.isPausedByEnvironment(landscape = false, gameMode = false))
        assertTrue(defaults.isPausedByEnvironment(landscape = true, gameMode = false))
        assertTrue(defaults.isPausedByEnvironment(landscape = false, gameMode = true))

        val allowLandscape = defaults.copy(pauseInLandscape = false)
        assertFalse(allowLandscape.isPausedByEnvironment(landscape = true, gameMode = false))
        assertTrue(allowLandscape.isPausedByEnvironment(landscape = true, gameMode = true))

        val allowGames = defaults.copy(pauseInGameMode = false)
        assertFalse(allowGames.isPausedByEnvironment(landscape = false, gameMode = true))
        assertTrue(allowGames.isPausedByEnvironment(landscape = true, gameMode = true))

        val allowBoth = defaults.copy(pauseInLandscape = false, pauseInGameMode = false)
        assertFalse(allowBoth.isPausedByEnvironment(landscape = true, gameMode = true))
    }

    @Test
    fun unknownOutsideTapModeFailsClosed() {
        val preferences =
            InMemoryPreferences(ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE to 99)

        assertEquals(
            OutsideTapCloseMode.Disabled,
            ModuleSettingsSnapshot.readFrom(preferences).outsideTapCloseMode,
        )
    }

    @Test
    fun cornerRangeIsClampedWhenRead() {
        val preferences =
            InMemoryPreferences(
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP to 8,
            )

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertEquals(24, snapshot.cornerTriggerRangeDp)
    }

    @Test
    fun cornerRangeIsClampedAndRoundTripsWhenWritten() {
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(
            cornerTriggerRangeDp = 200,
        ).writeTo(preferences.edit()).commit()

        assertEquals(
            160,
            preferences.getInt(ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP, 0),
        )
        val restored = ModuleSettingsSnapshot.readFrom(preferences)
        assertEquals(160, restored.cornerTriggerRangeDp)
    }
}

private class InMemoryPreferences(vararg initialValues: Pair<String, Any>) : SharedPreferences {
    private val values = linkedMapOf(*initialValues)

    override fun getAll(): Map<String, *> = values.toMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key]?.let { it as String } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        values[key]?.let { it as Set<String> } ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key]?.let { it as Int } ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key]?.let { it as Long } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key]?.let { it as Float } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key]?.let { it as Boolean } ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = linkedMapOf<String, Any>()
        private val removals = linkedSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            updateNullable(key, value)

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            updateNullable(key, values?.toSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = update(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = update(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = update(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = update(key, value)

        override fun remove(key: String): SharedPreferences.Editor = apply {
            updates.remove(key)
            removals += key
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clear = true
            updates.clear()
            removals.clear()
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun update(key: String, value: Any): SharedPreferences.Editor = apply {
            removals -= key
            updates[key] = value
        }

        private fun updateNullable(key: String, value: Any?): SharedPreferences.Editor =
            if (value == null) remove(key) else update(key, value)

        private fun applyChanges() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            values.putAll(updates)
        }
    }
}
