package com.shade.keyboard

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
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
 * Drives the Gboard-style settings activity on the JVM: the grouped category
 * home, every page (rendering, measuring and binding each row type), back
 * navigation, search and the color picker sheet. Any binding crash (wrong view
 * id, missing resource, theme problem) fails here before it can fail on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsActivitySmokeTest {

    private fun buildActivity(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java)
            .create().start().resume().visible().get()

    private fun measureAndLayout(rv: RecyclerView) {
        val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        // Tall enough that every row of a page is created and bound.
        val h = View.MeasureSpec.makeMeasureSpec(30000, View.MeasureSpec.EXACTLY)
        rv.measure(w, h)
        rv.layout(0, 0, 1080, 30000)
    }

    /** Home rows: one per category plus a gap between the card groups. */
    private fun homeCount(): Int =
        SettingsCatalog.pages.size + SettingsCatalog.homeGroups.size - 1

    private fun pageCount(page: SettingsCatalog.Page): Int =
        (if (page.largeTitle) 1 else 0) +
            page.sections.sumOf { (if (it.titleRes != 0) 1 else 0) + it.items.size }

    private fun positionOf(activity: SettingsActivity, predicate: (SettingsItem) -> Boolean): Int =
        (0 until activity.adapter.itemCount).first {
            activity.adapter.itemAt(it)?.let(predicate) == true
        }

    @Test
    fun opensOnTheHome_withEveryCategoryInGboardOrder() {
        val activity = buildActivity()
        val adapter = activity.adapter
        assertTrue(adapter.isHome)
        assertEquals(homeCount(), adapter.itemCount)
        assertEquals(SettingsCatalog.PAGE_LANGUAGES, adapter.pageAt(0)!!.id)
        assertEquals(SettingsCatalog.PAGE_PREFERENCES, adapter.pageAt(1)!!.id)
        assertEquals(SettingsCatalog.PAGE_THEME, adapter.pageAt(2)!!.id)
        assertTrue(adapter.pageAt(3) == null) // the gap between the first two card groups
        assertEquals(SettingsCatalog.PAGE_CORRECTIONS, adapter.pageAt(4)!!.id)
        // Home: search visible, back button hidden.
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.noir_search_input).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.noir_back).visibility)
        activity.finish()
    }

    @Test
    fun homeBindsEveryCategoryRow_withoutCrashing() {
        val activity = buildActivity()
        val rv = activity.findViewById<RecyclerView>(R.id.noir_list)
        measureAndLayout(rv)
        assertTrue(rv.childCount > 0)
        activity.finish()
    }

    @Test
    fun openingAPage_showsItsRows_andTheBackButton() {
        val activity = buildActivity()
        val page = SettingsCatalog.page(SettingsCatalog.PAGE_PREFERENCES)!!
        activity.openPage(page.id)
        val adapter = activity.adapter
        assertFalse(adapter.isHome)
        assertEquals(page.id, adapter.currentPageId)
        assertEquals(pageCount(page), adapter.itemCount)
        assertTrue(adapter.isTitleAt(0)) // Preferences opens with the large title
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.noir_back).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.noir_search_input).visibility)
        activity.finish()
    }

    @Test
    fun everyPage_bindsEveryRowType_withoutCrashing() {
        val activity = buildActivity()
        val rv = activity.findViewById<RecyclerView>(R.id.noir_list)
        for (page in SettingsCatalog.pages) {
            activity.openPage(page.id)
            assertEquals("rows on page ${page.id}", pageCount(page), activity.adapter.itemCount)
            measureAndLayout(rv)
            assertTrue("page ${page.id} rendered nothing", rv.childCount > 0)
            activity.goHome()
        }
        activity.finish()
    }

    @Test
    fun backButton_returnsToTheHome() {
        val activity = buildActivity()
        activity.openPage(SettingsCatalog.PAGE_THEME)
        activity.findViewById<View>(R.id.noir_back).performClick()
        assertTrue(activity.adapter.isHome)
        assertEquals(homeCount(), activity.adapter.itemCount)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.noir_back).visibility)
        activity.finish()
    }

    @Test
    fun systemBack_leavesAPage_beforeLeavingSettings() {
        val activity = buildActivity()
        activity.openPage(SettingsCatalog.PAGE_GLIDE)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.adapter.isHome)
        assertFalse(activity.isFinishing)
        activity.finish()
    }

    @Test
    fun longChoice_rendersAsValueRow() {
        val activity = buildActivity()
        activity.openPage(SettingsCatalog.PAGE_THEME)
        val rv = activity.findViewById<RecyclerView>(R.id.noir_list)
        measureAndLayout(rv)
        val pos = positionOf(activity) { it is SettingsItem.Choice && it.key == "theme" }
        val holder = rv.findViewHolderForAdapterPosition(pos)
        assertNotNull(holder)
        val value = holder!!.itemView.findViewById<TextView>(R.id.noir_choice_value)
        assertEquals(View.VISIBLE, value.visibility)
        assertTrue(value.text.isNotEmpty())
        activity.finish()
    }

    @Test
    fun switches_useTheGboardCheckAndXStyle() {
        val activity = buildActivity()
        activity.openPage(SettingsCatalog.PAGE_GLIDE)
        val rv = activity.findViewById<RecyclerView>(R.id.noir_list)
        measureAndLayout(rv)
        val pos = positionOf(activity) { it is SettingsItem.Toggle && it.key == "glide_typing" }
        val row = rv.findViewHolderForAdapterPosition(pos)!!.itemView
        val sw = row.findViewById<MaterialSwitch>(R.id.noir_switch)
        // The Gboard look is a Material 3 switch WITH thumb icons (check on / X off).
        assertNotNull("switch has no thumb icon", sw.thumbIconDrawable)
        // Tapping the row flips the switch.
        val before = sw.isChecked
        row.performClick()
        assertEquals(!before, sw.isChecked)
        activity.finish()
    }

    @Test
    fun voiceTyping_hasAHeroMasterSwitch() {
        val activity = buildActivity()
        activity.openPage(SettingsCatalog.PAGE_VOICE)
        val rv = activity.findViewById<RecyclerView>(R.id.noir_list)
        measureAndLayout(rv)
        val pos = positionOf(activity) { it is SettingsItem.Toggle && it.hero }
        assertNotNull(rv.findViewHolderForAdapterPosition(pos))
        assertEquals(SettingsAdapter.TYPE_HERO, activity.adapter.getItemViewType(pos))
        activity.finish()
    }

    @Test
    fun openPage_survivesRecreation() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
            .create().start().resume().visible()
        controller.get().openPage(SettingsCatalog.PAGE_PRIVACY)
        val state = Bundle()
        controller.saveInstanceState(state)

        val restored = Robolectric.buildActivity(SettingsActivity::class.java).create(state)
        assertEquals(SettingsCatalog.PAGE_PRIVACY, restored.get().adapter.currentPageId)
        restored.get().finish()
        controller.get().finish()
    }

    @Test
    fun search_filtersAndRestoresTheHome() {
        val activity = buildActivity()
        val adapter = activity.adapter

        adapter.applySearch("glide")
        assertEquals(1, adapter.itemCount)
        val item = adapter.itemAt(0)
        assertTrue(item is SettingsItem.Toggle && (item as SettingsItem.Toggle).key == "glide_typing")

        adapter.applySearch("zzzqqq")
        assertEquals(1, adapter.itemCount) // the "no results" info row

        adapter.applySearch("")
        assertEquals(homeCount(), adapter.itemCount) // back to the category home
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
        assertEquals(homeCount(), adapter.itemCount)
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
