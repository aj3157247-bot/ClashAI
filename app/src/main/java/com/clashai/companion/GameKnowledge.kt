package com.aiplayer.companion

/**
 * Game knowledge used by the online agent. Each game has keywords (to recognize it from the
 * package name or from the name the AI reports) and a short rule + strategy pack that is added
 * to the prompt once the game is known. A focused pack makes the model much stronger than a
 * generic prompt.
 */
class GamePack(val id: String, val title: String, val keywords: List<String>, val rules: String)

object GameKnowledge {

    val packs: List<GamePack> = listOf(
        GamePack(
            "ludo", "Ludo", listOf("ludo", "parchis", "mensch"),
            """
            LUDO. Only act when it is the player's turn. Roll by tapping the die or roll button, then read the die by counting the dots one by one.
            After the roll, tap the TOKEN you want to move (not the destination).
            Priority when choosing a token:
            1) capture an opponent token that is not on a safe cell (star or start cell);
            2) finish a token (exact count into the center);
            3) move a token into the colored home lane;
            4) with a 6, bring a token out of base (especially when no token is on the board);
            5) land on a safe cell;
            6) move a token that an opponent token 1-6 cells behind could capture;
            7) never land 1-6 cells in front of an opponent token unless it is a safe cell;
            8) otherwise advance the token closest to its home lane.
            A 6 gives another roll. If only one legal move exists, tap that token. If no legal move exists, the turn passes by itself.
            """.trimIndent()
        ),
        GamePack(
            "chess", "Chess", listOf("chess", "lichess"),
            """
            CHESS. You play the side at the bottom of the board unless the screen clearly shows otherwise.
            A move is two taps: the piece, then the destination square. Return BOTH taps in the actions list in one answer.
            Before every move: list the opponent's checks, captures and threats after your move; never leave a piece undefended or en prise.
            Prefer: checkmate > winning material > safe development, center control, early castling, king safety > improving the worst piece.
            Promotion: choose the queen. If a promotion or confirm dialog appears, handle it first.
            """.trimIndent()
        ),
        GamePack(
            "checkers", "Checkers / Draughts", listOf("checkers", "draughts", "dama", "dame"),
            """
            CHECKERS / DRAUGHTS. A move is two taps: the piece, then the destination. A multi-jump is one tap on the piece and then one tap per landing square.
            Captures are usually mandatory: if any capture exists, you must make one (choose the one that captures the most or leaves you safe).
            Keep the back row as long as possible, hold the center, trade when ahead, avoid leaving a piece that can be jumped. Kings move in all directions.
            """.trimIndent()
        ),
        GamePack(
            "backgammon", "Backgammon", listOf("backgammon", "nardi", "narde", "tavla", "gammon"),
            """
            BACKGAMMON. Roll the dice when it is your turn, then move checkers one die at a time (tap the checker, then the target point; if the app uses dragging, use a swipe).
            Priorities: hit an opponent blot, make points (two or more checkers), avoid leaving single checkers (blots) within 6 pips of enemy checkers, build a prime, then bear off when all checkers are home.
            """.trimIndent()
        ),
        GamePack(
            "dominoes", "Dominoes", listOf("domino"),
            """
            DOMINOES. Match a tile to an open end. Play high doubles early, keep a variety of numbers in hand, and block the numbers the opponent lacks.
            Tap the tile, then tap the end of the chain where it should go. If you cannot play, draw or pass using the visible button.
            """.trimIndent()
        ),
        GamePack(
            "connect4", "Connect Four", listOf("connect4", "connectfour", "4inarow", "fourinarow"),
            """
            CONNECT FOUR. Tap the TOP of the column where you want to drop a disc.
            1) win immediately if you can; 2) block the opponent's immediate win; 3) do not play a column that lets the opponent win right above you;
            4) prefer the center column, then the columns next to it; 5) build double threats (two ways to win at once).
            """.trimIndent()
        ),
        GamePack(
            "tictactoe", "Tic-tac-toe", listOf("tictactoe", "tictac", "xox"),
            """
            TIC-TAC-TOE. Win if you can, otherwise block, otherwise take the center, then a corner opposite the opponent's corner, then any corner, then an edge. Create forks.
            """.trimIndent()
        ),
        GamePack(
            "match3", "Match-3", listOf("match3", "candy", "bejeweled", "jewel", "gems", "gardenscapes", "homescapes", "toonblast", "blast"),
            """
            MATCH-3. A move is a swipe from one tile to the ADJACENT tile (one cell away, center to center).
            Follow the level objective shown at the top. Prefer moves that make 4 or 5 in a row or L/T shapes (special pieces), combine specials, and work near the objective items.
            Do not waste moves when the move counter is low; do not tap booster purchase buttons.
            """.trimIndent()
        ),
        GamePack(
            "2048", "2048", listOf("2048"),
            """
            2048. A move is a swipe in one direction. Keep the biggest tile in one corner, build a descending chain along an edge, and use only 3 of the 4 directions as long as possible (avoid the swipe that moves the corner tile away).
            """.trimIndent()
        ),
        GamePack(
            "sudoku", "Sudoku", listOf("sudoku"),
            """
            SUDOKU. Select a cell, then tap the number on the keypad. Use only certain logic (a cell with a single possible digit, or a digit with a single possible cell in a row, column or box). Never guess.
            """.trimIndent()
        ),
        GamePack(
            "solitaire", "Solitaire", listOf("solitaire", "klondike", "freecell", "spider"),
            """
            SOLITAIRE. Priorities: reveal face-down cards, move aces and then low cards to the foundations when safe, empty a column only if you have a king to place, keep colors alternating, draw from the stock when no move exists.
            Tapping a card usually moves it automatically to the best place.
            """.trimIndent()
        ),
        GamePack(
            "cards", "Card games", listOf("hokm", "shelem", "pasur", "belote", "tarneeb", "spades", "hearts", "uno", "rummy", "cards"),
            """
            CARD GAMES (trick-taking or matching). Tap the card you want to play. Follow suit when required. Win a trick with the lowest card that wins it, throw away the lowest useless card when you cannot win,
            keep trump (or wild cards) for important moments, count the cards that have already been played. In UNO-style games match color or number, hold wild cards for later, and press the UNO button when you have one card left.
            """.trimIndent()
        ),
        GamePack(
            "pool", "Pool / Billiards", listOf("8ballpool", "billiard", "snooker"),
            """
            POOL. Choose the easiest ball to pot, aim through the cue ball with a drag, and keep the cue ball safe from scratches. If the aiming control is not clear from the screen, wait.
            """.trimIndent()
        )
    )

    private fun norm(text: String?): String =
        (text ?: "").lowercase().filter { it.isLetterOrDigit() }

    /** Finds the pack for a package name or a game name, or null when unknown. */
    fun identify(text: String?): GamePack? {
        val n = norm(text)
        if (n.isEmpty() || n == "unknown") return null
        for (p in packs) {
            if (n == p.id) return p
            for (k in p.keywords) if (n.contains(k)) return p
        }
        return null
    }

    fun byId(id: String?): GamePack? = packs.firstOrNull { it.id == id }

    /** Ids the model may answer with, listed in the prompt. */
    fun idList(): String = packs.joinToString(", ") { it.id } + ", unknown"

    const val GENERIC_RULES = """
GENERIC GAME. Work out the objective from the screen. In menus tap Play / Start / Continue / Next / Claim (free rewards only).
In tutorials follow the highlighted hint. Close ordinary dialogs with their X or Close button. Make the move that progresses the objective the most under the game's rules.
Real-time action games (racing, runners, shooters) are too fast for this controller: if the game needs reflexes, answer wait.
"""
}
