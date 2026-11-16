package com.d4viddf.hyperbridge.service

import com.d4viddf.hyperbridge.R
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.random.Random

class HyperPetPillTest {

    private val uri = HyperPetPill.videoUri("com.d4viddf.hyperbridge", 0x7f120001)

    @Test
    fun theVideoUriPointsAtOurRawResource() {
        assertEquals("android.resource://com.d4viddf.hyperbridge/2131886081", uri)
    }

    @Test
    fun theNextClipIsNeverTheOneThatJustPlayed() {
        val clips = listOf(1, 2, 3)
        repeat(50) { seed ->
            val next = HyperPetPill.nextClip(clips, previous = 2, random = Random(seed))
            assertTrue(next == 1 || next == 3)
        }
        assertTrue(HyperPetPill.nextClip(clips, previous = null, random = Random(0)) in clips)
        assertEquals(7, HyperPetPill.nextClip(listOf(7), previous = 7))
    }

    @Test
    fun everyClipIsOffered() {
        val seen = (0 until 200).map { HyperPetPill.nextClip(listOf(1, 2, 3), null, Random(it)) }.toSet()
        assertEquals(setOf(1, 2, 3), seen)
    }

    @Test
    fun atNightTheDogFallsAsleepAndStaysAsleep() {
        val sleepy = com.d4viddf.hyperbridge.R.raw.hyper_pet_sleepy
        val asleep = com.d4viddf.hyperbridge.R.raw.hyper_pet_asleep
        val bone = com.d4viddf.hyperbridge.R.raw.hyper_pet_bone
        for (time in listOf("22:00", "23:30", "00:00", "03:00", "05:59").map(LocalTime::parse)) {
            assertEquals("$time", sleepy, HyperPetPill.clipAt(time, previous = null, wokeToday = true))
            assertEquals("$time", sleepy, HyperPetPill.clipAt(time, previous = bone, wokeToday = false))
            assertEquals("$time", asleep, HyperPetPill.clipAt(time, previous = sleepy, wokeToday = true))
            assertEquals("$time", asleep, HyperPetPill.clipAt(time, previous = asleep, wokeToday = false))
        }
    }

    @Test
    fun theFirstClipOfTheMorningWakesTheDogUp() {
        val morning = LocalTime.of(7, 30)
        assertEquals(R.raw.hyper_pet_wake_up, HyperPetPill.clipAt(morning, previous = R.raw.hyper_pet_asleep, wokeToday = true))
        assertEquals(R.raw.hyper_pet_wake_up, HyperPetPill.clipAt(morning, previous = R.raw.hyper_pet_sleepy, wokeToday = true))
        // The screen was off all night, or the app was restarted: it still wakes up, once.
        assertEquals(R.raw.hyper_pet_wake_up, HyperPetPill.clipAt(morning, previous = R.raw.hyper_pet_bone, wokeToday = false))
        assertEquals(R.raw.hyper_pet_wake_up, HyperPetPill.clipAt(morning, previous = null, wokeToday = false))
        assertTrue(HyperPetPill.clipAt(morning, previous = R.raw.hyper_pet_wake_up, wokeToday = true) != R.raw.hyper_pet_wake_up)
    }

    @Test
    fun theDogIsDrowsyForTheQuarterHourBeforeBed() {
        for (time in listOf("21:45", "21:59").map(LocalTime::parse)) {
            assertEquals("$time", R.raw.hyper_pet_drowsy, HyperPetPill.clipAt(time, previous = R.raw.hyper_pet_bone, wokeToday = true))
            assertEquals("$time", R.raw.hyper_pet_drowsy, HyperPetPill.clipAt(time, previous = R.raw.hyper_pet_drowsy, wokeToday = true))
        }
        assertTrue(HyperPetPill.clipAt(LocalTime.of(21, 44), R.raw.hyper_pet_bone, wokeToday = true) != R.raw.hyper_pet_drowsy)
    }

    @Test
    fun byDayTheDogPlaysARandomClip() {
        val special = setOf(R.raw.hyper_pet_sleepy, R.raw.hyper_pet_asleep, R.raw.hyper_pet_wake_up, R.raw.hyper_pet_drowsy)
        for (time in listOf("06:00", "12:00", "21:44").map(LocalTime::parse)) {
            val seen = (0 until 200).map { HyperPetPill.clipAt(time, R.raw.hyper_pet_wake_up, wokeToday = true, random = Random(it)) }.toSet()
            assertEquals("$time", 6, seen.size)
            assertTrue("$time", seen.none { it in special })
        }
    }

    @Test
    fun theLeftSlotPlaysTheVideoOnALoop() {
        val json = """{"param_v2":{"param_island":{"islandProperty":1,"bigIslandArea":{"imageTextInfoLeft":{"type":1,"textInfo":{"title":"  "}}}}}}"""

        val island = JsonParser.parseString(HyperPetPill.withPet(json, uri)).asJsonObject
            .getAsJsonObject("param_v2").getAsJsonObject("param_island")
        val left = island.getAsJsonObject("bigIslandArea").getAsJsonObject("imageTextInfoLeft")
        val pic = left.getAsJsonObject("picInfo")

        assertEquals(6, pic.get("type").asInt)
        assertEquals(uri, pic.get("pic").asString)
        assertTrue(pic.get("loop").asBoolean)
        assertTrue(pic.get("autoplay").asBoolean)
        // Everything else is left alone.
        assertEquals(1, island.get("islandProperty").asInt)
        assertEquals("  ", left.getAsJsonObject("textInfo").get("title").asString)
    }

    @Test
    fun theCustomViewJsonGetsThePetToo() {
        val json = """{"param_island":{"bigIslandArea":{"imageTextInfoLeft":{"type":1}}}}"""

        val pic = JsonParser.parseString(HyperPetPill.withPet(json, uri)).asJsonObject
            .getAsJsonObject("param_island").getAsJsonObject("bigIslandArea")
            .getAsJsonObject("imageTextInfoLeft").getAsJsonObject("picInfo")

        assertEquals(6, pic.get("type").asInt)
        assertEquals(uri, pic.get("pic").asString)
    }

    @Test
    fun anExistingPictureIsReplaced() {
        val json = """{"param_v2":{"param_island":{"bigIslandArea":{"imageTextInfoLeft":{"picInfo":{"type":1,"pic":"miui.focus.pic_x"}}}}}}"""

        val pic = JsonParser.parseString(HyperPetPill.withPet(json, uri)).asJsonObject
            .getAsJsonObject("param_v2").getAsJsonObject("param_island")
            .getAsJsonObject("bigIslandArea").getAsJsonObject("imageTextInfoLeft")
            .getAsJsonObject("picInfo")

        assertEquals(uri, pic.get("pic").asString)
    }

    @Test
    fun jsonWithoutALeftSlotIsReturnedUntouched() {
        val noSlot = """{"param_v2":{"param_island":{"bigIslandArea":{}}}}"""
        assertEquals(noSlot, HyperPetPill.withPet(noSlot, uri))
        assertEquals("not json", HyperPetPill.withPet("not json", uri))
    }
}
