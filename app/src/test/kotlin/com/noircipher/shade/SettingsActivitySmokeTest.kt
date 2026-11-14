package com.noircipher.shade

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
 * and bind every row type, collapse/expand sections, search, and open the
 * color picker sheet. Any binding crash (wrong view id, missing resource,
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
        val h = View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)
        rv.measure(w, h)
        rv.layout(0, 0, 1080, 2000)
    }

    private fun fullCatalogCount(): Int =
        1 + SettingsCatalog.sections.sumOf { 1 + it.items.size }

    @Test
    fun renders_fullCatalog_withHeroAndSections() {
        val activity = buildActivity()
        val adapter = activity.adapter
        assertEquals(fullCatalogCount(), adapter.itemCount)

        // Row 0 is the hero, row 1 the first section header, row 2 its first child.
        assertTrue(adapter.sectionAt(1) != null)
        assertEquals(SettingsCatalog.SECTION_APPEARANCE, adapter.sectionAt(1)!!.id)
        assertTrue(adapter.itemAt(2) != null)
        activity.finish()
    }

    @Test
    fun measureLayout_bindsEveryVisibleRowType_withoutCrashing() {
        val activity = buildActivity()
        val rv = activity.findViewById<RecyclerView>(R.id.noir_list)
        measureAndLayout(rv)
        // The appearance section (top of the list) covers hero, section header,
        // toggle, choice, color, slider rows — all bound during layout above.
        assertTrue(rv.childCount > 0)
        activity.finish()
    }

    @Test
    fun collapseExpand_changesChildVisibility() {
        val activity = buildActivity()
        val adapter = activity.adapter
        val before = adapter.itemCount
        val appearanceChildren = SettingsCatalog
            .section(SettingsCatalog.SECTION_APPEARANCE)!!.items.size

        adapter.collapse(SettingsCatalog.SECTION_APPEARANCE)
        assertTrue(adapter.isSectionCollapsed(SettingsCatalog.SECTION_APPEARANCE))
        assertEquals(before - appearanceChildren, adapter.itemCount)

        adapter.expand(SettingsCatalog.SECTION_APPEARANCE)
        assertFalse(adapter.isSectionCollapsed(SettingsCatalog.SECTION_APPEARANCE))
        assertEquals(before, adapter.itemCount)
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
        assertEquals(fullCatalogCount(), adapter.itemCount)
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
        assertEquals(fullCatalogCount(), adapter.itemCount)
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
