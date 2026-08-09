package io.github.mangi.flymefreeform.config

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsSnapshotTest {
    @Test
    fun missingIconStyleKeysUseProductDefaults() {
        val snapshot = ModuleSettingsSnapshot.readFrom(InMemoryPreferences())

        assertEquals(120, snapshot.radialIconContentScalePercent)
        assertEquals(100, snapshot.radialIconMaskScalePercent)
        assertEquals(84, snapshot.cornerTriggerRangeDp)
        assertTrue(snapshot.radialCircularIconsEnabled)
    }

    @Test
    fun iconStyleValuesAreClampedWhenRead() {
        val preferences =
            InMemoryPreferences(
                ModulePreferences.KEY_RADIAL_ICON_CONTENT_SCALE_PERCENT to 40,
                ModulePreferences.KEY_RADIAL_ICON_MASK_SCALE_PERCENT to 180,
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP to 8,
            )

        val snapshot = ModuleSettingsSnapshot.readFrom(preferences)

        assertEquals(80, snapshot.radialIconContentScalePercent)
        assertEquals(120, snapshot.radialIconMaskScalePercent)
        assertEquals(24, snapshot.cornerTriggerRangeDp)
    }

    @Test
    fun iconStyleValuesAreClampedAndRoundTripWhenWritten() {
        val preferences = InMemoryPreferences()
        ModuleSettingsSnapshot(
            radialIconContentScalePercent = 121,
            radialIconMaskScalePercent = 79,
            radialCircularIconsEnabled = false,
            cornerTriggerRangeDp = 200,
        ).writeTo(preferences.edit()).commit()

        assertEquals(
            120,
            preferences.getInt(ModulePreferences.KEY_RADIAL_ICON_CONTENT_SCALE_PERCENT, 0),
        )
        assertEquals(
            80,
            preferences.getInt(ModulePreferences.KEY_RADIAL_ICON_MASK_SCALE_PERCENT, 0),
        )
        assertEquals(
            160,
            preferences.getInt(ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP, 0),
        )
        val restored = ModuleSettingsSnapshot.readFrom(preferences)
        assertEquals(120, restored.radialIconContentScalePercent)
        assertEquals(80, restored.radialIconMaskScalePercent)
        assertEquals(160, restored.cornerTriggerRangeDp)
        assertFalse(restored.radialCircularIconsEnabled)
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
