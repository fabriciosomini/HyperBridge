package com.d4viddf.hyperbridge.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.d4viddf.hyperbridge.R
import java.time.LocalTime
import kotlin.random.Random

/**
 * The Hyper Pet: a looping animation in the permanent island's left slot.
 *
 * HyperOS's island plugin picks that slot's renderer from picInfo.type (1 bitmap, 2 Lottie,
 * 6 MP4). Lottie only loads the plugin's own assets by name, but the MP4 path hands an unknown
 * name straight to Uri.parse, so an android.resource URI to our own raw video plays and loops.
 * That is why the pet ships as an MP4 rendered from its Lottie source.
 *
 * Every dispatch of the island restarts the video, so the pet is posted again each time a clip
 * ends ([durationMs]), with another clip. The daytime ones start and end on the
 * same idle pose, with their moment in the middle, so the change of clip can't be seen. Keep
 * that shape (tools/hyper-pet) when adding clips.
 */
object HyperPetPill {
    private const val PIC_TYPE_MP4 = 6

    /** How long [clip] plays before the next one is posted. tools/hyper-pet cuts them to this. */
    fun durationMs(clip: Int): Long = when (clip) {
        R.raw.hyper_pet_sleepy, R.raw.hyper_pet_asleep, R.raw.hyper_pet_wake_up, R.raw.hyper_pet_drowsy -> 15_000L
        R.raw.hyper_pet_look_around, R.raw.hyper_pet_butterfly -> 12_000L
        else -> 9_000L
    }

    val DAY_CLIPS = listOf(
        R.raw.hyper_pet_look_around,
        R.raw.hyper_pet_sniff_tilt,
        R.raw.hyper_pet_happy,
        R.raw.hyper_pet_shake,
        R.raw.hyper_pet_butterfly,
        R.raw.hyper_pet_bone,
    )

    private val BEDTIME = LocalTime.of(22, 0)
    private val DROWSY_TIME = LocalTime.of(21, 45)
    private val WAKE_TIME = LocalTime.of(6, 0)

    /**
     * The clip to play at [time] after [previous]. From 22:00 to 06:00 the dog sleeps: it falls
     * asleep once (sleepy ends asleep) and then stays asleep. The first clip of the day wakes it
     * up ([wokeToday] says whether that has played), which is the first time the screen is on in
     * the morning. It yawns its way through the quarter hour before bed, and the rest of the day
     * it's a random clip.
     */
    fun clipAt(time: LocalTime, previous: Int?, wokeToday: Boolean, random: Random = Random): Int {
        val wasAsleep = previous == R.raw.hyper_pet_sleepy || previous == R.raw.hyper_pet_asleep
        return when {
            time >= BEDTIME || time < WAKE_TIME -> if (wasAsleep) R.raw.hyper_pet_asleep else R.raw.hyper_pet_sleepy
            wasAsleep || !wokeToday -> R.raw.hyper_pet_wake_up
            time >= DROWSY_TIME -> R.raw.hyper_pet_drowsy
            else -> nextClip(DAY_CLIPS, previous, random)
        }
    }

    /** A random clip, never the one that just played. */
    fun nextClip(clips: List<Int>, previous: Int?, random: Random = Random): Int =
        (clips - listOfNotNull(previous).toSet()).ifEmpty { clips }.random(random)

    fun videoUri(packageName: String, rawResId: Int): String = "android.resource://$packageName/$rawResId"

    /**
     * Points the big island's left slot at [videoUri]. The toolkit prefixes every pic with
     * "miui.focus.pic_", which the video lookup doesn't want, so the picInfo is written here.
     * Works on both of the toolkit's JSONs. Returns [json] untouched when it has no left slot to put the pet in.
     */
    fun withPet(json: String, videoUri: String): String {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return json
        // The island sits under param_v2, or at the top of a custom view's JSON.
        val left = (root.getAsJsonObject("param_v2") ?: root)
            .getAsJsonObject("param_island")
            ?.getAsJsonObject("bigIslandArea")
            ?.getAsJsonObject("imageTextInfoLeft")
            ?: return json
        left.add("picInfo", JsonObject().apply {
            addProperty("type", PIC_TYPE_MP4)
            addProperty("pic", videoUri)
            addProperty("loop", true)
            addProperty("autoplay", true)
            addProperty("number", 0)
        })
        return Gson().toJson(root)
    }
}
