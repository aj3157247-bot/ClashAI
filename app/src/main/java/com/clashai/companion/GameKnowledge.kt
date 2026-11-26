package com.aiplayer.companion

/**
 * Game knowledge used by the online agent. Each game has keywords (to recognize it from the
 * package name or from the name the AI reports) and a short rule + strategy pack that is added
 * to the prompt once the game is known. A focused pack makes the model much stronger than a
 * generic prompt.
 */
class GamePack(val id: String, val title: String, val keywords: List<String>, val rules: String, val realtime: Boolean = false)

object GameKnowledge {

    val packs: List<GamePack> = listOf(
        GamePack(
            "clashroyale", "Clash Royale", listOf("clashroyale", "royale"),
            """
            CLASH ROYALE, played like a top-ladder player. Real-time: you are asked every 1.5-3 seconds, so every answer must be a COMPLETE decision.
            FIRST, in the "screen" field, always write: elixir=N; hand=slot1(cost),slot2(cost),slot3(cost),slot4(cost); threats=lane+unit+how far it has come; time=mm:ss. Read the elixir number on the drop (bottom right) and the cost on each card.
            In the "memo" field keep notes for yourself (max 200 chars): opponent cards seen so far, their last deploy and an elixir estimate, which lane is weak, whether you are ahead on towers.

            ARENA. Two lanes (left, right), a river with two bridges in the middle, 3 towers each side (2 princess towers + the king tower behind them). You can deploy ONLY on your own half (below the river), plus behind a lane where an enemy princess tower is destroyed.
            DEPLOY: SWIPE from the card in the bottom tray to the target spot (type swipe, ms 250). Keep the card swipe start exactly at the center of the card. Never target the bottom tray or the enemy half.
            Placement spots: behind your princess tower line = safe defense; at the bridge on your side = fast attack; in the center of your half behind the king = anti-air or building pull; far back (just in front of the king tower) = slow push start.

            ELIXIR DISCIPLINE (most important). Elixir regenerates 1 per 2.8 s (1 per 1.4 s in the last minute and overtime = double elixir, 1 per 0.9 s at triple).
            - Opening: do NOT play anything for the first seconds. At about 7-8 elixir play one cheap defensive/cycle card in the back, then react to the opponent. Answer wait while elixir is low or while you have nothing useful.
            - Never play a card you cannot afford (cost > elixir): answer wait.
            - Never let elixir sit at 10 for long: wasted elixir is lost. At 9-10 start a push or play a value card.
            - Positive trade = you spend less elixir than the opponent spent to answer you (for example 3-elixir Valkyrie kills a 5-elixir Witch). Seek them, avoid negative trades.

            DEFENSE. React to what the opponent actually deploys (their elixir is spent, so you are now ahead): place the counter BEHIND the princess tower, a little to the side so the tower also shoots.
            Tanks (Giant, Golem, Lava Hound, P.E.K.K.A, Mega Knight): kite with a cheap unit, then deal damage with high DPS (Inferno Tower/Dragon, Mini P.E.K.K.A, P.E.K.K.A, Barbarians).
            Swarms (Skeleton Army, Goblin Gang, Barbarians, Minion Horde, Bats): Zap / Log / Arrows / Fireball, or Valkyrie / Wizard / Baby Dragon splash. Do not use a single-target unit.
            Air (Balloon, Minions, Baby Dragon, Lava Hound, Inferno Dragon, Mega Minion): Musketeer, Archers, Minions, Wizard, Tesla, Inferno Tower, Mega Minion.
            Hog Rider / Battle Ram / Ram Rider / Royal Hogs: Cannon, Tesla, Mini P.E.K.K.A, Skeletons/Ice Spirit to distract, Bomb Tower, Valkyrie (Hogs).
            Ranged glass cannons (Princess, Dart Goblin, Musketeer, Archers, Wizard, Firecracker, Hunter): Zap, Log, Fireball, Arrows, or a fast unit (Hog, Prince, Ram) to run them down.
            Miner / Goblin Barrel / Skeleton Barrel / Graveyard: keep a cheap spell or a cheap ground unit/ building ready (Log, Zap, Skeletons, Ice Spirit, Cannon in the middle).
            Buildings (Cannon, Tesla, Bomb Tower, Inferno Tower): place in the middle of your half, 3-4 tiles in front of the king tower to pull and lock onto tanks and Hog Riders.
            Spells: use only to kill 2+ units, to finish a tower, or to counter a push cheaply. A 4-elixir Fireball on a 3-elixir target is a bad trade.

            OFFENSE. Push only on the lane where the opponent is weak and low on elixir (they just played something), usually after you win a defense: keep the surviving unit and add support behind it.
            Tank in front + ranged/splash support behind (Giant + Wizard, Golem + Night Witch, Hog + Musketeer behind the bridge). Spell cycle (Log, Fireball) on towers when ahead.
            Split pushes in double elixir: attack the weaker lane while defending the other. In overtime or sudden death, any tower destroyed wins: commit to the lane with the lowest tower health.
            Protect your own king tower: do not leave a lane empty when the opponent has 8+ elixir.

            CARD COSTS AND ROLES (trust the number printed on the card if it disagrees; cards change over time).
            1: Skeletons, Ice Spirit, Fire Spirit. 2: Goblins, Spear Goblins, Bomber, Bats, Wall Breakers, Ice Golem, Barbarian Barrel, Log, Zap, Giant Snowball, Rage, Goblin Curse.
            3: Knight, Archers, Minions, Skeleton Army, Guards, Princess, Dart Goblin, Mega Minion, Ice Wizard, Miner, Goblin Gang, Bandit, Royal Ghost, Fisherman, Firecracker, Cannon, Tombstone, Tornado, Arrows, Earthquake, Skeleton Barrel, Elixir Golem.
            4: Valkyrie, Musketeer, Baby Dragon, Mini P.E.K.K.A, Hog Rider, Dark Prince, Battle Ram, Lumberjack, Inferno Dragon, Electro Wizard, Hunter, Night Witch, Zappies, Flying Machine, Magic Archer, Mighty Miner, Skeleton King, Golden Knight, Battle Healer, Tesla, Mortar, Bomb Tower, Furnace, Goblin Hut, Goblin Drill, Fireball, Poison, Freeze.
            5: Giant, Balloon, Witch, Barbarians, Prince, Wizard, Minion Horde, Bowler, Executioner, Ram Rider, Rascals, Cannon Cart, Royal Hogs, Archer Queen, Monk, Electro Dragon, Inferno Tower, Graveyard.
            6: Giant Skeleton, Royal Giant, Sparky, Elite Barbarians, Goblin Giant, X-Bow, Elixir Collector, Rocket, Lightning. 7: P.E.K.K.A, Lava Hound, Mega Knight, Royal Recruits, Barbarian Hut. 8: Golem. 9: Three Musketeers.
            Win conditions: Hog Rider, Giant, Golem, Balloon, Royal Giant, Ram Rider, Miner, Graveyard, X-Bow, Mortar, Lava Hound, P.E.K.K.A, Mega Knight. Tanks and win conditions go FIRST in a push; support goes BEHIND.

            MENUS (never spend money or gems). To start tap Battle (or Training Camp / Friendly battle if shown). After a match tap OK / Continue / Claim free reward. Ignore the shop, offers, pass royale, emote chat, deck change, chest unlock with gems. If a dialog asks for payment or login, answer wait.

            ALL GAME MODES. At the start of every match read the mode name or the banner on the screen and write mode=... in the "screen" field. The banner rules always win over the notes below; the elixir discipline above applies to every mode.
            - 1v1 ladder / Path of Legends / ranked / challenge / tournament / friendly / Training Camp: the standard plan above. Regular time, then elixir speeds up near the end, then overtime where the first destroyed tower decides, so the king tower must stay safe.
            - 2v2: an ally fights next to you and has its own hand and elixir. Defend the lane that is attacked and let the ally cover the other lane; support the ally's push by adding a unit behind its tank; never stack two heavy pushes on the same lane without elixir; do not tap the chat or emotes.
            - Draft / Mega Draft / Draft challenge: first comes a card-picking screen. Tap ONE card per pick (never skip). Build a balanced deck: 1-2 win conditions, 1 small spell (Zap/Log/Arrows) and 1 big spell (Fireball/Poison), 1 anti-air, 1 swarm answer, 1 building or tank killer, and cheap cycle cards; average cost about 3.5-4. Do not pick only expensive cards. After the picks play by the normal plan with those cards.
            - Triple / Double / Rage elixir and other fast-elixir modes: elixir comes much faster from the start, so never sit at 10; open at once with a real play, cycle cheap cards, keep constant pressure on one lane and defend with cheap efficient cards. Answer wait only when you truly lack elixir.
            - Sudden Death / overtime: the first tower to fall wins. Defend with full force, never trade your last good counter, and counter-push the lane where the opponent just spent elixir; aim at the weakest princess tower.
            - Champions, Heroes and Evolutions: a champion or hero card has an ability button next to the hand once the unit is on the field. Tap it when the elixir cost is affordable and it will hit a group or save the unit (Archer Queen cloak, Golden Knight dash, Skeleton King summon, Mighty Miner, Monk). Evolved cards (marked evolution cards) are strongest after you have cycled them twice; play them when you can protect them.
            - Boss battle / co-op and party modes (Touchdown, Heist, Rampant Growth, Goblin Queen's Revenge and similar): they change the goal. Read the rules banner shown at the start, follow the new objective first (carry, destroy, collect, survive), and use the same elixir and counter thinking. If the objective is unclear, wait until it is visible instead of tapping randomly.
            - Clan War / River Race duels and boss attacks: use only free battles, never buy extra decks or attacks.

            MERGE TACTICS MODE: if the screen shows a hex-tile board, "Round / Battle Phase", Time left and 4 player portraits on top, it is an auto-battler. Cards in the tray cost elixir; deploy by swiping a card onto a free hex on YOUR side (bottom half); troops fight by themselves during the battle phase, so then answer wait.
            Buy and place troops during the preparation phase while you have elixir; put tanks in the front row (closest to the enemy) and ranged troops in the back; three identical troops merge into a stronger one, so prefer the card you already own; keep a balanced team; do not leave elixir unused.
            """.trimIndent(), true
        ),
        GamePack(
            "racing", "Car racing", listOf("racing", "asphalt", "carx", "needforspeed", "trafficracer", "hillclimb", "drift", "rally", "burnout", "speed", "forza", "csr"),
            """
            CAR RACING (real-time). First find the controls: usually steer left / right arrows (or tilt) on one side, gas (accelerate) and brake on the other, sometimes a nitro button.
            Keep the gas held down for the whole plan (hold with a long ms such as 2500-4000) in group 1, together with a short steering tap or hold in the same group.
            Look at the road 1-2 seconds ahead: steer early and gently toward the open lane, away from traffic and walls; brake (hold the brake) only before sharp curves.
            Use nitro on straight sections, not in curves. Drift games: tap/hold steering through the corner then release.
            If an obstacle is directly ahead in your lane, change lanes toward the emptier side.
            If a menu, result screen or reward popup shows: tap Next / Continue / Race / Start (free options only, never Buy or watch-ad-for-reward).
            """.trimIndent(), true
        ),
        GamePack(
            "shooter", "Shooter / war game", listOf("shooter", "pubg", "freefire", "callofduty", "codm", "warzone", "modernstrike", "sniper", "fps", "bullet", "gunship", "critical", "standoff", "bgmi", "shadowgun"),
            """
            SHOOTER / WAR GAME (real-time, touch controls). The left thumb area is the MOVE joystick; the right half is AIM (drag to turn the camera) and a FIRE button (usually on the right).
            Each plan (3-5 seconds): (a) move with a hold on the joystick (group 1), (b) aim by a short swipe on the right half toward the enemy, then (c) fire with a hold on the fire button (group 1 or 2) for 300-800 ms bursts.
            Enemies: red name tags, red markers or moving silhouettes; the crosshair is at the screen center. Move enemy toward the center with a small swipe, then fire.
            Stay behind cover, strafe sideways while shooting, retreat when health is low (health bar), reload when the ammo counter is 0, pick up weapons or health shown nearby.
            In training, single player or bot modes this is fine; in menus tap Play / Start / Continue. Never tap Buy, Offers, Chat.
            """.trimIndent(), true
        ),
        GamePack(
            "puzzle", "Puzzle games", listOf("puzzle", "watersort", "sortpuz", "blockpuzzle", "woodoku", "blockblast", "jigsaw", "merge", "bubbleshooter", "bubble", "wordcross", "wordconnect", "unblock", "flow", "mazes"),
            """
            PUZZLE GAMES. Read the objective at the top and the rules shown on the screen, then solve with logic, not random taps.
            Water / color sort: move liquid from a tube to a tube only if the top colors match or the target is empty and there is room; finish tubes of a single color; do not fill empty tubes early.
            Block puzzle (1010 / Woodoku / Block Blast): DRAG a piece from the tray to the board with a swipe (ms 300); choose spots that complete rows, columns or squares, keep large empty areas, never leave isolated single holes.
            Merge games: drag identical items onto each other; keep space free on the board. Bubble shooter: swipe/aim to a spot where the same color touches 2 or more; prefer shots that drop hanging groups.
            Word games: connect letters with one swipe along adjacent letters. Pipe / flow: drag from one dot to the same-color dot without crossing other paths.
            If the result of the last move was "NO visible change", try a different piece or a different spot.
            """.trimIndent(), false
        ),
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
            "match3", "Match-3 (Candy Crush)", listOf("match3", "candy", "bejeweled", "jewel", "gems", "gardenscapes", "homescapes", "toonblast", "blast", "royalmatch", "fishdom"),
            """
            MATCH-3 (Candy Crush style). A move is ONE swipe from a tile to its ADJACENT tile (one cell away, center to center), which swaps them.
            Method: first read the board as rows and columns of colors (look at the objective and moves left at the top). Then list swaps that make 3+ in a row or column.
            Priority: 1) make a color bomb (5 in a row) or any special candy (4 in a row = striped, L/T shape = wrapped); 2) activate a special candy or combine two specials (they are the strongest);
            3) clear the level objective (jelly, ingredients, blockers) - moves close to the objective first; 4) moves low on the board cause cascades, but when moves left are few prefer moves near the objective;
            5) ingredients (cherries/hazelnuts): clear the tiles under them so they fall to the bottom.
            Aim exactly at the cell centers of the two tiles. If the swipe had NO visible change, the tiles were not adjacent or the swap makes no match: pick another pair.
            Tap Play / Next / Continue on menus; never tap buy buttons, extra moves with gems, lives purchases or the daily-offer popups.
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
Real-time action games (racing, runners, shooters, tower-defense / card battles) need REAL-TIME MODE, which is switched on automatically for the known ones. For an unknown fast game, set game_id to "unknown" and answer with a careful single action. In REAL-TIME MODE keep the car or character safe: stay on the road or path, avoid obstacles and enemies' fire, collect rewards, and use the visible controls (hold the gas, steer left or right, jump, fire).
"""
}
