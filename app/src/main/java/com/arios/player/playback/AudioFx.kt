package com.arios.player.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import com.arios.player.Arios
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/** A tuned sound profile: dB gain per frequency (interpolated to the device's bands). */
data class EqPreset(val id: String, val nameAr: String, val nameEn: String, val curve: List<Pair<Int, Float>>)

data class EqSettings(
    val enabled: Boolean = false,
    val presetId: String = "flat",
    /** gain in dB for each device band (empty = from preset) */
    val bands: List<Float> = emptyList(),
    val bass: Int = 0,          // 0..1000
    val virtualizer: Int = 0,   // 0..1000
    val loudness: Int = 0,      // 0..1500 mB
)

data class EqBandInfo(val centerHz: Int, val minDb: Float, val maxDb: Float)

/** In-app equalizer + bass boost + 3D surround + loudness, applied to every audio session we play. */
object AudioFx {
    val presets = listOf(
        EqPreset("flat", "مسطّح", "Flat", listOf(60 to 0f, 14000 to 0f)),
        EqPreset("tarab", "طرب", "Tarab", listOf(60 to 2.5f, 230 to 1.5f, 910 to 2.5f, 3600 to 3f, 14000 to 1f)),
        EqPreset("pop", "بوب", "Pop", listOf(60 to -1f, 230 to 2f, 910 to 4f, 3600 to 2f, 14000 to -1f)),
        EqPreset("rock", "روك", "Rock", listOf(60 to 4.5f, 230 to 2f, 910 to -2f, 3600 to 2.5f, 14000 to 4f)),
        EqPreset("hiphop", "هيب هوب", "Hip-hop", listOf(60 to 5.5f, 230 to 3.5f, 910 to 0f, 3600 to 1f, 14000 to 2f)),
        EqPreset("bass", "جهير قوي", "Bass boost", listOf(60 to 7f, 230 to 4f, 910 to 0f, 3600 to 0f, 14000 to 0f)),
        EqPreset("vocal", "صوت بشري", "Vocal / Podcast", listOf(60 to -3f, 230 to -1f, 910 to 3f, 3600 to 4f, 14000 to 1f)),
        EqPreset("classical", "كلاسيكي", "Classical", listOf(60 to 3f, 230 to 1f, 910 to -1f, 3600 to 2f, 14000 to 3.5f)),
        EqPreset("electronic", "إلكتروني", "Electronic", listOf(60 to 5f, 230 to 1f, 910 to -1.5f, 3600 to 2f, 14000 to 5f)),
        EqPreset("night", "ليلي هادئ", "Late night", listOf(60 to -2f, 230 to 0f, 910 to 1f, 3600 to -1.5f, 14000 to -3f)),
        EqPreset("treble", "حدّة عالية", "Treble boost", listOf(60 to 0f, 230 to 0f, 910 to 1f, 3600 to 4f, 14000 to 6f)),
    )

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<EqSettings> = _settings
    private val _bands = MutableStateFlow<List<EqBandInfo>>(emptyList())
    /** Band layout of this device (known once a session is open). */
    val bands: StateFlow<List<EqBandInfo>> = _bands

    private class Chain(val eq: Equalizer?, val bass: BassBoost?, val virt: Virtualizer?, val loud: LoudnessEnhancer?) {
        fun release() { runCatching { eq?.release() }; runCatching { bass?.release() }; runCatching { virt?.release() }; runCatching { loud?.release() } }
    }
    private val chains = HashMap<Int, Chain>()

    fun attach(sessionId: Int) {
        if (sessionId == 0 || chains.containsKey(sessionId)) return
        val eq = runCatching { Equalizer(0, sessionId) }.getOrNull()
        val chain = Chain(
            eq,
            runCatching { BassBoost(0, sessionId) }.getOrNull(),
            runCatching { Virtualizer(0, sessionId) }.getOrNull(),
            runCatching { LoudnessEnhancer(sessionId) }.getOrNull(),
        )
        chains[sessionId] = chain
        if (eq != null && _bands.value.isEmpty()) runCatching {
            val range = eq.bandLevelRange
            _bands.value = (0 until eq.numberOfBands).map { b ->
                EqBandInfo(eq.getCenterFreq(b.toShort()) / 1000, range[0] / 100f, range[1] / 100f)
            }
        }
        apply(chain, _settings.value)
    }

    fun detach(sessionId: Int) { chains.remove(sessionId)?.release() }

    fun update(s: EqSettings) {
        _settings.value = s
        chains.values.forEach { apply(it, s) }
        save(s)
    }

    /** dB per device band for [s] (custom bands or the preset curve). */
    fun bandGains(s: EqSettings): List<Float> {
        val info = _bands.value
        if (s.bands.size == info.size && info.isNotEmpty()) return s.bands
        val preset = presets.firstOrNull { it.id == s.presetId } ?: presets[0]
        return info.map { interpolate(preset.curve, it.centerHz) }
    }

    private fun interpolate(curve: List<Pair<Int, Float>>, hz: Int): Float {
        if (hz <= curve.first().first) return curve.first().second
        if (hz >= curve.last().first) return curve.last().second
        for (i in 0 until curve.size - 1) {
            val (f0, g0) = curve[i]; val (f1, g1) = curve[i + 1]
            if (hz in f0..f1) {
                val t = ((ln(hz.toDouble()) - ln(f0.toDouble())) / (ln(f1.toDouble()) - ln(f0.toDouble()))).toFloat()
                return g0 + (g1 - g0) * t
            }
        }
        return 0f
    }

    private fun apply(c: Chain, s: EqSettings) {
        runCatching {
            c.eq?.let { eq ->
                eq.enabled = s.enabled
                if (s.enabled) {
                    val gains = bandGains(s)
                    val range = eq.bandLevelRange
                    gains.forEachIndexed { i, g ->
                        if (i < eq.numberOfBands) eq.setBandLevel(i.toShort(), (g * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                    }
                }
            }
        }
        runCatching { c.bass?.let { it.enabled = s.enabled && s.bass > 0; if (it.strengthSupported) it.setStrength(s.bass.toShort()) } }
        runCatching { c.virt?.let { it.enabled = s.enabled && s.virtualizer > 0; if (it.strengthSupported) it.setStrength(s.virtualizer.toShort()) } }
        runCatching { c.loud?.let { it.enabled = s.enabled && s.loudness > 0; it.setTargetGain(s.loudness) } }
    }

    private fun load(): EqSettings = runCatching {
        val o = JSONObject(Arios.prefs.eqJson)
        EqSettings(
            enabled = o.optBoolean("enabled"), presetId = o.optString("preset", "flat"),
            bands = o.optJSONArray("bands")?.let { a -> List(a.length()) { a.getDouble(it).toFloat() } } ?: emptyList(),
            bass = o.optInt("bass"), virtualizer = o.optInt("virt"), loudness = o.optInt("loud"),
        )
    }.getOrDefault(EqSettings())

    private fun save(s: EqSettings) {
        Arios.prefs.eqJson = JSONObject().apply {
            put("enabled", s.enabled); put("preset", s.presetId); put("bands", JSONArray(s.bands.map { it.toDouble() }))
            put("bass", s.bass); put("virt", s.virtualizer); put("loud", s.loudness)
        }.toString()
    }
}
