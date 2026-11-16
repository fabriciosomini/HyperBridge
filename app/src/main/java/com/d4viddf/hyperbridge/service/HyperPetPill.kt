package com.d4viddf.hyperbridge.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.d4viddf.hyperbridge.R
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
 * ends, with another clip. Every clip lasts [CLIP_MS] and starts and ends on the same idle pose,
 * with its moment in the middle, so the change of clip can't be seen. Keep that shape
 * (tools/hyper-pet) when adding clips.
 */
object HyperPetPill {
    private const val PIC_TYPE_MP4 = 6
    const val CLIP_MS = 15_000L

    private val DAY_CLIPS = listOf(
        R.raw.hyper_pet_look_around,
        R.raw.hyper_pet_sniff_tilt,
        R.raw.hyper_pet_happy,
        R.raw.hyper_pet_shake,
        R.raw.hyper_pet_butterfly,
        R.raw.hyper_pet_bone,
    )
    private val NIGHT_CLIPS = DAY_CLIPS + R.raw.hyper_pet_sleepy

    /** The clips to pick from at [hourOfDay]: the dog only dozes off from 22:00 to 06:00. */
    fun clipsAt(hourOfDay: Int): List<Int> = if (hourOfDay >= 22 || hourOfDay < 6) NIGHT_CLIPS else DAY_CLIPS

    /** A random clip, never the one that just played. */
    fun nextClip(clips: List<Int>, previous: Int?, random: Random = Random): Int =
        (clips - listOfNotNull(previous).toSet()).ifEmpty { clips }.random(random)

    fun videoUri(packageName: String, rawResId: Int): String = "android.resource://$packageName/$rawResId"

    /**
     * Points the big island's left slot at [videoUri]. The toolkit prefixes every pic with
     * "miui.focus.pic_", which the video lookup doesn't want, so the picInfo is written here.
     * Returns [json] untouched when it has no left slot to put the pet in.
     */
    fun withPet(json: String, videoUri: String): String {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return json
        val left = root.getAsJsonObject("param_v2")
            ?.getAsJsonObject("param_island")
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
