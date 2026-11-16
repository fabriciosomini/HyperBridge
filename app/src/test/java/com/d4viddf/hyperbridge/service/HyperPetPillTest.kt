package com.d4viddf.hyperbridge.service

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun theDogOnlyDozesOffAtNight() {
        val sleepy = com.d4viddf.hyperbridge.R.raw.hyper_pet_sleepy
        for (hour in listOf(22, 23, 0, 3, 5)) assertTrue("$hour", sleepy in HyperPetPill.clipsAt(hour))
        for (hour in listOf(6, 7, 12, 18, 21)) assertTrue("$hour", sleepy !in HyperPetPill.clipsAt(hour))
        assertEquals(6, HyperPetPill.clipsAt(12).toSet().size)
        assertEquals(7, HyperPetPill.clipsAt(23).toSet().size)
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
