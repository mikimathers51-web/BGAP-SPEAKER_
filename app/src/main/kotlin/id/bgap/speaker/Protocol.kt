package id.bgap.speaker

/** Model state perangkat + parser baris SPP firmware 2.9.26. Tanpa dependensi Android (mudah diuji). */

val EQ_FREQS = listOf("20", "32", "50", "80", "125", "200", "315", "500", "800", "1,2k", "2k", "3,1k", "5k", "8k", "16k")
val MODE_NAMES = listOf(
    "Stereo (1-chip)", "2.1 Dual-chip", "2.1 1-Chip (biamp)", "3.1 (sat+center+sub)",
    "Wireless Sub", "2-way Stereo (bi-amp)", "2-way Mono 1-Chip",
)
val MODE_SHORT = listOf("ST", "2.1 DC", "2.1 1C", "3.1", "WSUB", "2W ST", "2W 1C")
val MODE_ENV = listOf(
    "esp32dev_stereo", "esp32dev_2chip_21", "esp32dev_1chip_21", "esp32dev_2chip_31",
    "esp32dev_wireless_master", "esp32dev_2chip_2way", "esp32dev_1chip_2way",
)

data class Peer(
    val mac: String = "",
    val linked: Boolean = false,
    val fillMs: Int = 0,
    val under: Int = 0,
    val gap: Int = 0,
    val rate: Int = 0,
)

data class Levels(
    val l: Float = -120f,
    val r: Float = -120f,
    val l2: Float = -120f,
    val r2: Float = -120f,
    val w: Float = -120f,
    val at: Long = 0L,
)

data class Voicing(val on: Boolean = false, val hpf: Int = 27, val pkHz: Int = 32, val db10: Int = 0, val q10: Int = 15)

data class Device(
    val mode: Int = 2,
    val master: Int = 78,
    val sat: Int = 90,
    val ctr: Int = 100,
    val sub: Int = 100,
    val xhp: Int = 120,
    val xlp: Int = 120,
    val twayOn: Boolean = false,
    val twayHz: Int = 2500,
    val gain: Int = 18,
    val amp: String = "play",
    val eqOn: Boolean = true,
    val mixer: String = "stereo",
    val mixLL: Float = 1f,
    val mixLR: Float = 0f,
    val mixRL: Float = 0f,
    val mixRR: Float = 1f,
    val clipGain: Int = 0,
    val clipL: Int = 30,
    val clipR: Int = 30,
    val inv: Boolean = false,
    val standbyMin: Int = 0,
    val subloud: Boolean = false,
    val slMaxDb10: Int = 60,
    val slLo: Int = 30,
    val slHi: Int = 70,
    val slPhone: Boolean = true,
    val pbass: Boolean = false,
    val pbAmt: Int = 40,
    val pbFc: Int = 80,
    val eqL: List<Int> = List(15) { 0 },
    val eqR: List<Int> = List(15) { 0 },
    val eqC: List<Int> = List(15) { 0 },
    val eqS: List<Int> = List(15) { 0 },
    val peers: List<Peer> = emptyList(),
    val found: List<String> = emptyList(),
    val wTrim: Int = 0,
    val wExtra: Int = 100,
    val wLat: Int = 70,
    val wCh: Int = 0,
    val wErr: String = "-",
    val wNomem: Int = 0,
    val wAckOk: Int = 0,
    val wAckFail: Int = 0,
    val wTxf: Int = 0,
    val wPkt: Int = 0,
    val voicing: Voicing = Voicing(),
    val radio: List<String> = emptyList(),
    val fw: String = "",
    val caps: Set<String> = emptySet(),
    val fault: String = "",
    val levels: Levels = Levels(),
)

object Protocol {
    private val KV = Regex("""(\w+)=(\S*)""")
    private val RE_MODE = Regex("""^MODE (\d+)""")
    private val RE_EQ = Regex("""^EQ ([lr])=([-\d,]+)""")
    private val RE_EQCS = Regex("""^EQ([CS]) ([-\d,]+)""")

    fun kv(line: String): Map<String, String> =
        KV.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }

    fun tag(line: String): String = line.trim().substringBefore(' ')

    private fun Map<String, String>.i(k: String, d: Int): Int = this[k]?.toIntOrNull() ?: d

    private fun parseEq(s: String): List<Int>? {
        val a = s.split(',').mapNotNull { it.toIntOrNull() }
        return if (a.size == 15) a else null
    }

    private fun csv(m: Map<String, String>, k: String): List<String> = (m[k] ?: "").split(',')

    /** Terapkan satu baris dari firmware ke state; baris tak dikenal tidak mengubah apa pun. */
    fun apply(d: Device, raw: String, now: Long = System.currentTimeMillis()): Device {
        val line = raw.trim()
        val m = kv(line)
        return when (tag(line)) {
            "MODE" -> d.copy(
                mode = RE_MODE.find(line)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 6) ?: d.mode,
                master = m.i("master", d.master), sat = m.i("sat", d.sat),
                ctr = m.i("ctr", d.ctr), sub = m.i("sub", d.sub),
                xhp = m.i("xhp", m.i("xover", d.xhp)), xlp = m.i("xlp", m.i("xover", d.xlp)),
            )
            "SUBLOUD" -> d.copy(
                subloud = m["on"] == "1", slMaxDb10 = m.i("db10", d.slMaxDb10),
                slLo = m.i("lo", d.slLo), slHi = m.i("hi", d.slHi), slPhone = m["ph"] == "1",
            )
            "PBASS" -> d.copy(pbass = m["on"] == "1", pbAmt = m.i("amt", d.pbAmt), pbFc = m.i("fc", d.pbFc))
            "TWAY" -> d.copy(twayOn = m["on"] == "1", twayHz = m.i("hz", d.twayHz))
            "STATE" -> {
                val ll = m["ll"]?.toFloatOrNull(); val lr = m["lr"]?.toFloatOrNull()
                val rl = m["rl"]?.toFloatOrNull(); val rr = m["rr"]?.toFloatOrNull()
                val mix = when {
                    ll == 1f && lr == 0f && rl == 0f && rr == 1f -> "stereo"
                    ll == 0.5f && lr == 0.5f && rl == 0.5f && rr == 0.5f -> "mono"
                    else -> d.mixer
                }
                d.copy(
                    gain = m.i("gain", d.gain), amp = m["amp"] ?: d.amp, eqOn = m["eq"] == "1", mixer = mix,
                    mixLL = ll ?: d.mixLL, mixLR = lr ?: d.mixLR, mixRL = rl ?: d.mixRL, mixRR = rr ?: d.mixRR,
                )
            }
            "CLIP" -> d.copy(clipGain = m.i("gain", d.clipGain), clipL = m.i("ml", d.clipL), clipR = m.i("mr", d.clipR))
            "FX" -> d.copy(inv = m["inv"] == "1", standbyMin = m.i("stby", d.standbyMin))
            "EQ" -> {
                val r = RE_EQ.find(line)
                val a = r?.let { parseEq(it.groupValues[2]) }
                if (r == null || a == null) d
                else if (r.groupValues[1] == "l") d.copy(eqL = a)
                else d.copy(eqR = a)
            }
            "EQC", "EQS" -> {
                val r = RE_EQCS.find(line)
                val a = r?.let { parseEq(it.groupValues[2]) }
                if (r == null || a == null) d
                else if (r.groupValues[1] == "C") d.copy(eqC = a)
                else d.copy(eqS = a)
            }
            "WSUB" -> {
                val macs = m["peers"]?.takeIf { it != "none" }?.split(',') ?: emptyList()
                val peers = macs.mapIndexed { i, mac ->
                    Peer(
                        mac = mac,
                        linked = csv(m, "links").getOrNull(i) == "1",
                        fillMs = csv(m, "fill").getOrNull(i)?.toIntOrNull() ?: 0,
                        under = csv(m, "und").getOrNull(i)?.toIntOrNull() ?: 0,
                        gap = csv(m, "gap").getOrNull(i)?.toIntOrNull() ?: 0,
                        rate = csv(m, "rate").getOrNull(i)?.toIntOrNull() ?: 0,
                    )
                }
                d.copy(
                    peers = peers,
                    found = m["found"]?.takeIf { it != "none" }?.split(',') ?: emptyList(),
                    wTrim = m.i("trim", d.wTrim), wLat = m.i("lat", d.wLat), wCh = m.i("ch", d.wCh),
                    wErr = m["err"] ?: "-", wNomem = m.i("nomem", 0), wAckOk = m.i("ackok", 0),
                    wAckFail = m.i("ackfail", 0), wTxf = m.i("txf", 0),
                )
            }
            "WSUBT" -> d.copy(wExtra = m.i("extra_max", d.wExtra))
            "WSUBD" -> d.copy(wPkt = m.i("pkt", d.wPkt))
            "WSUBV" -> d.copy(
                voicing = Voicing(
                    on = m["on"] == "1", hpf = m.i("hpf", d.voicing.hpf), pkHz = m.i("pk", d.voicing.pkHz),
                    db10 = m.i("db", d.voicing.db10), q10 = m.i("q", d.voicing.q10),
                ),
            )
            "WSUBS" -> d.copy(radio = (d.radio + line.take(200)).takeLast(20))
            "VER" -> d.copy(fw = m["fw"] ?: d.fw)
            "CAPS" -> d.copy(caps = line.split(' ').drop(1).filter { it.isNotBlank() }.toSet())
            "FAULT" -> d.copy(fault = line)
            "LVL" -> d.copy(levels = d.levels.copy(l = m.i("l", -1200) / 10f, r = m.i("r", -1200) / 10f, at = now))
            "LVL2" -> d.copy(levels = d.levels.copy(l2 = m.i("l", -1200) / 10f, r2 = m.i("r", -1200) / 10f, at = now))
            "WLVL" -> {
                val best = m.values.mapNotNull { it.toIntOrNull() }.maxOrNull()
                d.copy(levels = d.levels.copy(w = if (best == null) -120f else best / 10f, at = now))
            }
            else -> d
        }
    }

    /** Baris yang cukup berisik sehingga tidak perlu masuk log terminal. */
    private val QUIET = setOf(
        "MODE", "STATE", "FX", "PBASS", "TWAY", "SUBLOUD", "EQ", "EQC", "EQS", "WSUB", "WSUBS",
        "WSUBV", "WSUBT", "WSUBD", "VER", "DAC", "MOD", "CLIP", "SAVED", "LVL", "LVL2", "WLVL",
    )

    fun isQuiet(line: String) = tag(line) in QUIET
}

/** Pembangun perintah — satu-satunya tempat string perintah dibuat. */
object Cmd {
    fun clamp(v: Int, a: Int, b: Int) = v.coerceIn(a, b)
    fun mode(m: Int) = "mode ${clamp(m, 0, 6)}"
    fun master(v: Int) = "master ${clamp(v, 0, 100)}"
    fun satvol(v: Int) = "satvol ${clamp(v, 0, 100)}"
    fun subvol(v: Int) = "subvol ${clamp(v, 0, 100)}"
    fun ctr(v: Int) = "ctr ${clamp(v, 0, 100)}"
    fun xoverHp(v: Int, max: Int) = "xover_hp ${clamp(v, 60, max)}"
    fun xoverLp(v: Int, max: Int) = "xover_lp ${clamp(v, 60, max)}"
    fun tway(on: Boolean) = "tway ${if (on) 1 else 0}"
    fun twayHz(v: Int) = "tway_hz ${clamp(v, 1000, 5000)}"
    fun gain(v: Int) = "gain ${clamp(v, 0, 31)}"
    fun amp(s: String) = "ampstate $s"
    fun subinv(on: Boolean) = "subinv ${if (on) 1 else 0}"
    fun standby(min: Int) = "standby ${clamp(min, 0, 240)}"
    fun mixmode(s: String) = "mixmode $s"
    /** Gain mixer −1..2; Locale.US supaya desimal selalu titik (bukan koma). */
    fun mixer(ch: String, v: Float) = "mixer $ch ${String.format(java.util.Locale.US, "%.2f", v.coerceIn(-1f, 2f))}"
    fun clipset(g: Int, l: Int, r: Int) = "clipset ${clamp(g, -300, 0)} ${clamp(l, 0, 300)} ${clamp(r, 0, 300)}"
    fun eq(on: Boolean) = "eq ${if (on) "on" else "off"}"
    fun eqband(ch: Char, band: Int, gain: Int) = "eqband $ch ${clamp(band, 0, 14)} ${clamp(gain, -15, 15)}"
    fun eqc(ch: Char, band: Int, gain: Int) = "eqc $ch ${clamp(band, 3, 14)} ${clamp(gain, -15, 15)}"
    fun subloud(on: Boolean) = "subloud ${if (on) 1 else 0}"
    fun subloudPhone(on: Boolean) = "subloud_phone ${if (on) 1 else 0}"
    fun subloudCfg(db10: Int, lo: Int, hi: Int): String {
        val l = clamp(lo, 0, 99)
        return "subloud_cfg ${clamp(db10, 0, 60)} $l ${clamp(hi, l + 1, 100)}"
    }
    fun pbass(on: Boolean) = "pbass ${if (on) 1 else 0}"
    fun pbassCfg(amt: Int, fc: Int) = "pbass_cfg ${clamp(amt, 0, 100)} ${clamp(fc, 40, 150)}"
    fun wsubTrim(ms: Int) = "wsub_trim ${clamp(ms, -60, 100)}"
    fun wsubExtra(ms: Int) = "wsub_extra ${clamp(ms, 0, 200)}"
    fun wsubPair(mac: String) = "wsub_pair $mac"
    fun wsubUnpair(mac: String) = "wsub_unpair $mac"
    fun wsubVoice(v: Voicing) =
        "wsub_voice ${if (v.on) 1 else 0} ${clamp(v.hpf, 20, 120)} ${clamp(v.pkHz, 30, 150)} " +
            "${clamp(v.db10, -60, 90)} ${clamp(v.q10, 5, 30)} 0 100"

    const val STATUS = "status"
    const val DUMP = "dump"
    const val CLIPGET = "clipget"
    const val CAPS = "caps"
    const val PING = "ping"
    const val FAULT = "fault"
    const val SAVE = "save"
    const val ERASE = "erase"
    const val REBOOT = "reboot"
    const val WSUB_SCAN = "wsub_scan"
    const val WSUB_UNPAIR_ALL = "wsub_unpair_all"
    const val WSUB_DIAG_RESET = "wsub_diag_reset"
    const val LVL_ON = "lvl on"
    const val LVL = "lvl"
    const val LVL2 = "lvl2"
    const val WLVL = "wlvl"
}

/** Aturan yang mencerminkan firmware (is21(), mode lock, dll). */
object Rules {
    fun xoverMax(mode: Int) = if (mode == 2) 300 else 150
    fun hasXover(mode: Int) = mode in 0..4
    fun hasTway(mode: Int) = mode == 2 || mode >= 5
    fun hasSub(mode: Int) = mode != 0
    fun hasSubloud(mode: Int) = mode in 1..4 || mode == 6
    fun mixerLocked(mode: Int) = mode == 2 || mode == 5 || mode == 6
    fun eqMinBand(mode: Int) = if (mode == 0) 0 else 3
    fun eqToggleable(mode: Int) = mode == 0
    fun hasCenter(mode: Int) = mode == 3
}

/** Peta kanal meter per mode: (label, sumber). */
enum class Src { L, R, L2, R2, W }

fun meterChannels(mode: Int): List<Pair<String, Src>> = when (mode) {
    0 -> listOf("L" to Src.L, "R" to Src.R)
    1 -> listOf("SAT-L" to Src.L, "SAT-R" to Src.R, "SUB" to Src.L2)
    2 -> listOf("SAT" to Src.L, "SUB" to Src.R)
    3 -> listOf("SAT-L" to Src.L, "SAT-R" to Src.R, "CENTER" to Src.L2, "SUB" to Src.R2)
    4 -> listOf("SAT" to Src.L, "WSUB" to Src.W)
    5 -> listOf("TW-L" to Src.L, "TW-R" to Src.R, "WF-L" to Src.L2, "WF-R" to Src.R2)
    else -> listOf("TW" to Src.L, "WF" to Src.R)
}

fun Levels.db(s: Src): Float = when (s) { Src.L -> l; Src.R -> r; Src.L2 -> l2; Src.R2 -> r2; Src.W -> w }

/**
 * Lebar stereo (0..1) bila mixer berbentuk mid/side: LL=RR=1+w, LR=RL=−w. Selain itu null ("kustom").
 */
fun Device.stereoWidth(): Float? {
    val w = -mixLR
    val ok = kotlin.math.abs(mixLL - mixRR) < 0.01f && kotlin.math.abs(mixLR - mixRL) < 0.01f &&
        mixLR <= 0.001f && w <= 1.001f && kotlin.math.abs(mixLL - (1f + w)) < 0.02f
    return if (ok) w.coerceAtLeast(0f) else null
}
