package com.d4viddf.hyperbridge.service

import com.d4viddf.hyperbridge.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** What the buttons on the pet's card do, and the clip each one plays. */
enum class HyperPetAction(val clip: Int, val title: Int) {
    FEED(R.raw.hyper_pet_eat, R.string.hyper_pet_feed),
    PET(R.raw.hyper_pet_petted, R.string.hyper_pet_pet),
    BATHE(R.raw.hyper_pet_bath, R.string.hyper_pet_bathe),
    WALK(R.raw.hyper_pet_walk, R.string.hyper_pet_walk),
}

/** When each need was last met, in epoch millis. This is all of the pet that is saved. */
data class HyperPetNeeds(val fedAt: Long, val lovedAt: Long, val bathedAt: Long)

/** How the pet is doing, each from 0 (empty) to 1 (full). */
data class HyperPetLevels(val food: Float, val happiness: Float, val cleanness: Float, val energy: Float) {
    /** The needs the buttons take care of; energy looks after itself. */
    val lowest: Float get() = minOf(food, happiness, cleanness)
}

/**
 * The pet's state and what it does next.
 *
 * Food, happiness and cleanness each run from full to empty over [NEED_LASTS_MINUTES] of the dog's
 * waking day (06:00 to 22:00, nothing runs down while it sleeps), and one tap fills one up again.
 * They turn low at half, after five and a half hours, so three visits a day keep it content. Energy follows the
 * clock: it runs down through the day and sleep fills it.
 *
 * Left alone the pet follows the clock ([HyperPetPill.clipAt]), moping every other clip while a
 * need is low. An action asked for from its card plays first, whatever the hour: at night that
 * wakes the dog, which then falls asleep again, since the clip before was not a sleeping one.
 */
class HyperPetBehavior(needs: HyperPetNeeds, private val zone: ZoneId = ZoneId.systemDefault()) {
    var needs: HyperPetNeeds = needs
        private set
    private var previous: Int? = null
    private var requested: HyperPetAction? = null

    fun request(action: HyperPetAction, now: Long) {
        requested = action
        needs = when (action) {
            HyperPetAction.FEED -> needs.copy(fedAt = now)
            HyperPetAction.PET, HyperPetAction.WALK -> needs.copy(lovedAt = now)
            HyperPetAction.BATHE -> needs.copy(bathedAt = now)
        }
    }

    fun levels(now: Long): HyperPetLevels {
        fun level(since: Long) = (1f - (awakeMinutes(now) - awakeMinutes(since)).toFloat() / NEED_LASTS_MINUTES).coerceIn(0f, 1f)
        val minute = localTime(now).let { it.hour * 60 + it.minute }
        val energy = if (minute in WAKE_MINUTE until BED_MINUTE) {
            1f - (minute - WAKE_MINUTE).toFloat() / AWAKE_MINUTES_A_DAY
        } else {
            ((minute - BED_MINUTE + DAY_MINUTES) % DAY_MINUTES).toFloat() / (DAY_MINUTES - AWAKE_MINUTES_A_DAY)
        }
        return HyperPetLevels(level(needs.fedAt), level(needs.lovedAt), level(needs.bathedAt), energy)
    }

    /**
     * The one button the card offers: whatever the pet needs most. Happiness is a walk while it
     * has the energy for one and a cuddle when it's tired, which is also what a content dog gets.
     */
    fun suggestedAction(now: Long): HyperPetAction {
        val levels = levels(now)
        val cheerUp = if (levels.energy >= 0.5f) HyperPetAction.WALK else HyperPetAction.PET
        return listOf(
            levels.happiness to cheerUp,
            levels.food to HyperPetAction.FEED,
            levels.cleanness to HyperPetAction.BATHE,
        ).minBy { it.first }.second
    }

    /** The clip to post at [now]. Call it once per post: it remembers what it answered. */
    fun next(now: Long, wokeToday: Boolean, random: Random = Random): Int {
        val byTheClock = HyperPetPill.clipAt(localTime(now).toLocalTime(), previous, wokeToday, random)
        val clip = requested?.clip ?: when {
            byTheClock !in HyperPetPill.DAY_CLIPS -> byTheClock
            levels(now).lowest < LOW && previous != R.raw.hyper_pet_sad -> R.raw.hyper_pet_sad
            else -> byTheClock
        }
        requested = null
        previous = clip
        return clip
    }

    private fun localTime(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    /** The minutes the dog has been awake since the epoch: the clock its needs run on. */
    private fun awakeMinutes(millis: Long): Long {
        val time = localTime(millis)
        val today = (time.hour * 60 + time.minute - WAKE_MINUTE).coerceIn(0, AWAKE_MINUTES_A_DAY)
        return time.toLocalDate().toEpochDay() * AWAKE_MINUTES_A_DAY + today
    }

    companion object {
        const val LOW = 0.5f
        const val NEED_LASTS_MINUTES = 11 * 60
        private const val WAKE_MINUTE = 6 * 60
        private const val BED_MINUTE = 22 * 60
        private const val AWAKE_MINUTES_A_DAY = BED_MINUTE - WAKE_MINUTE
        private const val DAY_MINUTES = 24 * 60
    }
}
