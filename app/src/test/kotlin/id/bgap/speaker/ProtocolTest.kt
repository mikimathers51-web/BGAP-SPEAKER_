package id.bgap.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun feed(vararg lines: String): Device = lines.fold(Device()) { d, l -> Protocol.apply(d, l, now = 1000L) }

    @Test fun modeLine() {
        val d = feed("MODE 4 xover=120 master=64 sat=90 ctr=100 sub=80 xhp=100 xlp=110")
        assertEquals(4, d.mode); assertEquals(64, d.master); assertEquals(90, d.sat)
        assertEquals(80, d.sub); assertEquals(100, d.xhp); assertEquals(110, d.xlp)
    }

    @Test fun stateAndFx() {
        val d = feed("STATE gain=10 amp=mute eq=1 ll=1.00 lr=0.00 rl=0.00 rr=1.00", "FX inv=1 stby=15 sb=0")
        assertEquals(10, d.gain); assertEquals("mute", d.amp); assertTrue(d.eqOn)
        assertEquals("stereo", d.mixer); assertTrue(d.inv); assertEquals(15, d.standbyMin)
    }

    @Test fun eqLines() {
        val d = feed("EQ l=" + List(15) { 2 }.joinToString(","), "EQ r=" + List(15) { -1 }.joinToString(","))
        assertEquals(List(15) { 2 }, d.eqL); assertEquals(List(15) { -1 }, d.eqR)
        // panjang salah diabaikan
        assertEquals(d, Protocol.apply(d, "EQ l=1,2,3"))
    }

    @Test fun wirelessLine() {
        val d = feed("WSUB peers=AA:BB:CC:DD:EE:01,AA:BB:CC:DD:EE:02 links=1,0 fill=88,0 und=0,3 gap=1,2 rate=48,0 found=AA:BB:CC:DD:EE:03 trim=5 ch=6 lat=70 err=ok")
        assertEquals(2, d.peers.size)
        assertTrue(d.peers[0].linked); assertFalse(d.peers[1].linked)
        assertEquals(88, d.peers[0].fillMs); assertEquals(3, d.peers[1].under)
        assertEquals(listOf("AA:BB:CC:DD:EE:03"), d.found)
        assertEquals(5, d.wTrim); assertEquals(6, d.wCh)
        val none = Protocol.apply(d, "WSUB peers=none found=none")
        assertTrue(none.peers.isEmpty()); assertTrue(none.found.isEmpty())
    }

    @Test fun levels() {
        val d = feed("LVL l=-120 r=-300", "LVL2 l=-50 r=-60", "WLVL s0=-250 s1=na")
        assertEquals(-12f, d.levels.l, 0.01f); assertEquals(-30f, d.levels.r, 0.01f)
        assertEquals(-5f, d.levels.l2, 0.01f); assertEquals(-25f, d.levels.w, 0.01f)
    }

    @Test fun unknownLineIsIgnored() {
        val d = Device()
        assertEquals(d, Protocol.apply(d, "OK ping"))
        assertEquals(d, Protocol.apply(d, "ERR bad"))
    }

    @Test fun commands() {
        assertEquals("master 100", Cmd.master(250))
        assertEquals("xover_hp 150", Cmd.xoverHp(999, 150))
        assertEquals("eqband l 5 15", Cmd.eqband('l', 5, 40))
        assertEquals("subloud_cfg 40 30 70", Cmd.subloudCfg(40, 30, 70))
        assertEquals("pbass_cfg 55 150", Cmd.pbassCfg(55, 400))
        assertEquals("wsub_voice 1 27 32 0 15 0 100", Cmd.wsubVoice(Voicing(on = true)))
    }

    @Test fun meterMapping() {
        assertEquals(listOf("L", "R"), meterChannels(0).map { it.first })
        assertEquals(4, meterChannels(3).size)
        assertEquals(Src.W, meterChannels(4)[1].second)
    }

    @Test fun mixerAndClip() {
        val d = feed("STATE gain=10 amp=play eq=1 ll=1.50 lr=-0.50 rl=-0.50 rr=1.50", "CLIP gain=-30 ml=189 mr=185")
        assertEquals(1.5f, d.mixLL, 0.001f); assertEquals(-0.5f, d.mixLR, 0.001f)
        assertEquals(0.5f, d.stereoWidth()!!, 0.001f)
        assertEquals(-30, d.clipGain); assertEquals(189, d.clipL); assertEquals(185, d.clipR)
        assertEquals(0f, Device().stereoWidth()!!, 0.001f)
        val mono = feed("STATE gain=1 amp=play eq=1 ll=0.50 lr=0.50 rl=0.50 rr=0.50")
        assertEquals(null, mono.stereoWidth())
        assertEquals("mixer lr -0.50", Cmd.mixer("lr", -0.5f))
        assertEquals("mixer ll 2.00", Cmd.mixer("ll", 9f))
        assertEquals("clipset -300 300 0", Cmd.clipset(-999, 999, -5))
    }
}
