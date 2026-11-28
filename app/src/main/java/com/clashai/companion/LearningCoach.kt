package com.aiplayer.companion

import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The learning engine of the online mode. It works in two phases.
 *
 * 1) LEARN: the user plays by himself. A transparent layer (LearnOverlay) sees every tap, hold and swipe and passes it on
 *    to the game. For each touch the engine stores a small picture of the screen as it was just before the touch
 *    (40 x 72 gray values) together with the touch itself, and then checks whether the screen really changed
 *    (a touch that changed nothing is stored as a weak example). It also stores calm moments in which the user did
 *    nothing, so that it learns WHEN NOT to touch (the opponent is moving, an animation is running).
 *
 * 2) PLAY: for every new screen the engine looks for the stored screens that are almost identical.
 *    - If a (nearly) identical screen is known and the examples agree, the engine plays the learned touch at once,
 *      with no internet and no waiting. This is much faster than a human and than a cloud request.
 *    - Otherwise the cloud AI plays, but it gets the user's habits as a hint (what the user did on similar screens, and
 *      which controls he presses), and its touches are pulled onto the exact controls the user really presses.
 *
 * It keeps improving while it plays, so it is not just a copy of the user:
 *    - every played touch is checked afterwards: if the screen changed the example gains weight, if nothing happened the
 *      example loses weight (after two failures it is no longer used and the cloud AI decides);
 *    - touches chosen by the cloud AI that visibly worked are stored as new examples (weaker than the user's own);
 *    - the user's useless touches (nothing changed) never become strong examples.
 *
 * Limits: the match must be almost exact (a chess position that never appeared before is NOT matched; there the
 * cloud AI plays with the user's habits as help). Learning records one finger: tap, hold and one-finger swipe.
 */
object LearningCoach {

    private const val DW = 40
    private const val DH = 72
    private const val N = DW * DH

    /** A cell counts as changed when its gray value differs by more than this. */
    private const val CELL_T = 22

    /** Share of changed cells for "the same screen" and for "a similar screen". */
    private const val STRONG = 0.005f
    private const val SOFT = 0.04f

    /** At least this many cells must change before the engine believes that a touch did something. */
    private const val EFFECT_CELLS = 4

    private const val K = 9
    private const val MIN_DEMOS = 3
    private const val MAX_DEMOS = 2500

    const val T_TAP = 0
    const val T_SWIPE = 1
    const val T_HOLD = 2
    const val T_WAIT = 3

    class Demo(
        val desc: ByteArray,
        val type: Int,
        val x: Float,
        val y: Float,
        val x2: Float,
        val y2: Float,
        val ms: Int,
        var score: Float,
        var uses: Int,
        val at: Long
    )

    /** The answer of analyse(). `action` is set when a learned touch should be played now. */
    class CoachResult(
        val desc: ByteArray,
        val action: GameAction?,
        val wait: Boolean,
        val confidence: Float,
        val used: List<Demo>,
        val hint: String
    )

    private class Recent(val at: Long, val desc: ByteArray)
    private class PendingRecord(val demo: Demo, val before: ByteArray, val evalAt: Long)
    private class PendingEval(val at: Long, val before: ByteArray, val used: List<Demo>, val key: String, val cloud: Demo?)
    private class Hotspot(var x: Float, var y: Float, var weight: Float)

    @Volatile
    var learning = false
        private set

    @Volatile
    var playing = false
        private set

    /** Touches recorded in the current learning session. */
    @Volatile
    var sessionCount = 0
        private set

    private val lock = Any()
    private val stores = HashMap<String, ArrayList<Demo>>()
    private val unsaved = HashMap<String, Int>()
    private val hotspotCache = HashMap<String, List<Hotspot>>()

    private val recent = ArrayList<Recent>()
    private val idleCandidates = ArrayList<Recent>()
    private var lastGestureAt = 0L
    private var lastIdleAt = 0L
    private var pendingRecord: PendingRecord? = null
    private var pendingEval: PendingEval? = null

    // ------------------------------------------------------------------ switches

    fun startLearning() {
        synchronized(lock) {
            learning = true
            playing = false
            recent.clear()
            idleCandidates.clear()
            pendingRecord = null
            pendingEval = null
            sessionCount = 0
            lastGestureAt = System.currentTimeMillis()
            lastIdleAt = 0L
        }
    }

    fun stopLearning() {
        if (!learning) return
        learning = false
        synchronized(lock) {
            recent.clear()
            idleCandidates.clear()
            pendingRecord = null
        }
        saveAll()
    }

    fun startPlaying() {
        synchronized(lock) {
            playing = true
            learning = false
            pendingEval = null
        }
    }

    fun stopPlaying() {
        playing = false
        synchronized(lock) { pendingEval = null }
        saveAll()
    }

    // ------------------------------------------------------------------ pictures

    /** A tiny gray picture of the screen. The two-step shrink averages the pixels instead of skipping most of them. */
    fun descriptor(frame: Bitmap): ByteArray {
        val mid = Bitmap.createScaledBitmap(frame, DW * 3, DH * 3, true)
        val small = Bitmap.createScaledBitmap(mid, DW, DH, true)
        val px = IntArray(DW * DH)
        small.getPixels(px, 0, DW, 0, 0, DW, DH)
        if (small !== mid) small.recycle()
        if (mid !== frame) mid.recycle()
        val out = ByteArray(N)
        for (i in 0 until N) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = ((r * 3 + g * 6 + b) / 10).toByte()
        }
        return out
    }

    private fun changedCells(a: ByteArray, b: ByteArray): Int {
        var n = 0
        for (i in 0 until N) {
            val d = abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
            if (d > CELL_T) n++
        }
        return n
    }

    /** Share of cells that differ (0 = identical). */
    private fun distance(a: ByteArray, b: ByteArray): Float = changedCells(a, b).toFloat() / N

    // ------------------------------------------------------------------ learning

    /** Called for every screenshot while learning. */
    fun onFrame(pkg: String?, frame: Bitmap) {
        if (!learning) return
        val now = System.currentTimeMillis()
        val desc = descriptor(frame)
        val key = keyOf(pkg)
        synchronized(lock) {
            recent.add(Recent(now, desc))
            while (recent.size > 10) recent.removeAt(0)

            // Did the last recorded touch change the screen?
            val pr = pendingRecord
            if (pr != null && now >= pr.evalAt) {
                if (changedCells(pr.before, desc) < EFFECT_CELLS) pr.demo.score = min(pr.demo.score, 0.15f)
                pendingRecord = null
            }

            // Calm moments (the user did nothing for a while) teach the engine when not to touch.
            val iter = idleCandidates.iterator()
            while (iter.hasNext()) {
                val c = iter.next()
                if (lastGestureAt >= c.at) {
                    iter.remove()
                } else if (now - c.at >= 3500L) {
                    iter.remove()
                    val store = storeFor(key)
                    var waits = 0
                    for (d in store) if (d.type == T_WAIT) waits++
                    if (waits * 10 < max(10, store.size * 4)) {
                        addDemo(key, Demo(c.desc, T_WAIT, 0f, 0f, 0f, 0f, 0, 0.8f, 1, now), 0.8f)
                    }
                }
            }
            if (now - lastGestureAt > 2500L && now - lastIdleAt > 2500L) {
                lastIdleAt = now
                idleCandidates.add(Recent(now, desc))
            }
        }
    }

    /**
     * Called by LearnOverlay when the user finished a touch. Coordinates are 0..1 of the display.
     * `downAt` is the wall-clock time when the finger went down.
     */
    fun onUserGesture(pkg: String?, type: Int, x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long, downAt: Long) {
        if (!learning) return
        val key = keyOf(pkg)
        synchronized(lock) {
            lastGestureAt = System.currentTimeMillis()
            idleCandidates.clear()
            // The screen as it was before the finger went down.
            var before: Recent? = null
            for (r in recent) if (r.at <= downAt + 80L) before = r
            val b = before
            if (b == null || downAt - b.at > 3000L) {
                AdviceLog.info("یادگیری: تصویر قبل از لمس موجود نبود؛ این لمس ثبت نشد")
                return
            }
            val demo = addDemo(key, Demo(b.desc, type, x1, y1, x2, y2, durMs.toInt(), 1.0f, 1, System.currentTimeMillis()), 1.0f)
            pendingRecord = PendingRecord(demo, b.desc, System.currentTimeMillis() + 700L)
            sessionCount++
            AdviceLog.info("یادگیری: $sessionCount لمس ثبت شد (حافظه‌ی این بازی: ${storeFor(key).size})")
        }
        maybeSave(key)
    }

    /** Adds a new example, or strengthens an almost identical one. Must be called with the lock held. */
    private fun addDemo(key: String, d: Demo, weight: Float): Demo {
        val store = storeFor(key)
        for (e in store) {
            if (e.type != d.type) continue
            if (distance(e.desc, d.desc) > STRONG) continue
            if (d.type != T_WAIT) {
                val close = if (d.type == T_SWIPE) hypot(e.x - d.x, e.y - d.y) < 0.04f && hypot(e.x2 - d.x2, e.y2 - d.y2) < 0.06f
                else hypot(e.x - d.x, e.y - d.y) < 0.035f
                if (!close) continue
            }
            e.uses++
            e.score = min(3f, max(e.score, 0.3f) + 0.25f * weight)
            bump(key)
            return e
        }
        store.add(d)
        if (store.size > MAX_DEMOS) {
            // Forget the weakest and oldest example.
            var worst = 0
            var worstValue = Float.MAX_VALUE
            for (i in store.indices) {
                val v = store[i].score * 1000f + store[i].uses * 10f + store[i].at / 1.0e10f
                if (v < worstValue) { worstValue = v; worst = i }
            }
            store.removeAt(worst)
        }
        bump(key)
        return d
    }

    private fun bump(key: String) {
        unsaved[key] = (unsaved[key] ?: 0) + 1
        hotspotCache.remove(key)
    }

    // ------------------------------------------------------------------ playing

    /**
     * Looks at the screen and decides: play a learned touch, wait (the user waited on such screens), or leave it to the
     * cloud AI (with a hint about the user's habits when something similar is known).
     */
    fun analyse(pkg: String?, frame: Bitmap): CoachResult {
        val now = System.currentTimeMillis()
        val desc = descriptor(frame)
        val key = keyOf(pkg)
        return synchronized(lock) { analyseLocked(desc, key, now) }
    }

    private fun analyseLocked(desc: ByteArray, key: String, now: Long): CoachResult {
        judgePending(desc, now)
            val store = storeFor(key)
            if (store.size < MIN_DEMOS) return CoachResult(desc, null, false, 0f, emptyList(), "")

            // The K closest examples.
            val idx = IntArray(K) { -1 }
            val dst = FloatArray(K) { Float.MAX_VALUE }
            for (i in store.indices) {
                val d = distance(store[i].desc, desc)
                if (d > SOFT * 2f) continue
                var pos = K
                while (pos > 0 && d < dst[pos - 1]) pos--
                if (pos >= K) continue
                for (j in K - 1 downTo pos + 1) { dst[j] = dst[j - 1]; idx[j] = idx[j - 1] }
                dst[pos] = d
                idx[pos] = i
            }
            if (idx[0] < 0) return CoachResult(desc, null, false, 0f, emptyList(), habitsHint(key, emptyList()))

            // Group the neighbours by the touch they stand for.
            class Cluster(val type: Int, var x: Float, var y: Float, var x2: Float, var y2: Float, var ms: Float) {
                var weight = 0f
                var bestSim = 0f
                val members = ArrayList<Demo>()
            }
            val clusters = ArrayList<Cluster>()
            var total = 0f
            for (n in 0 until K) {
                val i = idx[n]
                if (i < 0) break
                val e = store[i]
                val sim = 1f / (1f + (dst[n] / 0.01f) * (dst[n] / 0.01f))
                val w = e.score * sim * (1f + 0.2f * min(5, e.uses - 1))
                total += w
                var home: Cluster? = null
                for (c in clusters) {
                    if (c.type != e.type) continue
                    if (e.type == T_WAIT) { home = c; break }
                    val close = if (e.type == T_SWIPE) hypot(c.x - e.x, c.y - e.y) < 0.05f && hypot(c.x2 - e.x2, c.y2 - e.y2) < 0.07f
                    else hypot(c.x - e.x, c.y - e.y) < 0.04f
                    if (close) { home = c; break }
                }
                val c = home ?: Cluster(e.type, e.x, e.y, e.x2, e.y2, e.ms.toFloat()).also { clusters.add(it) }
                val nw = c.weight + w
                if (nw > 0f) {
                    c.x = (c.x * c.weight + e.x * w) / nw
                    c.y = (c.y * c.weight + e.y * w) / nw
                    c.x2 = (c.x2 * c.weight + e.x2 * w) / nw
                    c.y2 = (c.y2 * c.weight + e.y2 * w) / nw
                    c.ms = (c.ms * c.weight + e.ms * w) / nw
                }
                c.weight = nw
                if (sim > c.bestSim) c.bestSim = sim
                c.members.add(e)
            }
            var best: Cluster? = null
            for (c in clusters) if (best == null || c.weight > best.weight) best = c
            val b = best
            val hint = habitsHint(key, clusters.filter { it.type != T_WAIT }.sortedByDescending { it.weight }
                .take(3).map { floatArrayOf(it.x, it.y, it.members.size.toFloat(), it.type.toFloat()) })
            if (b == null || total <= 0f) return CoachResult(desc, null, false, 0f, emptyList(), hint)

            val conf = (b.weight / total) * b.bestSim
            val nearest = dst[0]
            val strongEnough = b.weight >= 0.35f &&
                ((nearest <= STRONG && conf >= 0.5f) || (nearest <= STRONG * 2f && conf >= 0.75f))
            if (!strongEnough) return CoachResult(desc, null, false, conf, emptyList(), hint)
            if (b.type == T_WAIT) return CoachResult(desc, null, true, conf, b.members, hint)
            val action: GameAction = when (b.type) {
                T_SWIPE -> GameAction.Swipe(b.x, b.y, b.x2, b.y2, b.ms.toLong().coerceIn(80L, 1500L))
                T_HOLD -> GameAction.Hold(b.x, b.y, b.ms.toLong().coerceIn(120L, 6000L))
                else -> GameAction.Tap(b.x, b.y)
            }
            return CoachResult(desc, action, false, conf, b.members, hint)
    }

    /** The played touch of a local suggestion will be judged on a later screen. */
    fun noteExecuted(pkg: String?, res: CoachResult) {
        synchronized(lock) {
            pendingEval = PendingEval(System.currentTimeMillis(), res.desc, res.used, keyOf(pkg), null)
        }
    }

    /** A touch chosen by the cloud AI: if the screen changes, it is stored as a (weaker) example. */
    fun noteCloud(pkg: String?, desc: ByteArray?, action: GameAction?) {
        if (!playing || desc == null || action == null) return
        val demo: Demo = when (action) {
            is GameAction.Tap -> Demo(desc, T_TAP, action.x, action.y, 0f, 0f, 90, 0.6f, 1, System.currentTimeMillis())
            is GameAction.Hold -> Demo(desc, T_HOLD, action.x, action.y, 0f, 0f, action.durationMs.toInt(), 0.6f, 1, System.currentTimeMillis())
            is GameAction.Swipe -> Demo(desc, T_SWIPE, action.x1, action.y1, action.x2, action.y2, action.durationMs.toInt(), 0.6f, 1, System.currentTimeMillis())
            else -> return
        }
        synchronized(lock) {
            // The touch is played a little later (queue), so the judgement waits longer.
            pendingEval = PendingEval(System.currentTimeMillis() + 1500L, desc, emptyList(), keyOf(pkg), demo)
        }
    }

    /** Rewards examples whose touch changed the screen, punishes the others. Must be called with the lock held. */
    private fun judgePending(desc: ByteArray, time: Long) {
        val pe = pendingEval ?: return
        if (time - pe.at < 600L) return
        pendingEval = null
        val worked = changedCells(pe.before, desc) >= EFFECT_CELLS
        for (d in pe.used) {
            if (worked) {
                d.score = min(3f, d.score + 0.2f)
                d.uses++
            } else {
                d.score = max(0.05f, d.score * 0.5f)
            }
        }
        val cloud = pe.cloud
        if (cloud != null && worked) {
            addDemo(pe.key, cloud, 0.6f)
            AdviceLog.info("یادگیری: حرکت موفق هوش مصنوعی ابری هم یاد گرفته شد")
        }
        bump(pe.key)
    }

    // ------------------------------------------------------------------ habits and hot spots

    /** Where the user presses most often (buttons and cells), strongest first. */
    private fun hotspots(key: String): List<Hotspot> {
        hotspotCache[key]?.let { return it }
        val list = ArrayList<Hotspot>()
        for (d in storeFor(key)) {
            if (d.type != T_TAP && d.type != T_HOLD) continue
            if (d.score < 0.5f) continue
            val w = d.score * (1f + 0.3f * min(6, d.uses - 1))
            var home: Hotspot? = null
            for (h in list) if (hypot(h.x - d.x, h.y - d.y) < 0.03f) { home = h; break }
            if (home == null) {
                list.add(Hotspot(d.x, d.y, w))
            } else {
                val nw = home.weight + w
                home.x = (home.x * home.weight + d.x * w) / nw
                home.y = (home.y * home.weight + d.y * w) / nw
                home.weight = nw
            }
        }
        list.sortByDescending { it.weight }
        hotspotCache[key] = list
        return list
    }

    /**
     * Pulls a touch of the cloud AI onto the nearest control that the user really presses (the AI often misses a button
     * by a few percent). Returns null when no learned control is close enough.
     */
    fun snap(pkg: String?, x: Float, y: Float): FloatArray? {
        return synchronized(lock) {
            val key = keyOf(pkg)
            if (storeFor(key).isEmpty()) {
                null
            } else {
                var best: Hotspot? = null
                var bestD = 0.045f
                for (h in hotspots(key)) {
                    if (h.weight < 1.5f) continue
                    val d = hypot(h.x - x, h.y - y)
                    if (d < bestD) { bestD = d; best = h }
                }
                val b = best
                if (b == null) null else floatArrayOf(b.x, b.y)
            }
        }
    }

    /** Text for the cloud AI: what the user did on similar screens, and which controls he uses. */
    private fun habitsHint(key: String, similar: List<FloatArray>): String {
        val sb = StringBuilder()
        if (similar.isNotEmpty()) {
            sb.append("LEARNED FROM THE PLAYER'S OWN GAMES. On screens very similar to this one the player touched: ")
            for (s in similar) {
                val kind = when (s[3].toInt()) { T_SWIPE -> "swipe from"; T_HOLD -> "hold at"; else -> "tap at" }
                sb.append(kind).append(" (%.2f,%.2f)".format(s[0], s[1])).append(" x").append(s[2].toInt()).append("; ")
            }
            sb.append("This is the player's habit, not always the best move: reuse these exact coordinates when the move is good, ")
            sb.append("and play a clearly stronger move when you see one. ")
        }
        val hs = hotspots(key)
        if (hs.size >= 2) {
            sb.append("Controls and cells the player presses often (x,y): ")
            for (i in 0 until min(6, hs.size)) sb.append("(%.2f,%.2f) ".format(hs[i].x, hs[i].y))
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ storage

    private fun keyOf(pkg: String?): String = (pkg ?: "unknown").replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun storeFile(key: String): String? {
        val ctx = AiSettings.appContext ?: return null
        val dir = File(ctx.filesDir, "coach")
        dir.mkdirs()
        return File(dir, "$key.bin").absolutePath
    }

    /** The examples of one game. Loaded from the phone's storage the first time. Must be called with the lock held. */
    private fun storeFor(key: String): ArrayList<Demo> {
        stores[key]?.let { return it }
        val list = ArrayList<Demo>()
        try {
            val path = storeFile(key)
            if (path != null && File(path).exists()) {
                DataInputStream(File(path).inputStream().buffered()).use { input ->
                    if (input.readInt() == MAGIC) {
                        val n = input.readInt()
                        for (i in 0 until n.coerceIn(0, MAX_DEMOS)) {
                            val desc = ByteArray(N)
                            input.readFully(desc)
                            val type = input.readInt()
                            val x = input.readFloat()
                            val y = input.readFloat()
                            val x2 = input.readFloat()
                            val y2 = input.readFloat()
                            val ms = input.readInt()
                            val score = input.readFloat()
                            val uses = input.readInt()
                            val at = input.readLong()
                            list.add(Demo(desc, type, x, y, x2, y2, ms, score, uses, at))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // A damaged file only means that learning starts again for this game.
        }
        stores[key] = list
        return list
    }

    private fun maybeSave(key: String) {
        val n = synchronized(lock) { unsaved[key] ?: 0 }
        if (n >= 8) saveKey(key)
    }

    private fun saveAll() {
        val keys = synchronized(lock) { ArrayList(unsaved.keys) }
        for (k in keys) saveKey(k)
    }

    private fun saveKey(key: String) {
        val snapshot: List<Demo> = synchronized(lock) {
            val s = stores[key] ?: return
            unsaved[key] = 0
            ArrayList(s)
        }
        Thread {
            try {
                val path = storeFile(key) ?: return@Thread
                val tmp = File("$path.tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(snapshot.size)
                    for (d in snapshot) {
                        out.write(d.desc)
                        out.writeInt(d.type)
                        out.writeFloat(d.x)
                        out.writeFloat(d.y)
                        out.writeFloat(d.x2)
                        out.writeFloat(d.y2)
                        out.writeInt(d.ms)
                        out.writeFloat(d.score)
                        out.writeInt(d.uses)
                        out.writeLong(d.at)
                    }
                }
                tmp.renameTo(File(path))
            } catch (t: Throwable) {
                // Not saving is harmless; the examples stay in memory.
            }
        }.start()
    }

    /** Number of stored examples for a game. */
    fun count(pkg: String?): Int = synchronized(lock) { storeFor(keyOf(pkg)).size }

    /** Forgets everything that was learned (all games). */
    fun clearAll() {
        synchronized(lock) {
            stores.clear()
            unsaved.clear()
            hotspotCache.clear()
            recent.clear()
            idleCandidates.clear()
            pendingRecord = null
            pendingEval = null
        }
        try {
            val ctx = AiSettings.appContext ?: return
            File(ctx.filesDir, "coach").listFiles()?.forEach { it.delete() }
        } catch (t: Throwable) {
            // ignore
        }
    }

    private const val MAGIC = 0x434F4143
}
