package id.bgap.speaker

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Conn { Idle, Connecting, Connected }

data class Bonded(val name: String, val mac: String)

data class UiState(
    val conn: Conn = Conn.Idle,
    val devName: String = "",
    val dev: Device = Device(),
    val synced: Boolean = false,
    val bonded: List<Bonded> = emptyList(),
    val log: List<String> = emptyList(),
    val message: String? = null,
    val theme: Int = 0,
)

class BgapViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("bgap", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _ui = MutableStateFlow(UiState(theme = prefs.getInt("theme", 0)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var link: SppLink? = null
    @Volatile private var holdUntil = 0L
    private val pending = LinkedHashMap<String, String>()
    private var debounceJob: Job? = null
    private var resyncJob: Job? = null
    private var meterJob: Job? = null
    private var lvlOn = false
    private var autoTried = false
    private var lastNonZeroStandby = 10

    private val held = setOf("MODE", "STATE", "FX", "PBASS", "TWAY", "SUBLOUD", "EQ", "EQC", "EQS", "CLIP")

    // ---------------------------------------------------------------- koneksi

    @SuppressLint("MissingPermission")
    fun refreshBonded() {
        val list = try {
            adapter?.bondedDevices.orEmpty()
                .map { Bonded(it.name ?: "(tanpa nama)", it.address) }
                .sortedWith(compareBy({ !it.name.contains("BGAP", true) }, { it.name }))
        } catch (e: SecurityException) {
            emptyList()
        }
        _ui.update { it.copy(bonded = list) }
    }

    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /** Sambung otomatis ke perangkat terakhir (sekali per proses). */
    fun autoConnect() {
        if (autoTried) return
        autoTried = true
        val mac = prefs.getString("last", null) ?: return
        if (_ui.value.conn == Conn.Idle && bluetoothEnabled) connect(mac)
    }

    fun connect(mac: String) {
        val ad = adapter ?: run { toast("Perangkat tanpa Bluetooth"); return }
        if (_ui.value.conn != Conn.Idle) return
        viewModelScope.launch {
            _ui.update { it.copy(conn = Conn.Connecting, synced = false) }
            val l = SppLink(viewModelScope, ::onLine, ::onClosed)
            val dev = try {
                ad.getRemoteDevice(mac)
            } catch (e: Exception) {
                _ui.update { it.copy(conn = Conn.Idle) }
                toast("MAC tidak valid")
                return@launch
            }
            val name = _ui.value.bonded.firstOrNull { it.mac == mac }?.name ?: mac
            val r = l.connect(dev)
            if (r.isSuccess) {
                link = l
                lvlOn = false
                prefs.edit().putString("last", mac).apply()
                _ui.update { it.copy(conn = Conn.Connected, devName = name) }
                addLog("Tersambung ke $name")
                tx(Cmd.DUMP)
                tx(Cmd.CAPS)
                tx(Cmd.CLIPGET)
            } else {
                _ui.update { it.copy(conn = Conn.Idle) }
                toast("Gagal sambung: ${r.exceptionOrNull()?.message ?: "?"}")
            }
        }
    }

    fun disconnect() {
        link?.close()
        link = null
        onClosed("Diputus")
    }

    private fun onClosed(why: String) {
        link = null
        meterJob?.cancel(); meterJob = null
        debounceJob?.cancel(); pending.clear()
        resyncJob?.cancel()
        _ui.update { it.copy(conn = Conn.Idle, synced = false) }
        addLog(why)
        toast(why)
    }

    override fun onCleared() {
        link?.close()
        super.onCleared()
    }

    // ---------------------------------------------------------------- terima

    private fun onLine(line: String) {
        if (!Protocol.isQuiet(line)) addLog("RX: ${line.take(200)}")
        if (line.startsWith("ERR ")) {
            toast(line.take(60))
            resync(700)
        }
        val tag = Protocol.tag(line)
        if (tag in held && SystemClock.elapsedRealtime() < holdUntil) return
        _ui.update { s ->
            val d = Protocol.apply(s.dev, line)
            if (d.standbyMin > 0) lastNonZeroStandby = d.standbyMin
            val synced = s.synced || line == "OK dump" || line == "OK status"
            s.copy(dev = d, synced = synced)
        }
    }

    // ---------------------------------------------------------------- kirim

    private fun tx(cmd: String, log: Boolean = true) {
        if (link?.send(cmd) == true && log) addLog("TX: $cmd")
    }

    private fun hold(ms: Long = 700) {
        holdUntil = SystemClock.elapsedRealtime() + ms
    }

    private fun resync(ms: Long, full: Boolean = false) {
        resyncJob?.cancel()
        resyncJob = viewModelScope.launch {
            delay(ms)
            holdUntil = 0
            tx(if (full) Cmd.DUMP else Cmd.STATUS)
        }
    }

    /** Kirim langsung (toggle/chip) + ubah state lokal secara optimistis. */
    private fun now(cmd: String, mut: (Device) -> Device) {
        _ui.update { it.copy(dev = mut(it.dev)) }
        hold()
        tx(cmd)
        resync(800)
    }

    /** Untuk slider: perintah dengan kunci sama digabung, dikirim 120 ms setelah geseran terakhir. */
    private fun deb(key: String, cmd: String, mut: (Device) -> Device) {
        _ui.update { it.copy(dev = mut(it.dev)) }
        hold()
        pending[key] = cmd
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(120)
            val cmds = pending.values.toList()
            pending.clear()
            cmds.forEach { tx(it) }
            resync(800)
        }
    }

    fun sendRaw(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) tx(t)
    }

    fun refresh() { tx(Cmd.DUMP); tx(Cmd.CLIPGET) }
    fun readClip() = tx(Cmd.CLIPGET)
    fun readFault() = tx(Cmd.FAULT)
    fun readCaps() = tx(Cmd.CAPS)
    fun ping() = tx(Cmd.PING)
    fun saveToDevice() = tx(Cmd.SAVE)
    fun eraseSaved() = tx(Cmd.ERASE)
    fun reboot() = tx(Cmd.REBOOT)

    // ---------------------------------------------------------------- kontrol

    fun setMode(m: Int) {
        _ui.update { it.copy(dev = it.dev.copy(mode = m)) }
        hold(950)
        tx(Cmd.mode(m))
        resync(950, full = true)
    }

    fun setMaster(v: Int) = deb("master", Cmd.master(v)) { it.copy(master = v) }
    fun setSat(v: Int) = deb("sat", Cmd.satvol(v)) { it.copy(sat = v) }
    fun setSub(v: Int) = deb("sub", Cmd.subvol(v)) { it.copy(sub = v) }
    fun setCtr(v: Int) = deb("ctr", Cmd.ctr(v)) { it.copy(ctr = v) }
    fun setGain(v: Int) = deb("gain", Cmd.gain(v)) { it.copy(gain = v) }
    fun setTwayOn(on: Boolean) = now(Cmd.tway(on)) { it.copy(twayOn = on) }
    fun setTway(v: Int) = deb("tway", Cmd.twayHz(v)) { it.copy(twayHz = v) }

    fun setXhp(v: Int, lock: Boolean) {
        val mx = Rules.xoverMax(_ui.value.dev.mode)
        if (lock) {
            deb("xhp", Cmd.xoverHp(v, mx)) { it.copy(xhp = v, xlp = v) }
            deb("xlp", Cmd.xoverLp(v, mx)) { it.copy(xhp = v, xlp = v) }
        } else deb("xhp", Cmd.xoverHp(v, mx)) { it.copy(xhp = v) }
    }

    fun setXlp(v: Int, lock: Boolean) {
        val mx = Rules.xoverMax(_ui.value.dev.mode)
        if (lock) {
            deb("xhp", Cmd.xoverHp(v, mx)) { it.copy(xhp = v, xlp = v) }
            deb("xlp", Cmd.xoverLp(v, mx)) { it.copy(xhp = v, xlp = v) }
        } else deb("xlp", Cmd.xoverLp(v, mx)) { it.copy(xlp = v) }
    }

    fun setAmp(s: String) = now(Cmd.amp(s)) { it.copy(amp = s) }
    fun setInv(on: Boolean) = now(Cmd.subinv(on)) { it.copy(inv = on) }
    fun setStandby(on: Boolean) {
        val m = if (on) lastNonZeroStandby else 0
        now(Cmd.standby(m)) { it.copy(standbyMin = m) }
    }
    fun setMixer(s: String) = now(Cmd.mixmode(s)) { it.copy(mixer = s) }
    fun resetMixer() = now(Cmd.mixmode("stereo")) {
        it.copy(mixer = "stereo", mixLL = 1f, mixLR = 0f, mixRL = 0f, mixRR = 1f)
    }

    /** ch: ll | lr | rl | rr, nilai −1..2. */
    fun setMix(ch: String, v: Float) = deb("mix_$ch", Cmd.mixer(ch, v)) {
        when (ch) {
            "ll" -> it.copy(mixLL = v, mixer = "custom")
            "lr" -> it.copy(mixLR = v, mixer = "custom")
            "rl" -> it.copy(mixRL = v, mixer = "custom")
            else -> it.copy(mixRR = v, mixer = "custom")
        }
    }

    /** Lebar stereo mid/side: LL=RR=1+w, LR=RL=−w, w = 0..100 %. */
    fun setWidth(pct: Int) {
        val w = pct.coerceIn(0, 100) / 100f
        val upd = { d: Device -> d.copy(mixLL = 1f + w, mixLR = -w, mixRL = -w, mixRR = 1f + w, mixer = "custom") }
        deb("mix_ll", Cmd.mixer("ll", 1f + w), upd)
        deb("mix_lr", Cmd.mixer("lr", -w), upd)
        deb("mix_rl", Cmd.mixer("rl", -w), upd)
        deb("mix_rr", Cmd.mixer("rr", 1f + w), upd)
    }

    /** Clipper chip, satuan 0,1 dB (G −300..0, L/R 0..300). */
    fun setClip(g: Int? = null, l: Int? = null, r: Int? = null) {
        val d = _ui.value.dev
        val ng = g ?: d.clipGain; val nl = l ?: d.clipL; val nr = r ?: d.clipR
        deb("clip", Cmd.clipset(ng, nl, nr)) { it.copy(clipGain = ng, clipL = nl, clipR = nr) }
    }
    fun setEqOn(on: Boolean) = now(Cmd.eq(on)) { it.copy(eqOn = on) }

    fun setSubloud(on: Boolean) = now(Cmd.subloud(on)) { it.copy(subloud = on) }
    fun setSubloudPhone(on: Boolean) = now(Cmd.subloudPhone(on)) { it.copy(slPhone = on) }
    fun setSubloudMax(db10: Int) {
        val d = _ui.value.dev
        deb("slcfg", Cmd.subloudCfg(db10, d.slLo, d.slHi)) { it.copy(slMaxDb10 = db10) }
    }

    fun setPbass(on: Boolean) = now(Cmd.pbass(on)) { it.copy(pbass = on) }
    fun setPbassAmt(v: Int) {
        val d = _ui.value.dev
        deb("pbcfg", Cmd.pbassCfg(v, d.pbFc)) { it.copy(pbAmt = v) }
    }
    fun setPbassFc(v: Int) {
        val d = _ui.value.dev
        deb("pbcfg", Cmd.pbassCfg(d.pbAmt, v)) { it.copy(pbFc = v) }
    }

    // ---------------------------------------------------------------- EQ

    /** ch: 'l','r' (satelit) · 'c','s' (center/sub, mode 3). */
    fun setEq(ch: Char, band: Int, gain: Int) {
        val g = gain.coerceIn(-15, 15)
        val cmd = if (ch == 'c' || ch == 's') Cmd.eqc(ch, band, g) else Cmd.eqband(ch, band, g)
        deb("eq_${ch}_$band", cmd) { d ->
            fun List<Int>.with() = toMutableList().also { it[band] = g }
            when (ch) {
                'l' -> d.copy(eqL = d.eqL.with())
                'r' -> d.copy(eqR = d.eqR.with())
                'c' -> d.copy(eqC = d.eqC.with())
                else -> d.copy(eqS = d.eqS.with())
            }
        }
    }

    fun applyEqPreset(ch: Char, gains: List<Int>) {
        val min = Rules.eqMinBand(_ui.value.dev.mode)
        for (b in min until 15) setEq(ch, b, gains[b])
    }

    // ---------------------------------------------------------------- wireless

    fun scanSlaves() {
        viewModelScope.launch {
            tx(Cmd.WSUB_SCAN)
            delay(3000); tx(Cmd.STATUS)
            delay(6000); tx(Cmd.STATUS)
        }
    }
    fun pair(mac: String) { tx(Cmd.wsubPair(mac)); resync(800) }
    fun unpair(mac: String) { tx(Cmd.wsubUnpair(mac)); resync(800) }
    fun unpairAll() { tx(Cmd.WSUB_UNPAIR_ALL); resync(800) }
    fun diagReset() { tx(Cmd.WSUB_DIAG_RESET); resync(800) }
    fun setTrim(v: Int) = deb("trim", Cmd.wsubTrim(v)) { it.copy(wTrim = v) }
    fun setExtra(v: Int) = deb("extra", Cmd.wsubExtra(v)) { it.copy(wExtra = v) }
    fun editVoicing(v: Voicing) = _ui.update { it.copy(dev = it.dev.copy(voicing = v)) }
    fun applyVoicing() { tx(Cmd.wsubVoice(_ui.value.dev.voicing)); resync(800) }

    // ---------------------------------------------------------------- monitor

    fun setMonitor(on: Boolean) {
        meterJob?.cancel()
        meterJob = null
        if (!on) return
        meterJob = viewModelScope.launch {
            while (true) {
                val s = _ui.value
                if (s.conn == Conn.Connected && s.synced) {
                    if (!lvlOn) { lvlOn = true; tx(Cmd.LVL_ON, log = false) }
                    tx(Cmd.LVL, log = false)
                    when (s.dev.mode) {
                        1, 3, 5 -> tx(Cmd.LVL2, log = false)
                        4 -> tx(Cmd.WLVL, log = false)
                    }
                }
                delay(300)
            }
        }
    }

    // ---------------------------------------------------------------- lain-lain

    fun setTheme(i: Int) {
        prefs.edit().putInt("theme", i).apply()
        _ui.update { it.copy(theme = i) }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
    private fun toast(m: String) = _ui.update { it.copy(message = m) }
    private fun addLog(s: String) =
        _ui.update { it.copy(log = (it.log + "${clock.format(Date())} $s").takeLast(200)) }
}
