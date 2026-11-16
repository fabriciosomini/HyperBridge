package com.d4viddf.hyperbridge.service

import com.d4viddf.hyperbridge.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class HyperPetBehaviorTest {

    private fun at(hour: Int, minute: Int = 0, day: Int = 10) =
        LocalDateTime.of(2026, 11, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun pet(caredForAt: Long) =
        HyperPetBehavior(HyperPetNeeds(caredForAt, caredForAt, caredForAt), ZoneOffset.UTC)

    private val actionClips = HyperPetAction.entries.map { it.clip }

    @Test
    fun anActionPlaysNextAndOnlyOnce() {
        val pet = pet(at(12))
        pet.request(HyperPetAction.BATHE, at(12))

        assertEquals(R.raw.hyper_pet_bath, pet.next(at(12), wokeToday = true))
        repeat(20) { assertTrue(pet.next(at(12), wokeToday = true) !in actionClips) }
    }

    @Test
    fun theLastActionAskedForWins() {
        val pet = pet(at(12))
        pet.request(HyperPetAction.FEED, at(12))
        pet.request(HyperPetAction.WALK, at(12))

        assertEquals(R.raw.hyper_pet_walk, pet.next(at(12), wokeToday = true))
    }

    @Test
    fun anActionAtNightWakesTheDogAndItFallsAsleepAgain() {
        val pet = pet(at(21))
        assertEquals(R.raw.hyper_pet_sleepy, pet.next(at(23), wokeToday = true))
        assertEquals(R.raw.hyper_pet_asleep, pet.next(at(23), wokeToday = true))

        pet.request(HyperPetAction.PET, at(23))

        assertEquals(R.raw.hyper_pet_petted, pet.next(at(23), wokeToday = true))
        assertEquals(R.raw.hyper_pet_sleepy, pet.next(at(23), wokeToday = true))
        assertEquals(R.raw.hyper_pet_asleep, pet.next(at(23), wokeToday = true))
    }

    @Test
    fun everyActionHasItsOwnClip() {
        assertEquals(HyperPetAction.entries.size, actionClips.toSet().size)
        actionClips.forEach { assertEquals(9_000L, HyperPetPill.durationMs(it)) }
    }

    @Test
    fun needsRunDownOverElevenWakingHours() {
        val pet = pet(at(8))

        assertEquals(1f, pet.levels(at(8)).lowest, 0.001f)
        assertEquals(0.5f, pet.levels(at(13, 30)).food, 0.001f)
        assertEquals(0f, pet.levels(at(19)).cleanness, 0.001f)
        assertEquals(0f, pet.levels(at(21)).happiness, 0.001f)
    }

    @Test
    fun nothingRunsDownWhileTheDogSleeps() {
        val pet = pet(at(21, day = 10))

        // An hour awake before bed, an hour after waking: two of the eleven.
        assertEquals(1f - 2f / 11f, pet.levels(at(7, day = 11)).food, 0.001f)
        assertEquals(pet.levels(at(22, day = 10)), pet.levels(at(6, day = 11)).copy(energy = 0f))
    }

    @Test
    fun eachButtonFillsItsOwnNeed() {
        val pet = pet(at(8))
        val now = at(15)

        pet.request(HyperPetAction.FEED, now)
        assertEquals(1f, pet.levels(now).food, 0.001f)
        assertTrue(pet.levels(now).happiness < HyperPetBehavior.LOW)

        pet.request(HyperPetAction.WALK, now)
        assertEquals(1f, pet.levels(now).happiness, 0.001f)
        assertTrue(pet.levels(now).cleanness < HyperPetBehavior.LOW)

        pet.request(HyperPetAction.BATHE, now)
        assertEquals(1f, pet.levels(now).lowest, 0.001f)

        assertEquals(1f, pet(at(8)).apply { request(HyperPetAction.PET, now) }.levels(now).happiness, 0.001f)
    }

    @Test
    fun threeVisitsADayKeepItFromEverBeingSad() {
        val pet = pet(at(7))
        for (visit in listOf(at(7), at(12), at(17))) HyperPetAction.entries.forEach { pet.request(it, visit) }
        pet.next(at(17), wokeToday = true)

        for (hour in 7 until 22) {
            val careAt = listOf(at(7), at(12), at(17)).last { it <= at(hour) }
            val day = pet(careAt)
            assertTrue("$hour", day.levels(at(hour, 59)).lowest >= HyperPetBehavior.LOW)
        }
    }

    @Test
    fun theCardOffersWhatThePetNeedsMost() {
        val now = at(13)
        assertEquals(HyperPetAction.FEED, HyperPetBehavior(HyperPetNeeds(at(7), at(12), at(12)), ZoneOffset.UTC).suggestedAction(now))
        assertEquals(HyperPetAction.BATHE, HyperPetBehavior(HyperPetNeeds(at(12), at(12), at(7)), ZoneOffset.UTC).suggestedAction(now))
        assertEquals(HyperPetAction.WALK, HyperPetBehavior(HyperPetNeeds(at(12), at(7), at(12)), ZoneOffset.UTC).suggestedAction(now))
        // Too tired for a walk in the evening.
        assertEquals(HyperPetAction.PET, HyperPetBehavior(HyperPetNeeds(at(18), at(12), at(18)), ZoneOffset.UTC).suggestedAction(at(19)))
        // Nothing is needed: just some company.
        assertEquals(HyperPetAction.WALK, pet(now).suggestedAction(now))
    }

    @Test
    fun theOfferMovesOnOnceANeedIsMet() {
        val now = at(15)
        val pet = HyperPetBehavior(HyperPetNeeds(at(7), at(8), at(9)), ZoneOffset.UTC)

        assertEquals(HyperPetAction.FEED, pet.suggestedAction(now))
        pet.request(HyperPetAction.FEED, now)
        assertEquals(HyperPetAction.PET, pet.suggestedAction(now))
        pet.request(HyperPetAction.PET, now)
        assertEquals(HyperPetAction.BATHE, pet.suggestedAction(now))
    }

    @Test
    fun aNeglectedDogMopesEveryOtherClip() {
        val pet = pet(at(8))
        val now = at(15)

        val clips = List(6) { pet.next(now, wokeToday = true) }

        assertEquals(listOf(true, false, true, false, true, false), clips.map { it == R.raw.hyper_pet_sad })
    }

    @Test
    fun aSleepingDogDoesNotMope() {
        val pet = pet(at(8))

        assertEquals(R.raw.hyper_pet_sleepy, pet.next(at(23), wokeToday = true))
        assertEquals(R.raw.hyper_pet_asleep, pet.next(at(23), wokeToday = true))
    }

    @Test
    fun energyRunsDownThroughTheDayAndSleepFillsIt() {
        val pet = pet(at(8))

        assertEquals(1f, pet.levels(at(6)).energy, 0.001f)
        assertEquals(0.5f, pet.levels(at(14)).energy, 0.001f)
        assertEquals(0f, pet.levels(at(22)).energy, 0.001f)
        assertEquals(0.5f, pet.levels(at(2)).energy, 0.001f)
    }
}
