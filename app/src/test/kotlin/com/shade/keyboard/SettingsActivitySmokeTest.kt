package com.shade.keyboard

import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Drives the redesigned (Noir) settings activity on the JVM: render, measure
 * and bind every row type, collapse/expand sections (the screen opens fully
 * collapsed), search, and open the color picker sheet. Any binding crash (wrong view id, missing resource,
 * theme problem) fails here before it can fail on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivitySmokeTest {

    private fun buildActivity(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java)
            .create().start().resume().visible().get()

    private fun measureAndLayout(rv: RecyclerView) {
        val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        // Tall enough that every row of the expanded catalog is created and bound.
        val h = View.MeasureSpec.makeMeasureSpec(30000, View.MeasureSpec.EXACTLY)
        rv.measure(w, h)
        rv.layout(0, 0, 1080, 30000)
    }

    /** The screen opens as a short index: one header per section, nothing expanded. */
    private fun collapsedCount(): Int = SettingsCatalog.sections.size

    private fun expandedCount(): Int = SettingsCatalog.sections.sumOf { 1 + it.items.size }

    private fun expandAll(activity: SettingsActivity) =
        SettingsCatalog.sections.forEach { activity.adapter.expand(it.id) }

    @Test
    fun opensFullyCollapsed_withOneHeaderPerSection() {
        val activity = buildActivity()
        val adapter = activity.adapter
        assertEquals(collapsedCount(), adapter.itemCount)
        assertEquals(SettingsCatalog.SECTION_LANGUAGES, adapter.sectionAt(0)!!.id)
        assertTrue(SettingsCatalog.sections.all { adapter.isSectionCollapsed(it.id) })
        assertTrue(adapter.itemAt(1) == null) // next row is another header, not a child
        activity.finish()
    }

    @Test
    fun expandingEverything_rendersFullCatalog() {
        val activity = buildActivity()
        expandAll(activity)
        assertEquals(expandedCount(), activity.adapter.itemCount)
        assertTrue(activity.adapter.itemAt(1) != null)
        activity.finish()
    }

    @Test
    fun measureLayout_bindsEveryRowType_withoutCrashing() {
        val activity = buildActivity()
        expandAll(activity)
        val rv = activity.findViewById<RecyclerView>(R.id.noir_list)
        measureAndLayout(rv)
        // Every toggle, slider, choice, color, action and info row is bound above.
        assertTrue(rv.childCount > 0)
        activity.finish()
    }

    @Test
    fun longChoice_rendersAsValueRow_notSegments() {
        val activity = buildActivity()
        activity.adapter.expand(SettingsCatalog.SECTION_APPEARANCE)
        val rv = activity.findViewById<RecyclerView>(R.id.noir_list)
        measureAndLayout(rv)
        // Row 0 = header, row 1 = the theme choice (six options).
        val row = rv.findViewHolderForAdapterPosition(1)!!.itemView
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.noir_choice_value).visibility)
        assertEquals(View.GONE, row.findViewById<View>(R.id.noir_group).visibility)
        activity.finish()
    }

    @Test
    fun collapseExpand_changesChildVisibility() {
        val activity = buildActivity()
        val adapter = activity.adapter
        val before = adapter.itemCount
        val children = SettingsCatalog
            .section(SettingsCatalog.SECTION_APPEARANCE)!!.items.size

        adapter.expand(SettingsCatalog.SECTION_APPEARANCE)
        assertFalse(adapter.isSectionCollapsed(SettingsCatalog.SECTION_APPEARANCE))
        assertEquals(before + children, adapter.itemCount)

        adapter.collapse(SettingsCatalog.SECTION_APPEARANCE)
        assertTrue(adapter.isSectionCollapsed(SettingsCatalog.SECTION_APPEARANCE))
        assertEquals(before, adapter.itemCount)
        activity.finish()
    }

    @Test
    fun expandedSections_roundTripThroughSavedState() {
        val activity = buildActivity()
        val adapter = activity.adapter
        adapter.expand(SettingsCatalog.SECTION_TYPING)
        adapter.expand(SettingsCatalog.SECTION_BACKUP)
        val saved = adapter.expandedIds()
        assertEquals(setOf(SettingsCatalog.SECTION_TYPING, SettingsCatalog.SECTION_BACKUP), saved.toSet())

        adapter.setExpanded(intArrayOf())
        assertEquals(collapsedCount(), adapter.itemCount)
        adapter.setExpanded(saved)
        assertFalse(adapter.isSectionCollapsed(SettingsCatalog.SECTION_TYPING))
        assertTrue(adapter.isSectionCollapsed(SettingsCatalog.SECTION_APPEARANCE))
        activity.finish()
    }

    @Test
    fun search_filtersAndRestores() {
        val activity = buildActivity()
        val adapter = activity.adapter

        adapter.applySearch("glide")
        assertEquals(1, adapter.itemCount)
        val item = adapter.itemAt(0)
        assertTrue(item is SettingsItem.Toggle && (item as SettingsItem.Toggle).key == "glide_typing")

        adapter.applySearch("zzzqqq")
        assertEquals(1, adapter.itemCount) // the "no results" info row

        adapter.applySearch("")
        assertEquals(collapsedCount(), adapter.itemCount) // back to the collapsed index
        activity.finish()
    }

    @Test
    fun searchField_drivesAdapter() {
        val activity = buildActivity()
        val input = activity.findViewById<EditText>(R.id.noir_search_input)
        assertNotNull(input)
        input.setText("voice")
        val adapter = activity.adapter
        assertTrue(adapter.itemAt(0) is SettingsItem.Toggle)
        input.setText("")
        assertEquals(collapsedCount(), adapter.itemCount)
        activity.finish()
    }

    @Test
    fun colorPicker_opensShowsPreviewAndCloses() {
        val activity = buildActivity()
        activity.showColorPicker("color_bg", R.string.color_bg, 0xFF1C1C1E.toInt())
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)

        val picker = dialog.findViewById<ColorPickerView>(R.id.color_picker_view)
        assertNotNull(picker)
        // The picker must preselect the row's auto color (1C1C1E → dark gray).
        assertEquals(0xFF1C1C1E.toInt(), picker.currentColor())

        dialog.dismiss()
        assertFalse(dialog.isShowing)
        activity.finish()
    }
}
