package com.example.cloudwaste.fixture

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectTenTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun selectsExactlyTenCandidates_noDecoys_noDelete() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.waitForIdle()

        // Wait for app foreground
        device.wait(Until.hasObject(By.pkg("com.example.cloudwaste.fixture")), 15_000)

        val scroll = UiScrollable(UiSelector().resourceId("com.example.cloudwaste.fixture:id/photos_scroll"))
        scroll.setAsVerticalList()
        scroll.setSwipeDeadZonePercentage(0.35)

        for (tileData in FixtureData.tiles.filter { it.kind == MediaTile.Kind.CANDIDATE }) {
            val id = tileData.id
            if (!device.hasObject(By.pkg("com.example.cloudwaste.fixture"))) {
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                val intent = ctx.packageManager.getLaunchIntentForPackage("com.example.cloudwaste.fixture")
                intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ctx.startActivity(intent)
                device.wait(Until.hasObject(By.pkg("com.example.cloudwaste.fixture")), 5_000)
            }

            assertTrue(
                "missing tile $id",
                scroll.scrollIntoView(UiSelector().descriptionContains("$id:"))
            )
            device.waitForIdle()
            Thread.sleep(100)

            val displayHeight = device.displayHeight
            var tile = device.findObject(By.descContains("$id:"))
            if (tile != null && tile.visibleBounds.bottom > displayHeight - 250) {
                scroll.scrollForward(20)
                device.waitForIdle()
                Thread.sleep(150)
                tile = device.findObject(By.descContains("$id:"))
            }

            assertNotNull("missing tile $id after scroll", tile)
            tile!!.click()
            device.waitForIdle()

            var selected = device.wait(Until.hasObject(By.desc("$id:selected")), 2_000)
            if (!selected) {
                tile = device.findObject(By.descContains("$id:"))
                tile?.click()
                device.waitForIdle()
                selected = device.wait(Until.hasObject(By.desc("$id:selected")), 3_000)
            }
            assertTrue("tile $id did not become selected", selected)
        }

        val count = device.findObject(By.desc("selection_count:10"))
        assertNotNull("expected selection_count:10", count)
        assertEquals("선택됨 10개", count!!.text)

        scroll.scrollToBeginning(20)
        device.waitForIdle()

        for (id in FixtureData.decoyIds) {
            assertTrue(
                "missing decoy $id",
                scroll.scrollIntoView(UiSelector().descriptionContains("$id:"))
            )
            device.waitForIdle()
            val decoy = device.findObject(By.desc("$id:unselected"))
            assertNotNull("decoy $id missing or selected", decoy)
        }

        val delete = device.findObject(By.desc("delete_button"))
        assertNotNull(delete)
        assertFalse(
            "Delete was clicked — crown test forbids delete automation",
            delete!!.contentDescription == "delete_button_clicked"
        )

        println("PASS: selected exactly the 10 candidates; no decoys; delete not clicked.")
    }
}
