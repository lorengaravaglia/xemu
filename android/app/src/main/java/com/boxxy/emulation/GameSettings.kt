package com.boxxy.emulation

import android.content.Context
import android.net.Uri
import com.boxxy.library.XisoReader

/**
 * Per-game overrides of launch-time settings (OPEN-WORK A4).
 *
 * Each game may override any [Spec]; an absent entry means "use the global
 * setting", so changing a global setting still reaches every game that has
 * not overridden it.  Games are keyed by the disc's title ID -- stable across
 * renames and across launchers -- falling back to the filename-derived game
 * ID for images the XISO reader cannot parse.
 *
 * Values are stored as strings in their own preferences file, under
 * "<gameKey>/<prefKey>", where prefKey is the global setting's key in
 * main_prefs.
 */
object GameSettings {
    private const val PREFS = "game_settings"

    /** Brightness choices, in percent; 100 leaves the picture unchanged. */
    val BRIGHTNESS_STEPS = listOf(85, 100, 115, 130, 150, 175, 200)

    /** One overridable setting: its main_prefs key, label and choices. */
    data class Spec(
        val prefKey: String,
        val label: String,
        val section: String,
        /** (stored value, label) pairs. */
        val choices: List<Pair<String, String>>,
        /** The global setting, read the same way the settings screens do. */
        val global: (android.content.SharedPreferences) -> String,
        /** Built-in per-title defaults, by game key, that win over the
         *  global setting (but not over the user's own override). */
        val titleDefaults: Map<String, String> = emptyMap(),
    )

    val SPECS = listOf(
        Spec("surface_scale", "Resolution scale", "Graphics",
             listOf("1" to "1× (native)", "2" to "2×", "3" to "3×"),
             { it.getInt("surface_scale", 1).toString() }),
        Spec("aspect_ratio", "Aspect ratio", "Graphics",
             listOf("0" to "Native (640×480 integer scale)", "1" to "Auto (matches the game)",
                    "2" to "4:3 (pillarbox / letterbox)", "3" to "16:9 (stretch to fill)"),
             { it.getInt("aspect_ratio", 1).toString() }),
        /* The Xbox's EEPROM widescreen flag: games that support it render
         * true 16:9, and Auto aspect then fills the screen. */
        Spec("brightness", "Brightness", "Graphics",
             BRIGHTNESS_STEPS.map { it.toString() to "$it%" },
             { it.getInt("brightness", 100).toString() }),
        Spec("xbox_widescreen", "Widescreen (16:9)", "Graphics",
             listOf("false" to "Off", "true" to "On"),
             { it.getBoolean("xbox_widescreen", false).toString() }),
        Spec("accurate_mem_ordering", "Accurate memory ordering", "Compatibility",
             listOf("false" to "Off", "true" to "On"),
             { it.getBoolean("accurate_mem_ordering", false).toString() }),
        Spec("fast_ordered_loads", "Fast ordered loads", "Performance",
             listOf("true" to "On", "false" to "Off"),
             { it.getBoolean("fast_ordered_loads", true).toString() }),
        Spec("fast_boot", "Fast boot", "Performance",
             listOf("true" to "On", "false" to "Off"),
             { it.getBoolean("fast_boot", true).toString() }),
        Spec("async_shaders", "Compile shaders in background", "Performance",
             listOf("true" to "On", "false" to "Off"),
             { it.getBoolean("async_shaders", true).toString() }),
        /* C4: games that switch the x87 rounding mode (FINDINGS AK) --
         * Halo and Tony Hawk's Pro Skater 2x, millions of times a run. */
        Spec("x87_rounding", "Accurate x87 rounding", "Compatibility",
             listOf("false" to "Off", "true" to "On"),
             { it.getBoolean("x87_rounding", false).toString() },
             titleDefaults = mapOf("t4d530004" to "true", "t41560001" to "true")),
    )

    /** The built-in default for [gameKey], if this title has one. */
    fun titleDefault(gameKey: String, prefKey: String): String? =
        SPECS.firstOrNull { it.prefKey == prefKey }?.titleDefaults?.get(gameKey)

    /** The key for a game whose title ID is known (the library has it). */
    fun keyFor(titleId: Int?, isoUri: String): String =
        titleId?.let { "t%08x".format(it) } ?: "g_" + QuickResume.gameIdFor(isoUri)

    /** The key for a game known only by its URI: reads the title ID. */
    fun keyFor(context: Context, isoUri: String): String =
        keyFor(XisoReader.readTitleId(context, Uri.parse(isoUri)), isoUri)

    fun override(context: Context, gameKey: String, prefKey: String): String? =
        prefs(context).getString("$gameKey/$prefKey", null)

    fun setOverride(context: Context, gameKey: String, prefKey: String, value: String?) {
        prefs(context).edit().apply {
            if (value == null) remove("$gameKey/$prefKey") else putString("$gameKey/$prefKey", value)
        }.apply()
    }

    fun clearAll(context: Context, gameKey: String) {
        val p = prefs(context)
        p.edit().apply {
            p.all.keys.filter { it.startsWith("$gameKey/") }.forEach { remove(it) }
        }.apply()
    }

    fun overrideCount(context: Context, gameKey: String): Int =
        prefs(context).all.keys.count { it.startsWith("$gameKey/") }

    fun effectiveBoolean(context: Context, gameKey: String, prefKey: String, default: Boolean): Boolean =
        override(context, gameKey, prefKey)?.toBooleanStrictOrNull()
            ?: titleDefault(gameKey, prefKey)?.toBooleanStrictOrNull()
            ?: main(context).getBoolean(prefKey, default)

    fun effectiveInt(context: Context, gameKey: String, prefKey: String, default: Int): Int =
        override(context, gameKey, prefKey)?.toIntOrNull()
            ?: titleDefault(gameKey, prefKey)?.toIntOrNull()
            ?: main(context).getInt(prefKey, default)

    fun main(context: Context) =
        context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
