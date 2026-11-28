package com.aiplayer.companion

import android.graphics.Bitmap
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * General module for any game, powered by a cloud vision model.
 *
 * What makes it stronger than a plain "screenshot in, tap out" loop:
 *  - it recognizes the game (package name first, then the model's own answer) and then sends a
 *    focused rules and strategy pack for that game;
 *  - it draws a labeled coordinate grid on the screenshot so touches land precisely;
 *  - it remembers its last actions and whether each one changed the screen, so it does not loop
 *    on a tap that did nothing;
 *  - it can send a short sequence (for example piece, then destination) in one request;
 *  - it waits for animations to settle, backs off on rate limits and retries server errors.
 */
class GrokGameModule : GameModule {

    override val id: String = "grok_general"

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var pending = false

    // Guarded by `this`.
    private val queue = ArrayDeque<GameAction>()
    private val dueOffsets = ArrayDeque<Long>()
    @Volatile private var planStart = 0L
    private val history = ArrayList<String>()

    @Volatile private var gameId: String? = null
    @Volatile private var lockedByPackage = false
    @Volatile private var currentPackage: String? = null
    @Volatile private var waitStreak = 0
    /** Notes the AI keeps for itself between requests (opponent cards seen, elixir estimate...). */
    @Volatile private var memo: String = ""
    @Volatile private var backoffUntil = 0L
    private var lastCoachAt = 0L
    private var coachWaitSince = 0L

    private var lastRequestAt = 0L

    /** Grows after every "too many requests" answer and shrinks after good answers, so the bot adapts to the API limit. */
    @Volatile private var adaptiveGapMs = 0L
    private var lastActionAt = 0L
    private var lastSignature: IntArray? = null

    // Signature of the screen that the last action was based on, and whether we still have to judge its effect.
    @Volatile private var actionSignature: IntArray? = null
    @Volatile private var awaitingEffect = false

    override fun matches(packageName: String?, frame: Bitmap): Boolean {
        // While the bot plays with what it learned, this module also works without a cloud key (pure imitation).
        if (!LearningCoach.playing && !hasCloud()) return false
        if (packageName != currentPackage) resetFor(packageName)
        return true
    }

    private fun hasCloud(): Boolean =
        AiSettings.cloudAllowed() && (AiSettings.apiKey.isNotBlank() || AiSettings.hasBackup())

    private fun resetFor(packageName: String?) {
        currentPackage = packageName
        synchronized(this) {
            queue.clear()
            dueOffsets.clear()
            history.clear()
        }
        val pack = GameKnowledge.identify(packageName)
        gameId = pack?.id
        lockedByPackage = pack != null
        AiSettings.autoRealtime = pack?.realtime == true
        waitStreak = 0
        memo = ""
        lastSignature = null
        actionSignature = null
        awaitingEffect = false
    }

    override fun decide(frame: Bitmap): GameAction {
        val now = System.currentTimeMillis()
        if (now < backoffUntil) return GameAction.Wait

        // 1) Play queued touches first, spaced out so the game can react between them.
        synchronized(this) {
            if (queue.isNotEmpty()) {
                val offset = if (dueOffsets.isNotEmpty()) dueOffsets.first() else 0L
                if (offset > 0L) {
                    // A timed plan: play every touch at its own moment.
                    if (now < planStart + offset) return GameAction.Wait
                } else if (now - lastActionAt < (if (AiSettings.realtime) 250L else ACTION_GAP_MS)) return GameAction.Wait
                lastActionAt = now
                if (dueOffsets.isNotEmpty()) dueOffsets.removeFirst()
                return queue.removeFirst()
            }
        }

        // 2) Ask the AI again only when it makes sense.
        if (pending) return GameAction.Wait

        // 2a) Play with what the engine learned: a known screen is answered at once, without the cloud.
        var coach: LearningCoach.CoachResult? = null
        if (LearningCoach.playing) {
            if (now - lastActionAt < COACH_GAP_MS || now - lastCoachAt < 300L) return GameAction.Wait
            lastCoachAt = now
            val res = LearningCoach.analyse(currentPackage, frame)
            coach = res
            val act = res.action
            if (act != null) {
                lastActionAt = now
                coachWaitSince = 0L
                LearningCoach.noteExecuted(currentPackage, res)
                AdviceLog.info("یادگیری: حرکت آموخته‌شده اجرا شد (اطمینان ${(res.confidence * 100).toInt()}٪)")
                return act
            }
            if (res.wait) {
                if (coachWaitSince == 0L) coachWaitSince = now
                if (now - coachWaitSince < COACH_WAIT_MAX_MS) {
                    AdviceLog.info("یادگیری: روی این صفحه خودت هم صبر می‌کردی؛ منتظر می‌مانم")
                    return GameAction.Wait
                }
            } else {
                coachWaitSince = 0L
            }
            if (!hasCloud()) {
                AdviceLog.info("یادگیری: این صفحه را قبلاً ندیده‌ام و هوش مصنوعی ابری خاموش است؛ چند بار خودت روی آن بازی کن")
                return GameAction.Wait
            }
        }
        if (now - lastActionAt < (if (AiSettings.realtime) 200L else SETTLE_MS)) return GameAction.Wait
        val royale = gameId == "clashroyale"
        // Free API plans allow only a limited number of requests and tokens per minute. Asking too often makes every
        // answer a "rate limit" error and the bot then stops for many seconds, which is far slower than a steady pace.
        // So the pace follows the gap set in the settings (about a third of it, 1.5 to 4 s) and is shorter when the elixir is nearly full.
        val baseGap = (AiSettings.requestGapMs * 35L / 100L).coerceIn(1500L, 4000L)
        val realGap = if (royale) baseGap else 1500L
        val elixirNow = if (royale) ClashLayout.elixirOf(frame) else -1f
        val paceGap = if (royale && elixirNow >= 9f) Math.min(realGap, 1500L) else realGap
        if (now - lastRequestAt < Math.max(adaptiveGapMs, if (AiSettings.realtime) paceGap else AiSettings.requestGapMs)) return GameAction.Wait

        // Clash Royale: the elixir is read from the bar on the phone, with no cloud call. With less than 2 elixir
        // nothing useful can be played, so no request is made (saves the AI quota and keeps it fast when it matters).
        val elixir = elixirNow
        if (royale && elixir >= 0f && elixir < 2f) {
            AdviceLog.info("کلش رویال | الکسیر %.1f | منتظر الکسیر".format(elixir))
            return GameAction.Wait
        }

        val sig = signature(frame)
        val previous = lastSignature
        val changed = previous == null || diff(previous, sig) > CHANGE_THRESHOLD
        val heartbeat = now - lastRequestAt > HEARTBEAT_MS
        if (!changed && !heartbeat && !awaitingEffect && !AiSettings.realtime) return GameAction.Wait

        // Judge the effect of the last action.
        if (awaitingEffect) {
            val base = actionSignature
            val effect = base != null && diff(base, sig) > EFFECT_THRESHOLD
            synchronized(this) {
                if (history.isNotEmpty()) {
                    val last = history.size - 1
                    history[last] = history[last] + if (effect) " -> the screen changed" else " -> NO visible change"
                }
            }
            awaitingEffect = false
        }

        lastRequestAt = now
        lastSignature = sig
        pending = true

        val grid = AiSettings.gridOverlay && !royale
        val ctxHistory = synchronized(this) { history.toList() }
        val elixirHint = if (royale && elixir >= 0f)
            "MEASURED ELIXIR (exact, from the bar): %.1f. ".format(elixir) +
                (if (elixir >= 9.5f) "The bar is full: play a card now, wasting elixir is a mistake. " else "") +
                "Only cards whose cost is at most %d can be played now. ".format(elixir.toInt())
        else ""
        val coachHint = coach?.hint ?: ""
        val hint = coachHint + (if (coachHint.isNotEmpty()) " " else "") + elixirHint + if (waitStreak >= 2 && !royale)
            "The last answers were wait. If a roll, play, pass, end-turn, continue or next button is visible and it is the player's turn, tap it now. Do not answer wait."
        else ""
        val board = if (royale) null else findBoard(frame)
        val boardNote = if (board == null) "" else
            "A checkerboard was detected. The controller drew its cells in cyan, labeled A1 (top-left) to H8 (bottom-right): letter = column left to right, number = row top to bottom. " +
                "Decide which cell to touch and aim at the exact center of that cell; the touch is snapped to the cell center automatically. "
        val ctx = AgentContext(gameId, ctxHistory, boardNote + hint, grid, memo)

        // The frame is recycled by AgentLoop after this call, so render a copy for the worker.
        val image = GridOverlay.render(frame, if (royale) 600 else if (AiSettings.realtime) 720 else 1080, grid, board)
        val coachDesc = coach?.desc
        val sentW = image.width.toFloat()
        val sentH = image.height.toFloat()
        val frameW = frame.width.toFloat()
        val frameH = frame.height.toFloat()

        AdviceLog.info("در حال ارسال تصویر بازی به هوش مصنوعی...")
        worker.execute {
            try {
                val advice = AiRouter.ask(image, ctx)
                AdviceLog.update(advice)
                learnGame(advice)
                if (advice.memo.isNotBlank()) memo = advice.memo

                val planned = mergeGroups(
                    advice.steps.mapNotNull { st -> toAction(st, sentW, sentH, board, frameW, frameH)?.let { a -> Triple(st.group, st.at, a) } }
                )
                var actions: List<GameAction> = planned.map { it.second }
                val offsets: List<Long> = planned.map { it.first }
                if (!advice.myTurn) actions = emptyList()
                if (LearningCoach.playing) LearningCoach.noteCloud(currentPackage, coachDesc, actions.firstOrNull())

                adaptiveGapMs = adaptiveGapMs * 9L / 10L
                waitStreak = if (actions.isEmpty()) waitStreak + 1 else 0
                AdviceLog.info(
                    AdviceLog.lastText + " | " + AiRouter.lastNote + " | " +
                        (if (actions.isEmpty()) "بدون لمس (wait)" else actions.joinToString(" ، ") { describe(it) })
                )

                synchronized(this) {
                    val entry = if (actions.isEmpty()) "wait: " + advice.reason
                    else actions.joinToString(" then ") { describeEn(it) } + ": " + advice.reason
                    val lastIsWait = history.isNotEmpty() && history[history.size - 1].startsWith("wait")
                    if (actions.isNotEmpty() || !lastIsWait) history.add(entry.take(160))
                    while (history.size > HISTORY_MAX) history.removeAt(0)
                    queue.clear()
                    dueOffsets.clear()
                    queue.addAll(actions)
                    if (actions.isNotEmpty()) dueOffsets.addAll(offsets)
                    planStart = System.currentTimeMillis()
                }
                if (actions.isNotEmpty()) {
                    actionSignature = sig
                    awaitingEffect = true
                }
            } catch (e: RateLimitException) {
                val rt = AiSettings.realtime
                val wait = if (rt) Math.min(3L, Math.max(2L, e.retryAfterSeconds)) else Math.max(5L, e.retryAfterSeconds)
                backoffUntil = System.currentTimeMillis() + wait * 1000L
                adaptiveGapMs = if (rt) Math.min(4000L, Math.max(1500L, adaptiveGapMs * 3L / 2L + 500L))
                else Math.min(30000L, Math.max(AiSettings.requestGapMs, adaptiveGapMs) * 2L)
                AdviceLog.error("سقف سرعت API پر شد؛ $wait ثانیه صبر می‌کند و فاصله‌ی درخواست‌ها بیشتر شد (حالا ${adaptiveGapMs / 1000} ثانیه)")
            } catch (e: Exception) {
                backoffUntil = System.currentTimeMillis() + (if (AiSettings.realtime) 1500L else 3000L)
                AdviceLog.error(e.message ?: "خطای نامشخص")
            } finally {
                image.recycle()
                pending = false
            }
        }
        return GameAction.Wait
    }

    /** Remembers the game the model reported, unless the package name already told us. */
    private fun learnGame(advice: Advice) {
        if (lockedByPackage) return
        val found = GameKnowledge.identify(advice.gameId) ?: GameKnowledge.identify(advice.game)
        if (found != null) {
            gameId = found.id
            AiSettings.autoRealtime = found.realtime
        }
    }

    /**
     * Converts the model's answer into a touch. The model is told to use 0..1, but sometimes it
     * answers in pixels of the image it saw. Values above 1 are treated as pixels.
     */
    private fun toAction(
        s: Step, imageW: Float, imageH: Float,
        board: BoardDetector.Geo?, frameW: Float, frameH: Float
    ): GameAction? {
        fun norm(v: Float, size: Float): Float = (if (v > 1f) v / size else v).coerceIn(0f, 1f)

        /** If the point is on a detected board, move it to the center of its cell. */
        fun snap(nx: Float, ny: Float): FloatArray {
            if (board == null) return floatArrayOf(nx, ny)
            val px = nx * frameW
            val py = ny * frameH
            if (!board.contains(px, py)) return floatArrayOf(nx, ny)
            val col = ((px - board.x0) / board.cell).toInt().coerceIn(0, 7)
            val row = ((py - board.y0) / board.cell).toInt().coerceIn(0, 7)
            return floatArrayOf(board.centerX(col) / frameW, board.centerY(row) / frameH)
        }

        if (s.type == "card") {
            // Clash Royale: slot (1..4) + lane + spot -> exact touch from the card to the arena.
            if (s.slot !in 1..4) return null
            val from = ClashLayout.slotCenter(s.slot)
            val spot = if (s.lane.isNotBlank()) ClashLayout.spot(s.lane, s.where) else null
            val tx: Float
            val ty: Float
            if (spot != null) {
                tx = spot[0]; ty = spot[1]
            } else {
                tx = norm(s.x, imageW)
                val rawY = norm(s.y, imageH)
                ty = if (s.free) rawY.coerceIn(ClashLayout.ARENA_TOP, ClashLayout.OWN_BOTTOM)
                else rawY.coerceIn(ClashLayout.OWN_TOP, ClashLayout.OWN_BOTTOM)
            }
            return GameAction.Swipe(from[0], from[1], tx, ty, 280L)
        }

        val p1raw = snap(norm(s.x, imageW), norm(s.y, imageH))
        // Pull the touch onto the control that the user really presses (not on a board: there the cells are exact).
        val p1 = if (board == null) (LearningCoach.snap(currentPackage, p1raw[0], p1raw[1]) ?: p1raw) else p1raw
        return when (s.type) {
            "tap" -> GameAction.Tap(p1[0], p1[1])
            "hold" -> GameAction.Hold(p1[0], p1[1], if (s.ms > 0L) s.ms else 500L)
            "swipe" -> {
                val p2 = snap(norm(s.x2, imageW), norm(s.y2, imageH))
                var ty = p2[1]
                var dur = if (s.ms > 0L) s.ms else 300L
                if (gameId == "clashroyale" && p1[1] > ClashLayout.TRAY_TOP) {
                    // A card dragged out of the tray: it must land on the player's half and never back on the tray.
                    ty = ty.coerceIn(ClashLayout.OWN_TOP, ClashLayout.OWN_BOTTOM)
                    dur = dur.coerceIn(200L, 450L)
                }
                GameAction.Swipe(p1[0], p1[1], p2[0], ty, dur)
            }
            else -> null
        }
    }

    /** Actions that share a group number (> 0) are played together as one multi-finger gesture. */
    private fun mergeGroups(list: List<Triple<Int, Long, GameAction>>): List<Pair<Long, GameAction>> {
        val out = ArrayList<Pair<Long, GameAction>>()
        val done = HashSet<Int>()
        for (item in list) {
            val g = item.first
            if (g <= 0) {
                out.add(Pair(item.second, item.third))
            } else if (done.add(g)) {
                val parts = list.filter { it.first == g }.map { it.third }
                out.add(Pair(item.second, if (parts.size == 1) parts[0] else GameAction.Multi(parts)))
            }
        }
        return out
    }

    /** Detects an 8 x 8 board (chess, checkers...) so the grid can be drawn and touches snapped. */
    private fun findBoard(frame: Bitmap): BoardDetector.Geo? {
        return try {
            val w = frame.width
            val h = frame.height
            val px = IntArray(w * h)
            frame.getPixels(px, 0, w, 0, 0, w, h)
            val g = BoardDetector.detect(px, w, h)
            if (g != null && BoardDetector.isCheckerboard(px, w, h, g)) g else null
        } catch (e: Exception) {
            null
        }
    }

    private fun describe(action: GameAction): String = when (action) {
        is GameAction.Tap -> "لمس (%.2f, %.2f)".format(action.x, action.y)
        is GameAction.Hold -> "نگه‌داشتن (%.2f, %.2f)".format(action.x, action.y)
        is GameAction.Swipe -> "کشیدن (%.2f, %.2f) به (%.2f, %.2f)".format(action.x1, action.y1, action.x2, action.y2)
        is GameAction.Multi -> action.parts.joinToString(" + ") { describe(it) }
        else -> "wait"
    }

    private fun describeEn(action: GameAction): String = when (action) {
        is GameAction.Tap -> "tap(%.2f,%.2f)".format(action.x, action.y)
        is GameAction.Hold -> "hold(%.2f,%.2f)".format(action.x, action.y)
        is GameAction.Swipe -> "swipe(%.2f,%.2f->%.2f,%.2f)".format(action.x1, action.y1, action.x2, action.y2)
        is GameAction.Multi -> action.parts.joinToString("+") { describeEn(it) }
        else -> "wait"
    }

    /** A coarse brightness grid. Used to detect whether the screen has changed. */
    private fun signature(frame: Bitmap): IntArray {
        val out = IntArray(GRID * GRID)
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val x = ((gx + 0.5f) / GRID * frame.width).toInt().coerceIn(0, frame.width - 1)
                val y = ((gy + 0.5f) / GRID * frame.height).toInt().coerceIn(0, frame.height - 1)
                val p = frame.getPixel(x, y)
                out[gy * GRID + gx] = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            }
        }
        return out
    }

    private fun diff(a: IntArray, b: IntArray): Int {
        var sum = 0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / (a.size * 3)
    }

    companion object {
        private const val COACH_GAP_MS = 700L
        private const val COACH_WAIT_MAX_MS = 25_000L
        private const val ACTION_GAP_MS = 900L
        private const val SETTLE_MS = 1500L
        private const val HEARTBEAT_MS = 12000L
        private const val CHANGE_THRESHOLD = 6
        private const val EFFECT_THRESHOLD = 3
        private const val HISTORY_MAX = 6
        private const val GRID = 16
    }
}
