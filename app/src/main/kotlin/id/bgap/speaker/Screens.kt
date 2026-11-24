package id.bgap.speaker

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val TABS = listOf("🔌" to "Koneksi", "🎚" to "Kontrol", "〰" to "EQ", "🎛" to "DSP", "📊" to "Monitor", "🩺" to "Diagnosa")

// =============================================================== kerangka

@Composable
fun AppScreen(vm: BgapViewModel, ui: UiState, hasPerm: Boolean, requestPerm: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val connected = ui.conn == Conn.Connected

    LaunchedEffect(ui.message) {
        ui.message?.let { snack.showSnackbar(it); vm.consumeMessage() }
    }
    LaunchedEffect(tab, connected) { vm.setMonitor(tab == 4 && connected) }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icon) },
                        label = { Text(label, maxLines = 1, fontSize = 10.sp) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Header(ui)
            when {
                tab == 0 -> KoneksiTab(vm, ui, hasPerm, requestPerm)
                tab == 5 -> DiagnosaTab(vm, ui)
                !connected -> NeedConnect()
                tab == 1 -> KontrolTab(vm, ui.dev)
                tab == 2 -> EqTab(vm, ui.dev)
                tab == 3 -> DspTab(vm, ui.dev)
                else -> MonitorTab(ui.dev)
            }
        }
    }
}

@Composable
private fun Header(ui: UiState) {
    val c = when {
        ui.conn == Conn.Connected && ui.synced -> Color(0xFF34D399)
        ui.conn == Conn.Idle -> Color(0xFF64748B)
        else -> Color(0xFFFBBF24)
    }
    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(c, CircleShape))
        Column(Modifier.padding(start = 10.dp)) {
            Text("BGAP Speaker", style = MaterialTheme.typography.titleMedium)
            Text(
                when (ui.conn) {
                    Conn.Idle -> "Tidak tersambung"
                    Conn.Connecting -> "Menyambung…"
                    Conn.Connected -> "${ui.devName} · mode ${ui.dev.mode} (${MODE_SHORT.getOrElse(ui.dev.mode) { "?" }})" +
                        (if (ui.dev.fw.isNotEmpty()) " · fw ${ui.dev.fw}" else "") + (if (ui.synced) "" else " · menyinkron…")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NeedConnect() {
    Section("Belum tersambung") {
        Text("Buka tab Koneksi, pilih speaker yang sudah dipasangkan, lalu tekan Sambung.")
    }
}

// =============================================================== komponen

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    unit: String = "",
    enabled: Boolean = true,
    fmt: (Int) -> String = { "$it$unit" },
    onChange: (Int) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(fmt(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(min, max).toFloat(),
            onValueChange = { val v = it.roundToInt(); if (v != value) onChange(v) },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, t ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, enabled = enabled, label = { Text(t) })
        }
    }
}

@Composable
fun SwitchRow(label: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ConfirmButton(text: String, msg: String, onConfirm: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text(text) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(text) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { open = false; onConfirm() }) { Text("Ya") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Batal") } },
        )
    }
}

// =============================================================== Koneksi

@Composable
private fun KoneksiTab(vm: BgapViewModel, ui: UiState, hasPerm: Boolean, requestPerm: () -> Unit) {
    val ctx = LocalContext.current
    Section("Koneksi") {
        Text(
            when (ui.conn) {
                Conn.Idle -> "Tidak tersambung"
                Conn.Connecting -> "Menyambung…"
                Conn.Connected -> "Tersambung: ${ui.devName}" + if (ui.synced) " · tersinkron" else " · menyinkron…"
            },
        )
        if (ui.conn == Conn.Connected) Button(onClick = { vm.disconnect() }) { Text("Putuskan") }
    }
    Section("Perangkat Bluetooth (sudah dipasangkan)") {
        if (!hasPerm) {
            Text("Izin Bluetooth diperlukan untuk membaca daftar perangkat.")
            Button(onClick = requestPerm) { Text("Beri izin") }
        } else {
            if (ui.bonded.isEmpty()) Text("Belum ada. Pasangkan BGAP-SPEAKER dulu di Pengaturan Bluetooth Android.")
            ui.bonded.forEach { b ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name)
                        Text(b.mac, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(enabled = ui.conn == Conn.Idle, onClick = { vm.connect(b.mac) }) { Text("Sambung") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refreshBonded() }) { Text("Muat ulang") }
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Pengaturan BT") }
            }
        }
    }
    Section("Tema") { ChipRow(THEME_NAMES, ui.theme) { vm.setTheme(it) } }
}

// =============================================================== Kontrol

@Composable
private fun KontrolTab(vm: BgapViewModel, d: Device) {
    var lock by remember { mutableStateOf(true) }
    val m = d.mode
    Section("Mode") {
        ChipRow(MODE_SHORT, m) { vm.setMode(it) }
        Text(
            "${MODE_NAMES.getOrElse(m) { "?" }} · ${MODE_ENV.getOrElse(m) { "?" }}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (m == 4) Text("Env wireless master terkunci di mode 4.", style = MaterialTheme.typography.bodySmall)
    }
    Section("Volume") {
        SliderRow("Master", d.master, 0, 100, "%") { vm.setMaster(it) }
        SliderRow(if (m >= 5) "Tweeter" else "Satelit", d.sat, 0, 100, "%") { vm.setSat(it) }
        if (Rules.hasSub(m)) SliderRow(if (m >= 5) "Woofer" else "Sub", d.sub, 0, 100, "%") { vm.setSub(it) }
        if (Rules.hasCenter(m)) SliderRow("Center", d.ctr, 0, 100, "%") { vm.setCtr(it) }
    }
    if (Rules.hasXover(m) && Rules.hasSub(m)) {
        Section("Crossover") {
            SwitchRow("Kunci HPF = LPF", checked = lock) { lock = it }
            val mx = Rules.xoverMax(m)
            SliderRow("HPF satelit", d.xhp, 60, mx, " Hz") { vm.setXhp(it, lock) }
            SliderRow("LPF sub", d.xlp, 60, mx, " Hz") { vm.setXlp(it, lock) }
            if (m == 2) Text("Mode 2 mendukung sampai 300 Hz; di atas 150 Hz firmware memakai biquad LR4.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (Rules.hasTway(m)) {
        Section("2-way aktif") {
            if (m == 2) SwitchRow("2-way mono (tway)", "L = tweeter, R = woofer", d.twayOn) { vm.setTwayOn(it) }
            SliderRow("Crossover 2-way", d.twayHz, 1000, 5000, " Hz") { vm.setTway(it) }
        }
    }
    Section("Output") {
        SliderRow("Gain analog", d.gain, 0, 31, fmt = { "-%.1f dB".format(it / 2f) }) { vm.setGain(it) }
        val amps = listOf("play", "mute", "hiz")
        ChipRow(listOf("Play", "Mute", "Hi-Z"), amps.indexOf(d.amp)) { vm.setAmp(amps[it]) }
        if (Rules.hasSub(m)) SwitchRow("Polaritas sub 180°", checked = d.inv) { vm.setInv(it) }
        SwitchRow("Auto-standby", "Menit idle sebelum standby (bawaan 10)", d.standbyMin > 0) { vm.setStandby(it) }
    }
    if (Rules.hasSubloud(m)) {
        Section("Loudness sub") {
            SwitchRow("Aktif", "Naikkan sub saat volume kecil", d.subloud) { vm.setSubloud(it) }
            SwitchRow("Ikut volume ponsel", null, d.slPhone) { vm.setSubloudPhone(it) }
            SliderRow("Boost maks", d.slMaxDb10 / 10, 0, 6, " dB") { vm.setSubloudMax(it * 10) }
        }
    }
    Section("Bass virtual (psychoacoustic)") {
        SwitchRow("Aktif", "Harmonik bass ditambahkan di ESP32", d.pbass) { vm.setPbass(it) }
        SliderRow("Jumlah harmonik", d.pbAmt, 0, 100) { vm.setPbassAmt(it) }
        SliderRow("FC (harmonik mulai)", d.pbFc, 40, 150, " Hz") { vm.setPbassFc(it) }
    }
    Section("Simpan") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveToDevice() }) { Text("Simpan ke perangkat") }
            OutlinedButton(onClick = { vm.refresh() }) { Text("Muat ulang") }
        }
    }
}

// =============================================================== EQ

private val EQ_PRESETS = listOf(
    "Flat" to List(15) { 0 },
    "Bass" to listOf(6, 5, 4, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    "Vokal" to listOf(-6, -5, -3, -2, -1, 0, 0, 1, 2, 3, 3, 2, 1, 0, -1),
    "Loudness" to listOf(6, 5, 3, 1, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 4),
)

@Composable
private fun EqTab(vm: BgapViewModel, d: Device) {
    val chans = buildList {
        add("L" to 'l'); add("R" to 'r')
        if (d.mode == 3) { add("Center" to 'c'); add("Sub" to 's') }
    }
    var sel by remember { mutableIntStateOf(0) }
    if (sel >= chans.size) sel = 0
    val ch = chans[sel].second
    val gains = when (ch) { 'l' -> d.eqL; 'r' -> d.eqR; 'c' -> d.eqC; else -> d.eqS }
    val minBand = if (ch == 'c' || ch == 's') 3 else Rules.eqMinBand(d.mode)

    Section("Kanal EQ") {
        ChipRow(chans.map { it.first }, sel) { sel = it }
        if (Rules.eqToggleable(d.mode)) SwitchRow("EQ aktif", null, d.eqOn) { vm.setEqOn(it) }
        else Text("Band 0–2 dikunci untuk crossover di mode ini.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EQ_PRESETS.forEach { (name, g) -> OutlinedButton(onClick = { vm.applyEqPreset(ch, g) }) { Text(name, fontSize = 12.sp) } }
        }
    }
    Section("Band") {
        for (b in 0 until 15) {
            SliderRow(
                "${EQ_FREQS[b]} Hz", gains[b], -15, 15, enabled = b >= minBand,
                fmt = { (if (it > 0) "+" else "") + it + " dB" },
            ) { vm.setEq(ch, b, it) }
        }
    }
}

// =============================================================== DSP

@Composable
private fun DspTab(vm: BgapViewModel, d: Device) {
    val m = d.mode
    Section("Mixer kanal") {
        if (Rules.mixerLocked(m)) {
            Text("Mixer dikunci di mode ini (1-chip 2.1 / 2-way).", style = MaterialTheme.typography.bodySmall)
        } else {
            val mixers = listOf("stereo", "inv", "mono", "left", "right")
            val labels = listOf("Stereo", "Inv", "Mono", "Kiri", "Kanan")
            ChipRow(labels, mixers.indexOf(d.mixer)) { vm.setMixer(mixers[it]) }
            val w = d.stereoWidth()
            SliderRow(
                "Lebar stereo", if (w == null) 0 else (w * 100).roundToInt(), 0, 100,
                fmt = { if (w == null) "kustom" else "$it%" },
            ) { vm.setWidth(it) }
            @Composable
            fun mix(label: String, ch: String, v: Float) =
                SliderRow(label, (v * 100).roundToInt(), -100, 200, fmt = { "%.2f".format(it / 100f) }) { vm.setMix(ch, it / 100f) }
            mix("LL (L → kiri)", "ll", d.mixLL)
            mix("LR (R → kiri)", "lr", d.mixLR)
            mix("RL (L → kanan)", "rl", d.mixRL)
            mix("RR (R → kanan)", "rr", d.mixRR)
            OutlinedButton(onClick = { vm.resetMixer() }) { Text("Reset ke stereo normal") }
            Text(
                "Gain −1…2. Lebar stereo memakai mid/side (LL=RR=1+w, LR=RL=−w); w besar menaikkan level, awasi clipping. " +
                    if (m == 1 || m == 3) "Di mode ini mixer hanya memengaruhi chip satelit." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Section("Clipper chip") {
        Text(
            "Pembatas puncak di chip. Satuan 0,1 dB. Salah setel membuat suara terdistorsi.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SliderRow("Gain clipper", d.clipGain, -300, 0, fmt = { "%.1f dB".format(it / 10f) }) { vm.setClip(g = it) }
        SliderRow("Batas kiri (ml)", d.clipL, 0, 300, fmt = { "$it · %.1f dB".format(it / 10f) }) { vm.setClip(l = it) }
        SliderRow("Batas kanan (mr)", d.clipR, 0, 300, fmt = { "$it · %.1f dB".format(it / 10f) }) { vm.setClip(r = it) }
        OutlinedButton(onClick = { vm.readClip() }) { Text("Baca dari chip") }
    }
    if (m == 4) WirelessTab(vm, d)
}

// =============================================================== Wireless

@Composable
private fun WirelessTab(vm: BgapViewModel, d: Device) {
    if (d.mode != 4) {
        Section("Wireless sub") { Text("Hanya tersedia di mode 4 (env esp32dev_wireless_master).") }
        return
    }
    Section("Slave sub") {
        if (d.peers.isEmpty()) Text("Belum ada slave terpasang.")
        d.peers.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${p.mac} · ${if (p.linked) "LINK" else "IDLE"}")
                    Text(
                        "buffer ${p.fillMs} ms · und ${p.under} · gap ${p.gap} · ${p.rate} Hz",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.unpair(p.mac) }) { Text("Lepas") }
            }
        }
        val free = d.found.filter { f -> d.peers.none { it.mac == f } }
        free.forEach { f ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ditemukan: $f", Modifier.weight(1f))
                Button(onClick = { vm.pair(f) }) { Text("Pasangkan") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.scanSlaves() }) { Text("Cari slave") }
            ConfirmButton("Lepas semua", "Lepas semua slave sub?") { vm.unpairAll() }
        }
    }
    Section("Penyelarasan latensi") {
        SliderRow("wsub_trim", d.wTrim, -60, 100, " ms") { vm.setTrim(it) }
        SliderRow("wsub_extra", d.wExtra, 0, 200, " ms") { vm.setExtra(it) }
        Text("Latensi sekarang: ${d.wLat} ms", style = MaterialTheme.typography.bodySmall)
    }
    Section("Voicing sub (slave ≥ 2.9.20)") {
        val v = d.voicing
        SwitchRow("Aktif", null, v.on) { vm.editVoicing(v.copy(on = it)) }
        SliderRow("HPF subsonic", v.hpf, 20, 120, " Hz") { vm.editVoicing(v.copy(hpf = it)) }
        SliderRow("Peaking frekuensi", v.pkHz, 30, 150, " Hz") { vm.editVoicing(v.copy(pkHz = it)) }
        SliderRow("Peaking gain", v.db10, -60, 90, fmt = { "%.1f dB".format(it / 10f) }) { vm.editVoicing(v.copy(db10 = it)) }
        SliderRow("Peaking Q", v.q10, 5, 30, fmt = { "%.1f".format(it / 10f) }) { vm.editVoicing(v.copy(q10 = it)) }
        Button(onClick = { vm.applyVoicing() }) { Text("Terapkan ke slave") }
    }
    Section("Diagnostik radio") {
        Text(
            "ch ${d.wCh} · err ${d.wErr} · nomem ${d.wNomem} · ackok ${d.wAckOk} · ackfail ${d.wAckFail} · txf ${d.wTxf} · pkt ${d.wPkt}",
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
        )
        OutlinedButton(onClick = { vm.diagReset() }) { Text("Reset ukur") }
        d.radio.takeLast(6).forEach {
            Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// =============================================================== Monitor

@Composable
private fun MonitorTab(d: Device) {
    val chs = meterChannels(d.mode)
    Section("Level output (dBFS)") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            chs.forEach { (label, src) -> MeterBar(label, d.levels.db(src)) }
        }
        Text(
            "${MODE_NAMES.getOrElse(d.mode) { "?" }} — dibaca dari level meter chip TAS5805M (lvl/lvl2/wlvl).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MeterBar(label: String, db: Float) {
    val cur by rememberUpdatedState(db)
    var hold by remember { mutableFloatStateOf(-60f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60)
            hold = max(cur, hold - 1.2f).coerceAtLeast(-60f)
        }
    }
    val h = 170.dp
    val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val holdFrac = ((hold + 60f) / 60f).coerceIn(0f, 1f)
    val color = when {
        db > -3f -> Color(0xFFEF4444)
        db > -12f -> Color(0xFFEAB308)
        else -> Color(0xFF22C55E)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (db <= -60f) "−∞" else "%.1f".format(db), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Box(Modifier.height(h).size(width = 38.dp, height = h).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(frac.coerceAtLeast(0.001f)).background(color, RoundedCornerShape(6.dp)))
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = h * holdFrac).fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface))
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// =============================================================== Diagnosa

@Composable
private fun DiagnosaTab(vm: BgapViewModel, ui: UiState) {
    val d = ui.dev
    val connected = ui.conn == Conn.Connected
    Section("Ringkasan") {
        Text(
            "Mode ${d.mode} (${MODE_NAMES.getOrElse(d.mode) { "?" }})\nenv ${MODE_ENV.getOrElse(d.mode) { "?" }}\n" +
                "gain -${"%.1f".format(d.gain / 2f)} dB · amp ${d.amp} · fw ${d.fw.ifEmpty { "?" }}" +
                (if (d.mode == 4) "\nslave ${d.peers.count { it.linked }}/${d.peers.size}" else ""),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        if (d.caps.isNotEmpty()) Text("CAPS: ${d.caps.joinToString(" ")}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        if (d.fault.isNotEmpty()) Text(d.fault, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    if (connected) {
        Section("Aksi") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refresh() }) { Text("Refresh") }
                OutlinedButton(onClick = { vm.readFault() }) { Text("Fault") }
                OutlinedButton(onClick = { vm.readCaps() }) { Text("CAPS") }
                OutlinedButton(onClick = { vm.ping() }) { Text("Ping") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfirmButton("Hapus simpanan", "Hapus semua setelan tersimpan di perangkat?") { vm.eraseSaved() }
                ConfirmButton("Reboot", "Reboot ESP32 sekarang? Koneksi akan terputus.") { vm.reboot() }
            }
        }
    }
    Section("Terminal") {
        var text by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                singleLine = true, placeholder = { Text("status, fault, caps …") }, enabled = connected,
            )
            Button(enabled = connected, onClick = { vm.sendRaw(text.ifBlank { "status" }); text = "" }) { Text("Kirim") }
        }
        Column(Modifier.fillMaxWidth()) {
            ui.log.takeLast(40).reversed().forEach {
                Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
