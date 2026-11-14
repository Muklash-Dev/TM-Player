package com.muklashdev.tmplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Offline Games Hub — six tiny built-in games, 100% offline, no ads:
 * 2048, Tic-Tac-Toe, Slide Puzzle, Color Match, Sudoku, Falling Blocks.
 * High scores live in SharedPreferences ("tm_games").
 */
public class GamesActivity extends Activity {

    int cBg, cCard, cCard2, cText, cDim, cAccent;
    SharedPreferences gp;
    LinearLayout root;
    ScrollView hubScroll;
    LinearLayout hubCol, gameCol;
    final Handler ui = new Handler(Looper.getMainLooper());
    Runnable activeLoop;
    final Random rnd = new Random();

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs prefs = new Prefs(this);
        setTheme(prefs.isDark(this) ? R.style.AppThemeDark : R.style.AppThemeLight);
        boolean d = prefs.isDark(this);
        cBg = d ? 0xFF121826 : 0xFFF4F7FB;
        cCard = d ? 0xFF1A2336 : 0xFFFFFFFF;
        cCard2 = d ? 0xFF2A3650 : 0xFFE8EEF6;
        cText = d ? 0xFFEAF0F8 : 0xFF0B1220;
        cDim = d ? 0xFF93A3BA : 0xFF42536B;
        cAccent = prefs.accent(this);
        gp = getSharedPreferences("tm_games", MODE_PRIVATE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hubScroll = new ScrollView(this);
        hubScroll.setBackgroundColor(cBg);
        hubCol = new LinearLayout(this);
        hubCol.setOrientation(LinearLayout.VERTICAL);
        hubCol.setPadding(dp(16), dp(16), dp(16), dp(20));
        hubScroll.addView(hubCol);
        gameCol = new LinearLayout(this);
        gameCol.setOrientation(LinearLayout.VERTICAL);
        gameCol.setBackgroundColor(cBg);
        gameCol.setPadding(dp(12), dp(10), dp(12), dp(12));
        gameCol.setVisibility(View.GONE);
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        stage.setBackgroundColor(cBg);
        stage.addView(hubScroll);
        stage.addView(gameCol);
        root = hubCol;
        setContentView(stage);
        applyImmersive();
        showHub();
    }

    @Override protected void onPause() { super.onPause(); stopLoop(); }
    @Override public void onBackPressed() {
        if (inGame) { showHub(); return; }
        super.onBackPressed();
    }

    boolean inGame = false;
    int currentGame = -1;

    void stopLoop() {
        if (activeLoop != null) ui.removeCallbacks(activeLoop);
        activeLoop = null;
    }

    TextView label(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    TextView chip(String s, boolean accentBg) {
        TextView t = label(s, 14, accentBg ? 0xFF0B1220 : cText, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(accentBg ? cAccent : cCard2);
        t.setBackground(g);
        return t;
    }

    int best(String key, int def) { return gp.getInt(key, def); }

    void saveBest(String key, int val, boolean lowerBetter) {
        int cur = gp.getInt(key, lowerBetter ? Integer.MAX_VALUE : 0);
        if (lowerBetter ? (val > 0 && val < cur) : val > cur) {
            gp.edit().putInt(key, val).apply();
        }
    }

    void header(String title) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 30, cAccent, true);
        back.setPadding(0, 0, dp(10), dp(4));
        back.setOnClickListener(v -> { if (inGame) showHub(); else finish(); });
        bar.addView(back);
        bar.addView(label(title, 21, cText, true));
        root.addView(bar);
    }

    // ------------------------------------------------------------ hub
    /** True fullscreen, Play Store-game style: status + nav bars hidden. */
    void applyImmersive() {
        try {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        } catch (Exception ignored) {
            // some ROMs reject early bar-hiding; the game still runs windowed
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    void showHub() {
        stopLoop();
        inGame = false;
        root = hubCol;
        hubScroll.setVisibility(View.VISIBLE);
        gameCol.setVisibility(View.GONE);
        root.removeAllViews();
        header("Offline Games");
        root.addView(label("17 mini games — 100% offline, no ads.", 13, cDim, false));
        Object[][] games = {
                {"2048", "Number merge puzzle", "4", "best_2048", "Best score: %d"},
                {"Block Puzzle", "Fill lines to clear the board", "▦", "best_blockpz", "Best score: %d"},
                {"Falling Blocks", "The classic block stacker", "▟", "best_blocks", "Best score: %d"},
                {"Brick Breaker", "Bounce the ball, break bricks", "▬", "best_bricks", "Best score: %d"},
                {"Classic Snake", "Eat, grow, don't hit the wall", "🐍", "best_snake", "Best score: %d"},
                {"Flappy Bird", "Tap to fly through the pipes", "🐤", "best_flappy", "Best score: %d"},
                {"Stack Tower", "Stack blocks into a tall tower", "🏢", "best_stack", "Best: %d blocks"},
                {"Knife Hit", "Throw knives into the spinning log", "🔪", "best_knife", "Best level: %d"},
                {"Minesweeper", "Find all the hidden mines", "💣", "best_mines", "Wins: %d"},
                {"Connect 4", "Four in a row wins — AI or 2P", "🔴", "best_c4", "Wins vs AI: %d"},
                {"Dots and Boxes", "Complete boxes to score — AI or 2P", "⬜", "best_dots", "Wins vs AI: %d"},
                {"Snakes & Ladders", "Dice race to 100 — AI or 2P", "🎲", "best_snl", "Wins: %d"},
                {"Carrom Pool", "Pocket the coins — AI or 2P", "🎱", "best_carrom", "Wins vs AI: %d"},
                {"Ludo", "Race your tokens home — AI or 2P", "♟", "best_ludo", "Wins vs AI: %d"},
                {"Unblock Me", "Slide blocks, free the red one", "🟥", "best_unblock", "Best: %d moves"},
                {"Slide Puzzle", "Classic 15-puzzle", "15", "best_puzzle", "Best: %d moves"},
                {"Tic-Tac-Toe", "Vs AI or 2 players", "✕○", "best_ttt", "Wins vs AI: %d"},
        };
        // Play Store-style: every game card gets its own gradient
        int[][] grads = {
                {0xFFF2994A, 0xFFF2784A}, {0xFF42A5F5, 0xFF7C5CFF}, {0xFF00ACC1, 0xFF2FD465},
                {0xFFEF5350, 0xFFF2994A}, {0xFF2FD465, 0xFF1B8A4B}, {0xFF29B6F6, 0xFF0288D1},
                {0xFFAB47BC, 0xFF7C5CFF}, {0xFF78909C, 0xFF546E7A}, {0xFF8D9EAD, 0xFF5C6BC0},
                {0xFFEF5350, 0xFFEC407A}, {0xFFFFB300, 0xFFFB8C00}, {0xFF26A69A, 0xFF00897B},
                {0xFFD4A017, 0xFFB07D3C}, {0xFFE53935, 0xFF3949AB}, {0xFFE53B3B, 0xFFB71C1C},
                {0xFF5C6BC0, 0xFF3949AB}, {0xFFEC407A, 0xFFAB47BC},
        };
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(0, dp(14), 0, 0);
        for (int i = 0; i < games.length; i++) {
            final int gi = i;
            Object[] g = games[i];
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(10), dp(18), dp(10), dp(16));
            android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{grads[i][0], grads[i][1]});
            cg.setCornerRadius(dp(20));
            card.setBackground(cg);
            TextView glyph = label((String) g[2], 34, 0xFFFFFFFF, true);
            glyph.setGravity(Gravity.CENTER);
            card.addView(glyph);
            TextView nm = label((String) g[0], 16, 0xFFFFFFFF, true);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, dp(8), 0, 0);
            card.addView(nm);
            TextView sub = label((String) g[1], 11.5f, 0xD9FFFFFF, false);
            sub.setGravity(Gravity.CENTER);
            card.addView(sub);
            int b = best((String) g[3], ((String) g[3]).contains("puzzle")
                    || ((String) g[3]).contains("sudoku") ? 0 : 0);
            if (b > 0) {
                TextView bt = label(String.format((String) g[4], b), 11.5f, 0xFFFFFFFF, false);
                bt.setGravity(Gravity.CENTER);
                bt.setPadding(0, dp(6), 0, 0);
                card.addView(bt);
            }
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            card.setLayoutParams(lp);
            card.setOnClickListener(v -> openGame(gi));
            grid.addView(card);
        }
        root.addView(grid);
    }

    void openGame(int gi) {
        inGame = true;
        currentGame = gi;
        root.removeAllViews();
        stopLoop();
        switch (gi) {
            case 0: game2048(); break;
            case 1: gameBlockPuzzle(); break;
            case 2: gameBlocks(); break;
            case 3: gameBricks(); break;
            case 4: gameSnake(); break;
            case 5: gameFlappy(); break;
            case 6: gameStack(); break;
            case 7: gameKnife(); break;
            case 8: gameMines(); break;
            case 9: gameConnect4(); break;
            case 10: gameDots(); break;
            case 11: gameSnakesLadders(); break;
            case 12: gameCarrom(); break;
            case 13: gameLudo(); break;
            case 14: gameUnblock(); break;
            case 15: gameSlidePuzzle(); break;
            case 16: gameTicTacToe(); break;
            default: showHub();
        }
    }

    void gameScaffold(String title, TextView[] statusOut) {
        root = gameCol;
        gameCol.setVisibility(View.VISIBLE);
        hubScroll.setVisibility(View.GONE);
        root.removeAllViews();
        header(title);
        TextView st = label("", 15, cText, true);
        st.setGravity(Gravity.CENTER);
        st.setPadding(0, dp(10), 0, dp(6));
        root.addView(st);
        statusOut[0] = st;
    }

    // ------------------------------------------------------------ 2048
    int[] b2048;
    int score2048;
    TextView st2048;
    View2048 view2048;

    final class View2048 extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float downX, downY;
        View2048() { super(GamesActivity.this); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), cell = w / 4f, pad = cell * 0.06f;
            p.setColor(cCard);
            cv.drawRoundRect(new RectF(0, 0, w, w), dp(14), dp(14), p);
            for (int i = 0; i < 16; i++) {
                int r = i / 4, c = i % 4, v = b2048[i];
                float x = c * cell + pad, y = r * cell + pad, s = cell - pad * 2;
                p.setColor(tileColor(v));
                cv.drawRoundRect(new RectF(x, y, x + s, y + s), dp(10), dp(10), p);
                if (v > 0) {
                    p.setColor(v <= 4 ? cText : 0xFFFFFFFF);
                    p.setTextSize(s * (v < 100 ? 0.42f : v < 1000 ? 0.34f : 0.27f));
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    p.setTextAlign(Paint.Align.CENTER);
                    String t = String.valueOf(v);
                    cv.drawText(t, x + s / 2f, y + s / 2f + p.getTextSize() * 0.36f, p);
                }
            }
        }
        int tileColor(int v) {
            switch (v) {
                case 0: return cCard2;
                case 2: return 0xFF3D5A80;
                case 4: return 0xFF2E7DA6;
                case 8: return 0xFFF2994A;
                case 16: return 0xFFF2784A;
                case 32: return 0xFFEB5757;
                case 64: return 0xFFE53B3B;
                case 128: return 0xFFEDCE73;
                case 256: return 0xFFEDC94F;
                case 512: return 0xFFE8B93E;
                case 1024: return 0xFF2FD465;
                default: return 0xFF1FA855;
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downX = e.getX(); downY = e.getY();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.max(Math.abs(dx), Math.abs(dy)) < dp(24)) return true;
                if (Math.abs(dx) > Math.abs(dy)) move2048(dx > 0 ? 1 : 0);
                else move2048(dy > 0 ? 3 : 2);
                return true;
            }
            return true;
        }
    }

    void game2048() {
        TextView[] st = new TextView[1];
        gameScaffold("2048", st);
        st2048 = st[0];
        b2048 = new int[16];
        score2048 = 0;
        spawn2048(); spawn2048();
        view2048 = new View2048();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(view2048, lp);
        LinearLayout arrows = new LinearLayout(this);
        arrows.setGravity(Gravity.CENTER);
        arrows.setPadding(0, dp(12), 0, 0);
        String[] syms = {"◀", "▲", "▼", "▶"};
        for (int i = 0; i < 4; i++) {
            final int dir = i == 0 ? 0 : i == 1 ? 2 : i == 2 ? 3 : 1;
            TextView b = chip(syms[i], false);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(64), dp(52));
            bl.setMargins(dp(5), 0, dp(5), 0);
            b.setLayoutParams(bl);
            b.setTextSize(18);
            b.setOnClickListener(v -> move2048(dir));
            arrows.addView(b);
        }
        root.addView(arrows);
        TextView ng = chip("↻  New game", true);
        ng.setOnClickListener(v -> game2048Refresh());
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(14);
        root.addView(ng, nl);
        upd2048Status();
    }

    void game2048Refresh() {
        b2048 = new int[16];
        score2048 = 0;
        spawn2048(); spawn2048();
        view2048.invalidate();
        upd2048Status();
    }

    void upd2048Status() {
        st2048.setText("Score: " + score2048 + "   •   Best: "
                + Math.max(best("best_2048", 0), score2048));
    }

    void spawn2048() {
        List<Integer> empty = new ArrayList<>();
        for (int i = 0; i < 16; i++) if (b2048[i] == 0) empty.add(i);
        if (empty.isEmpty()) return;
        b2048[empty.get(rnd.nextInt(empty.size()))] = rnd.nextInt(10) == 0 ? 4 : 2;
    }

    // dir: 0 left, 1 right, 2 up, 3 down
    void move2048(int dir) {
        int[] before = b2048.clone();
        for (int line = 0; line < 4; line++) {
            int[] idx = new int[4];
            for (int k = 0; k < 4; k++) {
                int r, c;
                if (dir == 0) { r = line; c = k; }
                else if (dir == 1) { r = line; c = 3 - k; }
                else if (dir == 2) { r = k; c = line; }
                else { r = 3 - k; c = line; }
                idx[k] = r * 4 + c;
            }
            int[] vals = new int[4];
            int n = 0;
            for (int k = 0; k < 4; k++) if (b2048[idx[k]] != 0) vals[n++] = b2048[idx[k]];
            for (int k = 0; k < 3; k++) {
                if (vals[k] != 0 && vals[k] == vals[k + 1]) {
                    vals[k] *= 2;
                    score2048 += vals[k];
                    vals[k + 1] = 0;
                    for (int j = k + 1; j < 3; j++) vals[j] = vals[j + 1];
                    vals[3] = 0;
                }
            }
            int m = 0;
            int[] out = new int[4];
            for (int k = 0; k < 4; k++) if (vals[k] != 0) out[m++] = vals[k];
            for (int k = 0; k < 4; k++) b2048[idx[k]] = out[k];
        }
        boolean changed = false;
        for (int i = 0; i < 16; i++) if (b2048[i] != before[i]) changed = true;
        if (changed) {
            spawn2048();
            view2048.invalidate();
            saveBest("best_2048", score2048, false);
            upd2048Status();
            if (is2048Over()) {
                st2048.setText("Game over! Score: " + score2048);
            }
        }
    }

    boolean is2048Over() {
        for (int i = 0; i < 16; i++) {
            if (b2048[i] == 0) return false;
            int r = i / 4, c = i % 4;
            if (c < 3 && b2048[i] == b2048[i + 1]) return false;
            if (r < 3 && b2048[i] == b2048[i + 4]) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ Tic-Tac-Toe
    int[] ttt;
    boolean tttVsAi = true, tttXTurn = true, tttDone;
    TextView stTtt;
    TextView[] tttCells;

    void gameTicTacToe() {
        TextView[] st = new TextView[1];
        gameScaffold("Tic-Tac-Toe", st);
        stTtt = st[0];
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        TextView mAi = chip("Vs AI", tttVsAi);
        TextView m2p = chip("2 Players", !tttVsAi);
        mAi.setOnClickListener(v -> { tttVsAi = true; openGame(16); });
        m2p.setOnClickListener(v -> { tttVsAi = false; openGame(16); });
        modes.addView(mAi);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(10), 1);
        modes.addView(new View(this), sp);
        modes.addView(m2p);
        root.addView(modes);
        ttt = new int[9];
        tttCells = new TextView[9];
        tttXTurn = true;
        tttDone = false;
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setPadding(0, dp(16), 0, 0);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        for (int i = 0; i < 9; i++) {
            final int idx = i;
            TextView cell = label("", 34, cText, true);
            cell.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
            cg.setCornerRadius(dp(12));
            cg.setColor(cCard);
            cell.setBackground(cg);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(96);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> tttTap(idx));
            tttCells[i] = cell;
            grid.addView(cell);
        }
        root.addView(grid);
        TextView ng = chip("↻  New game", true);
        ng.setOnClickListener(v -> openGame(16));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(14);
        root.addView(ng, nl);
        stTtt.setText(tttVsAi ? "You are ✕ — your move" : "✕ goes first");
    }

    void tttTap(int idx) {
        if (tttDone || ttt[idx] != 0) return;
        ttt[idx] = tttXTurn ? 1 : 2;
        tttPaint();
        if (tttCheckEnd()) return;
        tttXTurn = !tttXTurn;
        if (tttVsAi && !tttXTurn) {
            ui.postDelayed(() -> {
                int mv = tttAiMove();
                if (mv >= 0) {
                    ttt[mv] = 2;
                    tttPaint();
                    if (!tttCheckEnd()) {
                        tttXTurn = true;
                        stTtt.setText("Your move");
                    }
                }
            }, 350);
        } else {
            stTtt.setText((tttXTurn ? "✕" : "○") + " to move");
        }
    }

    void tttPaint() {
        for (int i = 0; i < 9; i++) {
            tttCells[i].setText(ttt[i] == 1 ? "✕" : ttt[i] == 2 ? "○" : "");
            tttCells[i].setTextColor(ttt[i] == 1 ? cAccent : 0xFFEF5350);
        }
    }

    int tttWinner() {
        int[][] lines = {{0,1,2},{3,4,5},{6,7,8},{0,3,6},{1,4,7},{2,5,8},{0,4,8},{2,4,6}};
        for (int[] L : lines) {
            if (ttt[L[0]] != 0 && ttt[L[0]] == ttt[L[1]] && ttt[L[1]] == ttt[L[2]]) return ttt[L[0]];
        }
        return 0;
    }

    boolean tttCheckEnd() {
        int w = tttWinner();
        if (w != 0) {
            tttDone = true;
            if (tttVsAi) {
                if (w == 1) {
                    stTtt.setText("🎉 You win!");
                    saveBest("best_ttt", best("best_ttt", 0) + 1, false);
                } else stTtt.setText("AI wins — try again");
            } else {
                stTtt.setText((w == 1 ? "✕" : "○") + " wins! 🎉");
            }
            return true;
        }
        boolean full = true;
        for (int v : ttt) if (v == 0) full = false;
        if (full) {
            tttDone = true;
            stTtt.setText("Draw!");
            return true;
        }
        return false;
    }

    int tttAiMove() {
        // win if possible, else block, else center/corner/random
        for (int i = 0; i < 9; i++) if (ttt[i] == 0) {
            ttt[i] = 2;
            boolean win = tttWinner() == 2;
            ttt[i] = 0;
            if (win) return i;
        }
        for (int i = 0; i < 9; i++) if (ttt[i] == 0) {
            ttt[i] = 1;
            boolean threat = tttWinner() == 1;
            ttt[i] = 0;
            if (threat) return i;
        }
        if (ttt[4] == 0) return 4;
        int[] corners = {0, 2, 6, 8};
        List<Integer> opts = new ArrayList<>();
        for (int c : corners) if (ttt[c] == 0) opts.add(c);
        if (opts.isEmpty()) for (int i = 0; i < 9; i++) if (ttt[i] == 0) opts.add(i);
        return opts.isEmpty() ? -1 : opts.get(rnd.nextInt(opts.size()));
    }

    // ------------------------------------------------------------ Slide Puzzle
    int[] pz;
    int pzMoves;
    TextView stPz;
    TextView[] pzCells;

    void gameSlidePuzzle() {
        TextView[] st = new TextView[1];
        gameScaffold("Slide Puzzle", st);
        stPz = st[0];
        pz = new int[16];
        for (int i = 0; i < 16; i++) pz[i] = i + 1;
        pz[15] = 0;
        // shuffle with random legal moves (always solvable)
        int blank = 15;
        for (int s = 0; s < 120; s++) {
            List<Integer> nbs = pzNeighbors(blank);
            int nb = nbs.get(rnd.nextInt(nbs.size()));
            pz[blank] = pz[nb];
            pz[nb] = 0;
            blank = nb;
        }
        pzMoves = 0;
        pzCells = new TextView[16];
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setPadding(0, dp(10), 0, 0);
        for (int i = 0; i < 16; i++) {
            final int idx = i;
            TextView cell = label("", 22, cText, true);
            cell.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(74);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> pzTap(idx));
            pzCells[i] = cell;
            grid.addView(cell);
        }
        root.addView(grid);
        TextView ng = chip("↻  Shuffle again", true);
        ng.setOnClickListener(v -> openGame(15));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(14);
        root.addView(ng, nl);
        pzPaint();
        pzStatus();
    }

    List<Integer> pzNeighbors(int blank) {
        List<Integer> out = new ArrayList<>();
        int r = blank / 4, c = blank % 4;
        if (r > 0) out.add(blank - 4);
        if (r < 3) out.add(blank + 4);
        if (c > 0) out.add(blank - 1);
        if (c < 3) out.add(blank + 1);
        return out;
    }

    void pzTap(int idx) {
        int blank = -1;
        for (int i = 0; i < 16; i++) if (pz[i] == 0) blank = i;
        if (!pzNeighbors(blank).contains(idx)) return;
        pz[blank] = pz[idx];
        pz[idx] = 0;
        pzMoves++;
        pzPaint();
        pzStatus();
        boolean solved = true;
        for (int i = 0; i < 15; i++) if (pz[i] != i + 1) solved = false;
        if (solved && pz[15] == 0) {
            saveBest("best_puzzle", pzMoves, true);
            stPz.setText("🎉 Solved in " + pzMoves + " moves!");
        }
    }

    void pzPaint() {
        for (int i = 0; i < 16; i++) {
            android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
            cg.setCornerRadius(dp(10));
            cg.setColor(pz[i] == 0 ? 0x00000000 : (pz[i] == i + 1 ? 0xFF1FA855 : cCard));
            pzCells[i].setBackground(cg);
            pzCells[i].setText(pz[i] == 0 ? "" : String.valueOf(pz[i]));
        }
    }

    void pzStatus() {
        int b = best("best_puzzle", 0);
        stPz.setText("Moves: " + pzMoves + (b > 0 ? "   •   Best: " + b : ""));
    }

    // ------------------------------------------------------------ Falling Blocks
    static final int BW = 10, BH = 20;
    int[][] board;
    int[][] piece;
    int pieceType, px, py, blkScore, blkLines;
    boolean blkRunning, blkOver;
    TextView stBlk;
    ViewBlocks viewBlocks;
    final Random blkRnd = new Random();
    static final int[][][] SHAPES = {
            {{0, 0}, {1, 0}, {2, 0}, {3, 0}},        // I
            {{0, 0}, {1, 0}, {0, 1}, {1, 1}},        // O
            {{0, 0}, {1, 0}, {2, 0}, {1, 1}},        // T
            {{0, 0}, {1, 0}, {2, 0}, {2, 1}},        // L
            {{0, 0}, {1, 0}, {2, 0}, {0, 1}},        // J
            {{0, 0}, {1, 0}, {1, 1}, {2, 1}},        // S
            {{1, 0}, {2, 0}, {0, 1}, {1, 1}},        // Z
    };
    static final int[] SHAPE_COLORS = {0xFF19C8E8, 0xFFFFEB3B, 0xFFAB47BC, 0xFFFF9800,
            0xFF42A5F5, 0xFF2FD465, 0xFFEF5350};
    final Runnable blkTick = new Runnable() {
        @Override public void run() {
            if (!blkRunning) return;
            blkStep();
            ui.postDelayed(this, Math.max(120, 520 - blkLines * 12));
        }
    };

    final class ViewBlocks extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float tbDownX, tbDownY, tbLastX, tbLastY;
        long tbDownT;
        ViewBlocks() { super(GamesActivity.this); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!blkRunning || blkOver || piece == null) return true;
            float cw = getWidth() / (float) BW, ch = getHeight() / (float) BH;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    tbDownX = tbLastX = e.getX();
                    tbDownY = tbLastY = e.getY();
                    tbDownT = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dxCells = (e.getX() - tbLastX) / cw;
                    while (dxCells >= 1) { blkMove(1); tbLastX += cw; dxCells--; }
                    while (dxCells <= -1) { blkMove(-1); tbLastX -= cw; dxCells++; }
                    float dyCells = (e.getY() - tbLastY) / ch;
                    while (dyCells >= 1) { blkStep(); tbLastY += ch; dyCells--; }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float totalMove = (float) Math.hypot(e.getX() - tbDownX, e.getY() - tbDownY);
                    if (System.currentTimeMillis() - tbDownT < 250 && totalMove < cw) {
                        blkRotate();
                        invalidate();
                    }
                    return true;
                }
                default: return true;
            }
        }
        @Override protected void onDraw(Canvas cv) {
            float cw = getWidth() / (float) BW, ch = getHeight() / (float) BH;
            p.setColor(cCard);
            cv.drawRect(0, 0, getWidth(), getHeight(), p);
            for (int y = 0; y < BH; y++) for (int x = 0; x < BW; x++) {
                if (board[y][x] != 0) {
                    p.setColor(board[y][x]);
                    cv.drawRoundRect(new RectF(x * cw + 1, y * ch + 1,
                            (x + 1) * cw - 1, (y + 1) * ch - 1), 3, 3, p);
                }
            }
            if (piece != null && !blkOver) {
                p.setColor(SHAPE_COLORS[pieceType]);
                for (int[] cell : piece) {
                    int x = px + cell[0], y = py + cell[1];
                    if (y >= 0) cv.drawRoundRect(new RectF(x * cw + 1, y * ch + 1,
                            (x + 1) * cw - 1, (y + 1) * ch - 1), 3, 3, p);
                }
            }
            p.setColor(0x22FFFFFF);
            for (int x = 1; x < BW; x++) cv.drawLine(x * cw, 0, x * cw, getHeight(), p);
            for (int y = 1; y < BH; y++) cv.drawLine(0, y * ch, getWidth(), y * ch, p);
        }
    }

    void gameBlocks() {
        TextView[] st = new TextView[1];
        gameScaffold("Falling Blocks", st);
        stBlk = st[0];
        board = new int[BH][BW];
        blkScore = 0;
        blkLines = 0;
        blkOver = false;
        viewBlocks = new ViewBlocks();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(viewBlocks, lp);
        viewBlocks.post(() -> {
            ViewGroup.LayoutParams vp = viewBlocks.getLayoutParams();
            vp.width = viewBlocks.getHeight() / 2;
            viewBlocks.setLayoutParams(vp);
        });
        LinearLayout ctrls = new LinearLayout(this);
        ctrls.setGravity(Gravity.CENTER);
        ctrls.setPadding(0, dp(12), 0, 0);
        String[] syms = {"◀", "⟳", "▶", "▼"};
        for (int i = 0; i < 4; i++) {
            final int act = i;
            TextView b = chip(syms[i], i == 3);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(64), dp(52));
            bl.setMargins(dp(5), 0, dp(5), 0);
            b.setLayoutParams(bl);
            b.setTextSize(18);
            b.setOnClickListener(v -> {
                if (!blkRunning || blkOver) return;
                if (act == 0) blkMove(-1);
                else if (act == 1) blkRotate();
                else if (act == 2) blkMove(1);
                else blkStep();
                viewBlocks.invalidate();
            });
            ctrls.addView(b);
        }
        root.addView(ctrls);
        TextView start = chip("▶  Start", true);
        start.setOnClickListener(v -> {
            if (blkOver) { openGame(2); return; }
            if (!blkRunning) {
                blkRunning = true;
                activeLoop = blkTick;
                blkSpawn();
                ui.postDelayed(blkTick, 400);
                start.setText("⏸  Pause");
            } else {
                blkRunning = false;
                start.setText("▶  Resume");
            }
            blkStatus();
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(12);
        root.addView(start, nl);
        blkStatus();
    }

    void blkStatus() {
        stBlk.setText("Score: " + blkScore + "   •   Lines: " + blkLines
                + "   •   Best: " + Math.max(best("best_blocks", 0), blkScore));
    }

    void blkSpawn() {
        pieceType = blkRnd.nextInt(SHAPES.length);
        piece = new int[4][2];
        for (int i = 0; i < 4; i++) {
            piece[i][0] = SHAPES[pieceType][i][0];
            piece[i][1] = SHAPES[pieceType][i][1];
        }
        px = 3; py = 0;
        if (blkCollides(piece, px, py)) {
            blkOver = true;
            blkRunning = false;
            saveBest("best_blocks", blkScore, false);
            stBlk.setText("Game over! Score: " + blkScore);
        }
        viewBlocks.invalidate();
    }

    boolean blkCollides(int[][] pc, int ox, int oy) {
        for (int[] cell : pc) {
            int x = ox + cell[0], y = oy + cell[1];
            if (x < 0 || x >= BW || y >= BH) return true;
            if (y >= 0 && board[y][x] != 0) return true;
        }
        return false;
    }

    void blkMove(int dx) {
        if (!blkCollides(piece, px + dx, py)) px += dx;
    }

    void blkRotate() {
        int[][] rot = new int[4][2];
        for (int i = 0; i < 4; i++) {
            rot[i][0] = -piece[i][1];
            rot[i][1] = piece[i][0];
        }
        // normalize into a 0-based box
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (int[] c : rot) { minX = Math.min(minX, c[0]); minY = Math.min(minY, c[1]); }
        for (int[] c : rot) { c[0] -= minX; c[1] -= minY; }
        if (!blkCollides(rot, px, py)) piece = rot;
    }

    void blkStep() {
        if (piece == null) return;
        if (!blkCollides(piece, px, py + 1)) {
            py++;
        } else {
            for (int[] cell : piece) {
                int x = px + cell[0], y = py + cell[1];
                if (y >= 0) board[y][x] = SHAPE_COLORS[pieceType];
            }
            piece = null;
            blkClearLines();
            blkStatus();
            blkSpawn();
            return;
        }
        viewBlocks.invalidate();
    }

    void blkClearLines() {
        int cleared = 0;
        for (int y = BH - 1; y >= 0; y--) {
            boolean full = true;
            for (int x = 0; x < BW; x++) if (board[y][x] == 0) full = false;
            if (full) {
                cleared++;
                for (int yy = y; yy > 0; yy--) board[yy] = board[yy - 1];
                board[0] = new int[BW];
                y++;
            }
        }
        if (cleared > 0) {
            blkLines += cleared;
            blkScore += new int[]{0, 100, 300, 500, 800}[cleared];
        }
    }

    // ------------------------------------------------------------ Block Puzzle
    int[][] bpBoard;
    int bpScore, bpSel;
    int[][][] bpTray;
    int[] bpTrayColor;
    TextView stBp;
    ViewBlockPuzzle viewBp;
    static final int[][][] BP_PIECES = {
            {{0, 0}},                                        // single
            {{0, 0}, {1, 0}},                                // domino H
            {{0, 0}, {0, 1}},                                // domino V
            {{0, 0}, {1, 0}, {2, 0}},                        // line 3 H
            {{0, 0}, {0, 1}, {0, 2}},                        // line 3 V
            {{0, 0}, {1, 0}, {2, 0}, {3, 0}},                // line 4 H
            {{0, 0}, {0, 1}, {0, 2}, {0, 3}},                // line 4 V
            {{0, 0}, {1, 0}, {0, 1}, {1, 1}},                // square 2x2
            {{0, 0}, {1, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}, {0, 2}, {1, 2}, {2, 2}}, // 3x3
            {{0, 0}, {0, 1}, {1, 1}},                        // L small
            {{0, 0}, {1, 0}, {1, 1}},                        // L small 2
            {{0, 0}, {1, 0}, {0, 1}},                        // L small 3
            {{1, 0}, {0, 1}, {1, 1}},                        // L small 4
            {{0, 0}, {1, 0}, {2, 0}, {1, 1}},                // T
            {{0, 0}, {0, 1}, {0, 2}, {1, 2}},                // L big
            {{0, 0}, {1, 0}, {2, 0}, {2, 1}},                // L big 2
    };
    static final int[] BP_COLORS = {0xFFEF5350, 0xFFFF9800, 0xFFFFEB3B, 0xFF2FD465,
            0xFF19C8E8, 0xFF42A5F5, 0xFFAB47BC, 0xFFF06292};

    final class ViewBlockPuzzle extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewBlockPuzzle() { super(GamesActivity.this); }
        float cell() { return getWidth() / 10f; }
        float trayY() { return cell() * 10 + dp(18); }
        @Override protected void onDraw(Canvas cv) {
            float cs = cell();
            p.setColor(cCard);
            cv.drawRoundRect(new RectF(0, 0, cs * 10, cs * 10), dp(10), dp(10), p);
            for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++) {
                if (bpBoard[y][x] != 0) {
                    p.setColor(bpBoard[y][x]);
                    cv.drawRoundRect(new RectF(x * cs + 1.5f, y * cs + 1.5f,
                            (x + 1) * cs - 1.5f, (y + 1) * cs - 1.5f), dp(5), dp(5), p);
                }
            }
            // tray: 3 slots
            float ty = trayY(), slotW = getWidth() / 3f, tcs = cs * 0.62f;
            for (int s = 0; s < 3; s++) {
                if (bpTray[s] == null) continue;
                float ox = s * slotW + slotW / 2f - bpPieceW(bpTray[s]) * tcs / 2f;
                float oy = ty + dp(26) - bpPieceH(bpTray[s]) * tcs / 2f;
                if (s == bpSel) {
                    p.setColor(0x33FFFFFF);
                    cv.drawRoundRect(new RectF(s * slotW + dp(6), ty, (s + 1) * slotW - dp(6),
                            ty + dp(52)), dp(10), dp(10), p);
                }
                p.setColor(bpTrayColor[s]);
                for (int[] c : bpTray[s]) {
                    cv.drawRoundRect(new RectF(ox + c[0] * tcs + 1.5f, oy + c[1] * tcs + 1.5f,
                            ox + (c[0] + 1) * tcs - 1.5f, oy + (c[1] + 1) * tcs - 1.5f), dp(4), dp(4), p);
                }
            }
            p.setColor(cDim);
            p.setTextSize(dp(12));
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText("Tap a piece, then tap the board", getWidth() / 2f, ty + dp(70), p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_DOWN) return true;
            float cs = cell(), x = e.getX(), y = e.getY();
            if (y < cs * 10) {
                if (bpSel >= 0 && bpTray[bpSel] != null) {
                    int gx = (int) (x / cs), gy = (int) (y / cs);
                    bpPlace(gx, gy);
                }
            } else if (y > trayY() && y < trayY() + dp(56)) {
                int s = (int) (x / (getWidth() / 3f));
                if (s >= 0 && s < 3 && bpTray[s] != null) {
                    bpSel = s;
                    invalidate();
                }
            }
            return true;
        }
    }

    int bpPieceW(int[][] pc) { int m = 0; for (int[] c : pc) m = Math.max(m, c[0] + 1); return m; }
    int bpPieceH(int[][] pc) { int m = 0; for (int[] c : pc) m = Math.max(m, c[1] + 1); return m; }

    void bpDeal(int slot) {
        bpTray[slot] = BP_PIECES[rnd.nextInt(BP_PIECES.length)];
        bpTrayColor[slot] = BP_COLORS[rnd.nextInt(BP_COLORS.length)];
    }

    boolean bpFits(int[][] pc, int gx, int gy) {
        for (int[] c : pc) {
            int x = gx + c[0], y = gy + c[1];
            if (x < 0 || x >= 10 || y < 0 || y >= 10 || bpBoard[y][x] != 0) return false;
        }
        return true;
    }

    void bpPlace(int gx, int gy) {
        int[][] pc = bpTray[bpSel];
        if (!bpFits(pc, gx, gy)) return;
        for (int[] c : pc) bpBoard[gy + c[1]][gx + c[0]] = bpTrayColor[bpSel];
        bpScore += pc.length;
        bpTray[bpSel] = null;
        bpSel = -1;
        // clear full rows/cols
        boolean[] rows = new boolean[10], cols = new boolean[10];
        int lines = 0;
        for (int y = 0; y < 10; y++) {
            rows[y] = true;
            for (int x = 0; x < 10; x++) if (bpBoard[y][x] == 0) rows[y] = false;
            if (rows[y]) lines++;
        }
        for (int x = 0; x < 10; x++) {
            cols[x] = true;
            for (int y = 0; y < 10; y++) if (bpBoard[y][x] == 0) cols[x] = false;
            if (cols[x]) lines++;
        }
        if (lines > 0) {
            for (int y = 0; y < 10; y++) if (rows[y]) for (int x = 0; x < 10; x++) bpBoard[y][x] = 0;
            for (int x = 0; x < 10; x++) if (cols[x]) for (int y = 0; y < 10; y++) bpBoard[y][x] = 0;
            bpScore += lines * 10 + (lines - 1) * 10;
        }
        boolean anyLeft = false;
        for (int s = 0; s < 3; s++) if (bpTray[s] != null) anyLeft = true;
        if (!anyLeft) for (int s = 0; s < 3; s++) bpDeal(s);
        saveBest("best_blockpz", bpScore, false);
        bpStatus();
        viewBp.invalidate();
        if (bpGameOver()) stBp.setText("Game over! Score: " + bpScore);
    }

    boolean bpGameOver() {
        for (int s = 0; s < 3; s++) {
            if (bpTray[s] == null) continue;
            for (int y = 0; y < 10; y++) for (int x = 0; x < 10; x++) {
                if (bpFits(bpTray[s], x, y)) return false;
            }
        }
        return true;
    }

    void bpStatus() {
        stBp.setText("Score: " + bpScore + "   •   Best: " + Math.max(best("best_blockpz", 0), bpScore));
    }

    void gameBlockPuzzle() {
        TextView[] st = new TextView[1];
        gameScaffold("Block Puzzle", st);
        stBp = st[0];
        bpBoard = new int[10][10];
        bpTray = new int[3][][];
        bpTrayColor = new int[3];
        bpScore = 0;
        bpSel = -1;
        for (int s = 0; s < 3; s++) bpDeal(s);
        viewBp = new ViewBlockPuzzle();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewBp, new LinearLayout.LayoutParams(w, w + dp(108)));
        TextView ng = chip("↻  New game", true);
        ng.setOnClickListener(v -> openGame(1));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(10);
        root.addView(ng, nl);
        bpStatus();
    }

    // ------------------------------------------------------------ Unblock Me
    // blocks: {x, y, len, horizontal(1/0), isRed(1/0)} — drag blocks with your finger
    static final int[][][] UB_PUZZLES = {
            {{0, 2, 2, 1, 1}, {2, 1, 3, 0, 0}, {0, 0, 2, 1, 0}, {5, 0, 2, 0, 0},
             {3, 5, 2, 1, 0}, {0, 4, 2, 1, 0}, {1, 3, 2, 0, 0}},
            {{0, 2, 2, 1, 1}, {2, 2, 2, 0, 0}, {4, 1, 2, 0, 0}, {0, 0, 2, 1, 0},
             {5, 3, 2, 0, 0}, {0, 5, 2, 1, 0}, {0, 3, 2, 0, 0}},
            {{0, 2, 2, 1, 1}, {2, 1, 3, 0, 0}, {4, 2, 3, 0, 0}, {0, 0, 2, 1, 0},
             {5, 3, 2, 0, 0}, {0, 4, 2, 1, 0}},
    };
    ArrayList<int[]> ubBlocks;
    int ubMoves, ubLevel, ubDragIdx, ubDragStartPos;
    float ubGrabOff;
    TextView stUb;
    ViewUnblock viewUb;

    final class ViewUnblock extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewUnblock() { super(GamesActivity.this); }
        float cs() { return getWidth() / 6f; }
        @Override protected void onDraw(Canvas cv) {
            float cs = cs();
            p.setColor(0xFF2A2118);
            cv.drawRoundRect(new RectF(0, 0, getWidth(), getWidth()), dp(12), dp(12), p);
            p.setColor(0xFF3E2F1F);
            for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
                cv.drawRoundRect(new RectF(x * cs + 2, y * cs + 2, (x + 1) * cs - 2, (y + 1) * cs - 2),
                        dp(6), dp(6), p);
            }
            // exit
            p.setColor(0xFF2FD465);
            p.setTextSize(cs * 0.6f);
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText("▶", 5.5f * cs, 2.5f * cs + cs * 0.22f, p);
            for (int bi = 0; bi < ubBlocks.size(); bi++) {
                int[] b = ubBlocks.get(bi);
                float x = b[0] * cs, y = b[1] * cs;
                float w = (b[3] == 1 ? b[2] : 1) * cs, h = (b[3] == 1 ? 1 : b[2]) * cs;
                p.setColor(b[4] == 1 ? 0xFFE53B3B : (bi % 2 == 0 ? 0xFFC98A4B : 0xFFA66A2E));
                cv.drawRoundRect(new RectF(x + 3, y + 3, x + w - 3, y + h - 3), dp(9), dp(9), p);
                p.setColor(0x55FFFFFF);
                cv.drawRoundRect(new RectF(x + 8, y + 8, x + w - 8, y + h * 0.36f), dp(6), dp(6), p);
                if (b[4] == 1) {
                    p.setColor(0xFFFFFFFF);
                    p.setTextSize(cs * 0.5f);
                    cv.drawText("★", x + w / 2f, y + h / 2f + cs * 0.18f, p);
                }
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float cs = cs();
            float gx = e.getX() / cs, gy = e.getY() / cs;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    ubDragIdx = -1;
                    for (int bi = 0; bi < ubBlocks.size(); bi++) {
                        int[] b = ubBlocks.get(bi);
                        float bw = b[3] == 1 ? b[2] : 1, bh = b[3] == 1 ? 1 : b[2];
                        if (gx >= b[0] && gx < b[0] + bw && gy >= b[1] && gy < b[1] + bh) {
                            ubDragIdx = bi;
                            ubDragStartPos = b[3] == 1 ? b[0] : b[1];
                            ubGrabOff = (b[3] == 1 ? gx - b[0] : gy - b[1]);
                            getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (ubDragIdx < 0) return true;
                    int[] b = ubBlocks.get(ubDragIdx);
                    float want = (b[3] == 1 ? gx : gy) - ubGrabOff;
                    // step toward the wanted position one cell at a time, stopping at collisions
                    int cur = b[3] == 1 ? b[0] : b[1];
                    while (Math.abs(want - cur) >= 0.5f) {
                        int step = want > cur ? 1 : -1;
                        if (!ubTryMove(ubDragIdx, step)) break;
                        cur += step;
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (ubDragIdx >= 0) {
                        int[] b = ubBlocks.get(ubDragIdx);
                        int now = b[3] == 1 ? b[0] : b[1];
                        if (now != ubDragStartPos) {
                            ubMoves++;
                            ubStatus();
                            int[] red = ubBlocks.get(0);
                            if (red[0] + red[2] >= 6) {
                                saveBest("best_unblock", ubMoves, true);
                                stUb.setText("🎉 Freed in " + ubMoves + " moves!");
                            }
                        }
                    }
                    ubDragIdx = -1;
                    return true;
                }
                default: return true;
            }
        }
    }

    /** Tries to move block bi one cell along its axis (dir = -1/+1). Returns false if blocked. */
    boolean ubTryMove(int bi, int dir) {
        int[] b = ubBlocks.get(bi);
        int nx = b[0] + (b[3] == 1 ? dir : 0);
        int ny = b[1] + (b[3] == 1 ? 0 : dir);
        for (int k = 0; k < b[2]; k++) {
            int x = nx + (b[3] == 1 ? k : 0), y = ny + (b[3] == 1 ? 0 : k);
            if (x < 0 || x >= 6 || y < 0 || y >= 6) return false;
        }
        for (int bj = 0; bj < ubBlocks.size(); bj++) {
            if (bj == bi) continue;
            int[] o = ubBlocks.get(bj);
            for (int k = 0; k < b[2]; k++) {
                int x = nx + (b[3] == 1 ? k : 0), y = ny + (b[3] == 1 ? 0 : k);
                for (int j = 0; j < o[2]; j++) {
                    int ox = o[0] + (o[3] == 1 ? j : 0), oy = o[1] + (o[3] == 1 ? 0 : j);
                    if (x == ox && y == oy) return false;
                }
            }
        }
        b[0] = nx;
        b[1] = ny;
        return true;
    }

    void gameUnblock() {
        TextView[] st = new TextView[1];
        gameScaffold("Unblock Me", st);
        stUb = st[0];
        LinearLayout lv = new LinearLayout(this);
        lv.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            final int li = i;
            TextView b = chip("Level " + (i + 1), i == ubLevel);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.setMargins(dp(4), 0, dp(4), 0);
            b.setLayoutParams(bl);
            b.setOnClickListener(v -> { ubLevel = li; openGame(14); });
            lv.addView(b);
        }
        root.addView(lv);
        ubBlocks = new ArrayList<>();
        for (int[] b : UB_PUZZLES[ubLevel]) ubBlocks.add(b.clone());
        ubMoves = 0;
        ubDragIdx = -1;
        viewUb = new ViewUnblock();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewUb, new LinearLayout.LayoutParams(w, w));
        root.addView(label("Drag the blocks with your finger. Slide the red ★ block "
                + "out through the green exit on the right.", 12, cDim, false));
        ubStatus();
    }

    void ubStatus() {
        int b = best("best_unblock", 0);
        stUb.setText("Moves: " + ubMoves + (b > 0 ? "   •   Best: " + b : ""));
    }

    // ------------------------------------------------------------ Minesweeper
    boolean[][] mnMine, mnOpen, mnFlag;
    boolean mnStarted, mnOver;
    TextView stMn;
    TextView[] mnCells;

    void gameMines() {
        TextView[] st = new TextView[1];
        gameScaffold("Minesweeper", st);
        stMn = st[0];
        mnMine = new boolean[9][9];
        mnOpen = new boolean[9][9];
        mnFlag = new boolean[9][9];
        mnStarted = false;
        mnOver = false;
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(9);
        grid.setPadding(0, dp(10), 0, 0);
        mnCells = new TextView[81];
        for (int i = 0; i < 81; i++) {
            final int idx = i;
            TextView cell = label("", 15, cText, true);
            cell.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(38);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> mnTap(idx));
            cell.setOnLongClickListener(v -> {
                if (!mnOver && !mnOpen[idx / 9][idx % 9]) {
                    mnFlag[idx / 9][idx % 9] = !mnFlag[idx / 9][idx % 9];
                    mnPaint();
                }
                return true;
            });
            mnCells[i] = cell;
            grid.addView(cell);
        }
        root.addView(grid);
        root.addView(label("Tap to reveal • Long-press to flag 🚩 (10 mines)", 12, cDim, false));
        TextView ng = chip("↻  New game", true);
        ng.setOnClickListener(v -> openGame(8));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(12);
        root.addView(ng, nl);
        mnPaint();
        stMn.setText("Find all 10 mines");
    }

    void mnPlant(int safeIdx) {
        int placed = 0;
        while (placed < 10) {
            int i = rnd.nextInt(81);
            if (i == safeIdx || mnMine[i / 9][i % 9]) continue;
            mnMine[i / 9][i % 9] = true;
            placed++;
        }
        mnStarted = true;
    }

    int mnCount(int r, int c) {
        int n = 0;
        for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
            int rr = r + dr, cc = c + dc;
            if (rr >= 0 && rr < 9 && cc >= 0 && cc < 9 && mnMine[rr][cc]) n++;
        }
        return n;
    }

    void mnTap(int idx) {
        if (mnOver || mnFlag[idx / 9][idx % 9]) return;
        if (!mnStarted) mnPlant(idx);
        int r = idx / 9, c = idx % 9;
        if (mnMine[r][c]) {
            mnOver = true;
            mnOpen[r][c] = true;
            mnPaint();
            stMn.setText("💥 Boom! That was a mine.");
            return;
        }
        mnReveal(r, c);
        mnPaint();
        int open = 0;
        for (int i = 0; i < 81; i++) if (mnOpen[i / 9][i % 9]) open++;
        if (open == 71) {
            mnOver = true;
            saveBest("best_mines", best("best_mines", 0) + 1, false);
            stMn.setText("🎉 You cleared the field! Wins: " + best("best_mines", 0));
        } else {
            stMn.setText("Revealed: " + open + " / 71 safe cells");
        }
    }

    void mnReveal(int r, int c) {
        if (r < 0 || r >= 9 || c < 0 || c >= 9 || mnOpen[r][c]) return;
        mnOpen[r][c] = true;
        if (mnCount(r, c) == 0) {
            for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                mnReveal(r + dr, c + dc);
            }
        }
    }

    void mnPaint() {
        for (int i = 0; i < 81; i++) {
            int r = i / 9, c = i % 9;
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(dp(4));
            if (mnOpen[r][c]) {
                g.setColor(mnMine[r][c] ? 0xFFE53B3B : cCard);
                int n = mnCount(r, c);
                mnCells[i].setText(mnMine[r][c] ? "💣" : (n == 0 ? "" : String.valueOf(n)));
                mnCells[i].setTextColor(mnMine[r][c] ? 0xFFFFFFFF
                        : n == 1 ? 0xFF42A5F5 : n == 2 ? 0xFF2FD465 : 0xFFEF5350);
            } else {
                g.setColor(cCard2);
                mnCells[i].setText(mnFlag[r][c] ? "🚩" : "");
            }
            mnCells[i].setBackground(g);
        }
    }

    // ------------------------------------------------------------ Connect 4
    int[][] c4Board;
    boolean c4VsAi = true, c4RedTurn = true, c4Done;
    TextView stC4;
    TextView[] c4Cells;

    void gameConnect4() {
        TextView[] st = new TextView[1];
        gameScaffold("Connect 4", st);
        stC4 = st[0];
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        TextView mAi = chip("Vs AI", c4VsAi);
        TextView m2p = chip("2 Players", !c4VsAi);
        mAi.setOnClickListener(v -> { c4VsAi = true; openGame(9); });
        m2p.setOnClickListener(v -> { c4VsAi = false; openGame(9); });
        modes.addView(mAi);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(10), 1);
        modes.addView(new View(this), sp);
        modes.addView(m2p);
        root.addView(modes);
        c4Board = new int[6][7];
        c4RedTurn = true;
        c4Done = false;
        // column drop buttons
        LinearLayout dropRow = new LinearLayout(this);
        dropRow.setPadding(0, dp(10), 0, 0);
        for (int col = 0; col < 7; col++) {
            final int cc = col;
            TextView b = label("▼", 16, cAccent, true);
            b.setGravity(Gravity.CENTER);
            b.setOnClickListener(v -> c4Drop(cc));
            dropRow.addView(b, new LinearLayout.LayoutParams(0, dp(30), 1));
        }
        root.addView(dropRow);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(7);
        c4Cells = new TextView[42];
        for (int i = 0; i < 42; i++) {
            final int idx = i;
            TextView cell = label("", 24, cText, true);
            cell.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(48);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> c4Drop(idx % 7));
            c4Cells[i] = cell;
            grid.addView(cell);
        }
        root.addView(grid);
        TextView ng = chip("↻  New game", true);
        ng.setOnClickListener(v -> openGame(9));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(12);
        root.addView(ng, nl);
        c4Paint();
        stC4.setText(c4VsAi ? "You are 🔴 — pick a column" : "🔴 goes first");
    }

    void c4Drop(int col) {
        if (c4Done) return;
        int row = -1;
        for (int r = 5; r >= 0; r--) if (c4Board[r][col] == 0) { row = r; break; }
        if (row < 0) return;
        c4Board[row][col] = c4RedTurn ? 1 : 2;
        c4Paint();
        if (c4CheckEnd()) return;
        c4RedTurn = !c4RedTurn;
        if (c4VsAi && !c4RedTurn) {
            ui.postDelayed(() -> {
                int mv = c4AiMove();
                if (mv >= 0) {
                    int rw = -1;
                    for (int r = 5; r >= 0; r--) if (c4Board[r][mv] == 0) { rw = r; break; }
                    if (rw >= 0) {
                        c4Board[rw][mv] = 2;
                        c4Paint();
                        if (!c4CheckEnd()) {
                            c4RedTurn = true;
                            stC4.setText("Your move");
                        }
                    }
                }
            }, 380);
        } else {
            stC4.setText((c4RedTurn ? "🔴" : "🟡") + " to move");
        }
    }

    boolean c4Win(int who) {
        int[][] dirs = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
        for (int r = 0; r < 6; r++) for (int c = 0; c < 7; c++) {
            if (c4Board[r][c] != who) continue;
            for (int[] d : dirs) {
                int n = 1;
                for (int k = 1; k < 4; k++) {
                    int rr = r + d[0] * k, cc = c + d[1] * k;
                    if (rr < 0 || rr >= 6 || cc < 0 || cc >= 7 || c4Board[rr][cc] != who) break;
                    n++;
                }
                if (n >= 4) return true;
            }
        }
        return false;
    }

    boolean c4CheckEnd() {
        if (c4Win(1)) {
            c4Done = true;
            if (c4VsAi) {
                stC4.setText("🎉 You win!");
                saveBest("best_c4", best("best_c4", 0) + 1, false);
            } else stC4.setText("🔴 wins! 🎉");
            return true;
        }
        if (c4Win(2)) {
            c4Done = true;
            stC4.setText(c4VsAi ? "AI wins — try again" : "🟡 wins! 🎉");
            return true;
        }
        boolean full = true;
        for (int c = 0; c < 7; c++) if (c4Board[0][c] == 0) full = false;
        if (full) {
            c4Done = true;
            stC4.setText("Draw!");
            return true;
        }
        return false;
    }

    int c4AiMove() {
        // win, then block, then center-biased random
        for (int pass = 0; pass < 2; pass++) {
            int who = pass == 0 ? 2 : 1;
            for (int col = 0; col < 7; col++) {
                int row = -1;
                for (int r = 5; r >= 0; r--) if (c4Board[r][col] == 0) { row = r; break; }
                if (row < 0) continue;
                c4Board[row][col] = who;
                boolean wins = c4Win(who);
                c4Board[row][col] = 0;
                if (wins) return col;
            }
        }
        int[] pref = {3, 2, 4, 1, 5, 0, 6};
        List<Integer> opts = new ArrayList<>();
        for (int col : pref) if (c4Board[0][col] == 0) opts.add(col);
        if (opts.isEmpty()) return -1;
        return opts.get(rnd.nextInt(Math.min(3, opts.size())));
    }

    void c4Paint() {
        for (int i = 0; i < 42; i++) {
            int v = c4Board[i / 7][i % 7];
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(v == 1 ? 0xFFEF5350 : v == 2 ? 0xFFFFCA28 : cCard2);
            c4Cells[i].setBackground(g);
            c4Cells[i].setText("");
            GridLayout.LayoutParams lp = (GridLayout.LayoutParams) c4Cells[i].getLayoutParams();
            lp.height = dp(44);
            c4Cells[i].setLayoutParams(lp);
        }
    }

    // ------------------------------------------------------------ Dots and Boxes
    // 4x3 boxes. hEdges[4][4] (dot rows), vEdges[3][5], boxOwner[3][4]
    boolean[][] dbH, dbV;
    int[][] dbBox;
    boolean dbVsAi = true, dbP1Turn = true, dbDone;
    int dbScore1, dbScore2;
    TextView stDb;
    ViewDots viewDb;

    final class ViewDots extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewDots() { super(GamesActivity.this); }
        float ox() { return getWidth() * 0.08f; }
        float oy() { return getHeight() * 0.06f; }
        float sx() { return getWidth() * 0.84f / 4f; }
        float sy() { return getHeight() * 0.88f / 3f; }
        @Override protected void onDraw(Canvas cv) {
            float ox = ox(), oy = oy(), sx = sx(), sy = sy();
            // box fills
            for (int r = 0; r < 3; r++) for (int c = 0; c < 4; c++) {
                if (dbBox[r][c] != 0) {
                    p.setColor(dbBox[r][c] == 1 ? 0x55EF5350 : 0x55FFCA28);
                    cv.drawRect(ox + c * sx + 3, oy + r * sy + 3,
                            ox + (c + 1) * sx - 3, oy + (r + 1) * sy - 3, p);
                }
            }
            p.setStrokeWidth(dp(5));
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
                p.setColor(dbH[r][c] ? cText : 0x33FFFFFF);
                cv.drawLine(ox + c * sx, oy + r * sy, ox + (c + 1) * sx, oy + r * sy, p);
            }
            for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) {
                p.setColor(dbV[r][c] ? cText : 0x33FFFFFF);
                cv.drawLine(ox + c * sx, oy + r * sy, ox + c * sx, oy + (r + 1) * sy, p);
            }
            p.setColor(cAccent);
            for (int r = 0; r < 4; r++) for (int c = 0; c < 5; c++) {
                cv.drawCircle(ox + c * sx, oy + r * sy, dp(5), p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_DOWN || dbDone) return true;
            if (dbVsAi && !dbP1Turn) return true;
            float ox = ox(), oy = oy(), sx = sx(), sy = sy();
            float x = e.getX(), y = e.getY();
            double bestD = Double.MAX_VALUE;
            int bType = -1, bR = -1, bC = -1; // 0 = H, 1 = V
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
                if (dbH[r][c]) continue;
                float mx = ox + (c + 0.5f) * sx, my = oy + r * sy;
                double d = Math.hypot(x - mx, y - my);
                if (d < bestD) { bestD = d; bType = 0; bR = r; bC = c; }
            }
            for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) {
                if (dbV[r][c]) continue;
                float mx = ox + c * sx, my = oy + (r + 0.5f) * sy;
                double d = Math.hypot(x - mx, y - my);
                if (d < bestD) { bestD = d; bType = 1; bR = r; bC = c; }
            }
            if (bType >= 0 && bestD < sx * 0.62) dbPlay(bType, bR, bC);
            return true;
        }
    }

    void gameDots() {
        TextView[] st = new TextView[1];
        gameScaffold("Dots and Boxes", st);
        stDb = st[0];
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        TextView mAi = chip("Vs AI", dbVsAi);
        TextView m2p = chip("2 Players", !dbVsAi);
        mAi.setOnClickListener(v -> { dbVsAi = true; openGame(10); });
        m2p.setOnClickListener(v -> { dbVsAi = false; openGame(10); });
        modes.addView(mAi);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(10), 1);
        modes.addView(new View(this), sp);
        modes.addView(m2p);
        root.addView(modes);
        dbH = new boolean[4][4];
        dbV = new boolean[3][5];
        dbBox = new int[3][4];
        dbP1Turn = true;
        dbDone = false;
        dbScore1 = 0;
        dbScore2 = 0;
        viewDb = new ViewDots();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewDb, new LinearLayout.LayoutParams(w, (int) (w * 0.78f)));
        root.addView(label("Tap between two dots to draw a line. Complete a box to "
                + "claim it — and take another turn.", 12, cDim, false));
        dbStatus();
    }

    void dbStatus() {
        stDb.setText("🔴 " + dbScore1 + "  —  " + dbScore2 + " 🟡   •   "
                + (dbP1Turn ? "Red" : "Yellow") + " to move");
    }

    int dbBoxEdges(int r, int c) {
        int n = 0;
        if (dbH[r][c]) n++;
        if (dbH[r + 1][c]) n++;
        if (dbV[r][c]) n++;
        if (dbV[r][c + 1]) n++;
        return n;
    }

    void dbPlay(int type, int r, int c) {
        if (type == 0) dbH[r][c] = true; else dbV[r][c] = true;
        int claimed = 0;
        for (int br = 0; br < 3; br++) for (int bc = 0; bc < 4; bc++) {
            if (dbBox[br][bc] == 0 && dbBoxEdges(br, bc) == 4) {
                dbBox[br][bc] = dbP1Turn ? 1 : 2;
                claimed++;
                if (dbP1Turn) dbScore1++; else dbScore2++;
            }
        }
        viewDb.invalidate();
        boolean full = true;
        for (int br = 0; br < 3; br++) for (int bc = 0; bc < 4; bc++) {
            if (dbBox[br][bc] == 0) full = false;
        }
        if (full) {
            dbDone = true;
            String w = dbScore1 > dbScore2 ? "🔴 Red wins!" : dbScore2 > dbScore1 ? "🟡 Yellow wins!" : "Draw!";
            stDb.setText("🎉 " + w + "  (" + dbScore1 + "–" + dbScore2 + ")");
            if (dbVsAi && dbScore1 > dbScore2) saveBest("best_dots", best("best_dots", 0) + 1, false);
            return;
        }
        if (claimed == 0) dbP1Turn = !dbP1Turn;
        dbStatus();
        if (dbVsAi && !dbP1Turn && !dbDone) {
            ui.postDelayed(this::dbAiMove, 420);
        }
    }

    void dbAiMove() {
        if (dbDone || dbP1Turn) return;
        // 1) complete a box
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            if (!dbH[r][c]) {
                dbH[r][c] = true;
                boolean comp = dbCompletesAny();
                dbH[r][c] = false;
                if (comp) { dbPlay(0, r, c); return; }
            }
        }
        for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) {
            if (!dbV[r][c]) {
                dbV[r][c] = true;
                boolean comp = dbCompletesAny();
                dbV[r][c] = false;
                if (comp) { dbPlay(1, r, c); return; }
            }
        }
        // 2) safe edge (does not hand the opponent a 3-sided box)
        List<int[]> safe = new ArrayList<>(), all = new ArrayList<>();
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) {
            if (dbH[r][c]) continue;
            all.add(new int[]{0, r, c});
            boolean bad = false;
            if (r > 0 && dbBoxEdges(r - 1, c) == 2) bad = true;
            if (r < 3 && dbBoxEdges(r, c) == 2) bad = true;
            if (!bad) safe.add(new int[]{0, r, c});
        }
        for (int r = 0; r < 3; r++) for (int c = 0; c < 5; c++) {
            if (dbV[r][c]) continue;
            all.add(new int[]{1, r, c});
            boolean bad = false;
            if (c > 0 && dbBoxEdges(r, c - 1) == 2) bad = true;
            if (c < 4 && dbBoxEdges(r, c) == 2) bad = true;
            if (!bad) safe.add(new int[]{1, r, c});
        }
        List<int[]> pool = safe.isEmpty() ? all : safe;
        if (pool.isEmpty()) return;
        int[] mv = pool.get(rnd.nextInt(pool.size()));
        dbPlay(mv[0], mv[1], mv[2]);
    }

    boolean dbCompletesAny() {
        for (int br = 0; br < 3; br++) for (int bc = 0; bc < 4; bc++) {
            if (dbBox[br][bc] == 0 && dbBoxEdges(br, bc) == 4) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ Snakes & Ladders
    static final int[][] SNL_MAP = { // {from, to}
            {2, 38}, {4, 14}, {9, 31}, {21, 42}, {28, 84}, {36, 44}, {51, 67}, {71, 91}, {80, 100},
            {16, 6}, {47, 26}, {49, 11}, {56, 53}, {62, 19}, {64, 60}, {87, 24}, {93, 73}, {95, 75}, {98, 78}};
    int[] snlPos;
    boolean snlVsAi = true, snlP1Turn = true, snlDone, snlRolling;
    TextView stSnl, snlDiceBtn;
    ViewSnl viewSnl;

    final class ViewSnl extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewSnl() { super(GamesActivity.this); }
        float cell() { return getWidth() / 10f; }
        // cell index 0..99 (square 1..100) -> center xy, bottom row = squares 1..10
        float[] center(int sq) {
            int idx = sq - 1;
            int rowFromBottom = idx / 10;
            int colInRow = idx % 10;
            int col = rowFromBottom % 2 == 0 ? colInRow : 9 - colInRow;
            float cs = cell();
            return new float[]{col * cs + cs / 2f, getHeight() - (rowFromBottom * cs + cs / 2f)};
        }
        @Override protected void onDraw(Canvas cv) {
            float cs = cell();
            // colorful Play Store-style cells
            int[] pastel = {0xFFFFF8E1, 0xFFE3F2FD, 0xFFFCE4EC, 0xFFE8F5E9, 0xFFFFF3E0, 0xFFF3E5F5};
            for (int sq = 1; sq <= 100; sq++) {
                float[] ctr = center(sq);
                int rowB = (sq - 1) / 10, colR = (sq - 1) % 10;
                p.setColor(pastel[(rowB * 3 + colR) % pastel.length]);
                cv.drawRect(ctr[0] - cs / 2f, ctr[1] - cs / 2f, ctr[0] + cs / 2f, ctr[1] + cs / 2f, p);
                p.setColor(0xFF5D6D7E);
                p.setTextSize(cs * 0.24f);
                p.setTextAlign(Paint.Align.CENTER);
                cv.drawText(String.valueOf(sq), ctr[0], ctr[1] - cs * 0.2f, p);
            }
            // ladders: two rails + rungs
            for (int[] pair : SNL_MAP) {
                if (pair[1] < pair[0]) continue; // snakes below
                float[] a = center(pair[0]), bb = center(pair[1]);
                double ang = Math.atan2(bb[1] - a[1], bb[0] - a[0]);
                double perp = ang + Math.PI / 2;
                float off = cs * 0.14f;
                float ox = (float) Math.cos(perp) * off, oy = (float) Math.sin(perp) * off;
                p.setStrokeWidth(dp(3));
                p.setColor(0xFFB07D3C);
                cv.drawLine(a[0] + ox, a[1] + oy, bb[0] + ox, bb[1] + oy, p);
                cv.drawLine(a[0] - ox, a[1] - oy, bb[0] - ox, bb[1] - oy, p);
                p.setStrokeWidth(dp(2));
                int rungs = 4;
                for (int i = 1; i <= rungs; i++) {
                    float t = i / (float) (rungs + 1);
                    float x = a[0] + (bb[0] - a[0]) * t, y = a[1] + (bb[1] - a[1]) * t;
                    cv.drawLine(x + ox, y + oy, x - ox, y - oy, p);
                }
            }
            // snakes: wavy body + head
            int snakeColor = 0;
            for (int[] pair : SNL_MAP) {
                if (pair[1] > pair[0]) continue; // ladders above
                float[] head = center(pair[0]), tail = center(pair[1]);
                int[] cols = {0xFF43A047, 0xFF8E24AA, 0xFF00897B, 0xFF6D4C41, 0xFF3949AB};
                int col = cols[snakeColor++ % cols.length];
                double ang = Math.atan2(tail[1] - head[1], tail[0] - head[0]);
                double perp = ang + Math.PI / 2;
                float dist = (float) Math.hypot(tail[0] - head[0], tail[1] - head[1]);
                int segs = Math.max(6, (int) (dist / (cs * 0.3f)));
                p.setStrokeWidth(cs * 0.3f);
                p.setStrokeCap(Paint.Cap.ROUND);
                float prevX = head[0], prevY = head[1];
                for (int i = 1; i <= segs; i++) {
                    float t = i / (float) segs;
                    float wave = (float) Math.sin(t * Math.PI * 3) * cs * 0.34f * (1 - t * 0.5f);
                    float x = head[0] + (tail[0] - head[0]) * t + (float) Math.cos(perp) * wave;
                    float y = head[1] + (tail[1] - head[1]) * t + (float) Math.sin(perp) * wave;
                    p.setColor(col);
                    cv.drawLine(prevX, prevY, x, y, p);
                    prevX = x; prevY = y;
                }
                p.setStrokeCap(Paint.Cap.BUTT);
                p.setColor(col);
                cv.drawCircle(head[0], head[1], cs * 0.3f, p);
                p.setColor(0xFFFFFFFF);
                cv.drawCircle(head[0] - cs * 0.1f, head[1] - cs * 0.08f, cs * 0.07f, p);
                cv.drawCircle(head[0] + cs * 0.1f, head[1] - cs * 0.08f, cs * 0.07f, p);
                p.setColor(0xFF111111);
                cv.drawCircle(head[0] - cs * 0.1f, head[1] - cs * 0.08f, cs * 0.035f, p);
                cv.drawCircle(head[0] + cs * 0.1f, head[1] - cs * 0.08f, cs * 0.035f, p);
            }
            // tokens (pawns)
            for (int pl = 0; pl < 2; pl++) {
                if (snlPos[pl] <= 0) continue;
                float[] ctr = center(snlPos[pl]);
                float off = pl == 0 ? -cs * 0.17f : cs * 0.17f;
                p.setColor(pl == 0 ? 0xFFE53935 : 0xFFFFB300);
                cv.drawCircle(ctr[0] + off, ctr[1] + cs * 0.16f, cs * 0.23f, p);
                p.setColor(0xFFFFFFFF);
                p.setStrokeWidth(dp(2));
                p.setStyle(Paint.Style.STROKE);
                cv.drawCircle(ctr[0] + off, ctr[1] + cs * 0.16f, cs * 0.23f, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                p.setTextSize(cs * 0.26f);
                cv.drawText(pl == 0 ? "1" : "2", ctr[0] + off, ctr[1] + cs * 0.16f + cs * 0.1f, p);
            }
        }
    }

    void gameSnakesLadders() {
        TextView[] st = new TextView[1];
        gameScaffold("Snakes & Ladders", st);
        stSnl = st[0];
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        TextView mAi = chip("Vs AI", snlVsAi);
        TextView m2p = chip("2 Players", !snlVsAi);
        mAi.setOnClickListener(v -> { snlVsAi = true; openGame(11); });
        m2p.setOnClickListener(v -> { snlVsAi = false; openGame(11); });
        modes.addView(mAi);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(10), 1);
        modes.addView(new View(this), sp);
        modes.addView(m2p);
        root.addView(modes);
        snlPos = new int[]{0, 0};
        snlP1Turn = true;
        snlDone = false;
        snlRolling = false;
        viewSnl = new ViewSnl();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewSnl, new LinearLayout.LayoutParams(w, w));
        viewSnl.setOnClickListener(v -> snlRoll());
        snlDiceBtn = chip("🎲  Roll — Red's turn", true);
        snlDiceBtn.setOnClickListener(v -> snlRoll());
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(12);
        root.addView(snlDiceBtn, nl);
        root.addView(label("Roll a 6 to leave base. Ladders (green) climb, snakes (red) slide. "
                + "First to exactly 100 wins.", 12, cDim, false));
        snlStatus(0);
    }

    void snlStatus(int dice) {
        String who = snlP1Turn ? "🔴 Red" : "🟡 Yellow";
        stSnl.setText(who + " — at " + Math.max(0, snlPos[snlP1Turn ? 0 : 1])
                + (dice > 0 ? "   •   rolled " + dice : ""));
        snlDiceBtn.setText("🎲  Roll — " + (snlP1Turn ? "Red" : "Yellow") + "'s turn");
    }

    void snlRoll() {
        if (snlDone || snlRolling) return;
        if (snlVsAi && !snlP1Turn) return;
        snlDoRoll();
    }

    void snlDoRoll() {
        snlRolling = true;
        int dice = 1 + rnd.nextInt(6);
        int pl = snlP1Turn ? 0 : 1;
        int cur = snlPos[pl];
        if (cur == 0) {
            if (dice == 6) snlPos[pl] = 1;
        } else {
            int next = cur + dice;
            if (next <= 100) {
                for (int[] pair : SNL_MAP) if (pair[0] == next) { next = pair[1]; break; }
                snlPos[pl] = next;
            }
        }
        viewSnl.invalidate();
        if (snlPos[pl] == 100) {
            snlDone = true;
            boolean humanWon = pl == 0;
            stSnl.setText("🎉 " + (pl == 0 ? "🔴 Red" : "🟡 Yellow") + " reaches 100 and wins!");
            snlDiceBtn.setText("🎉 Game over");
            if (!snlVsAi || humanWon) saveBest("best_snl", best("best_snl", 0) + 1, false);
            snlRolling = false;
            return;
        }
        boolean again = dice == 6;
        if (!again) snlP1Turn = !snlP1Turn;
        snlStatus(dice);
        snlRolling = false;
        if (snlVsAi && !snlP1Turn && !snlDone) {
            ui.postDelayed(this::snlDoRoll, 650);
        }
    }

    // ------------------------------------------------------------ Brick Breaker
    float bkBallX, bkBallY, bkBallVX, bkBallVY, bkPaddleX;
    boolean bkRunning, bkLaunched, bkOver;
    int bkScore, bkLives;
    boolean[][] bkBricks;
    TextView stBk;
    ViewBricks viewBk;
    static final int[] BK_ROW_COLORS = {0xFFEF5350, 0xFFFF9800, 0xFFFFEB3B, 0xFF2FD465, 0xFF19C8E8, 0xFFAB47BC};
    final Runnable bkTick = new Runnable() {
        @Override public void run() {
            if (!bkRunning) return;
            bkStep();
            viewBk.invalidate();
            ui.postDelayed(this, 16);
        }
    };

    final class ViewBricks extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewBricks() { super(GamesActivity.this); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setColor(cCard);
            cv.drawRect(0, 0, w, h, p);
            float bw = w / 8f, bh = h * 0.045f;
            for (int r = 0; r < 6; r++) for (int c = 0; c < 8; c++) {
                if (!bkBricks[r][c]) continue;
                p.setColor(BK_ROW_COLORS[r]);
                cv.drawRoundRect(new RectF(c * bw + 2, h * 0.06f + r * (bh + 4),
                        (c + 1) * bw - 2, h * 0.06f + r * (bh + 4) + bh), 4, 4, p);
            }
            float pw = w * 0.22f, ph = h * 0.022f, py = h * 0.93f;
            p.setColor(cAccent);
            cv.drawRoundRect(new RectF(bkPaddleX - pw / 2f, py, bkPaddleX + pw / 2f, py + ph), dp(6), dp(6), p);
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(bkBallX, bkBallY, w * 0.016f + dp(3), p);
            if (!bkLaunched && !bkOver) {
                p.setColor(cDim);
                p.setTextSize(dp(14));
                p.setTextAlign(Paint.Align.CENTER);
                cv.drawText("Drag to move the paddle — tap to launch", w / 2f, h * 0.6f, p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float w = getWidth();
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                bkPaddleX = Math.max(w * 0.11f, Math.min(w * 0.89f, e.getX()));
                if (!bkLaunched && !bkOver) {
                    bkBallX = bkPaddleX;
                }
                invalidate();
            }
            if (e.getAction() == MotionEvent.ACTION_UP && !bkLaunched && !bkOver && bkRunning) {
                bkLaunched = true;
                float sp = w * 0.011f;
                bkBallVX = sp * 0.6f;
                bkBallVY = -sp;
            }
            return true;
        }
    }

    void gameBricks() {
        TextView[] st = new TextView[1];
        gameScaffold("Brick Breaker", st);
        stBk = st[0];
        bkBricks = new boolean[6][8];
        for (int r = 0; r < 6; r++) java.util.Arrays.fill(bkBricks[r], true);
        bkScore = 0;
        bkLives = 3;
        bkOver = false;
        bkLaunched = false;
        viewBk = new ViewBricks();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewBk, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        ui.post(() -> {
            bkPaddleX = viewBk.getWidth() / 2f;
            bkBallX = bkPaddleX;
            bkBallY = viewBk.getHeight() * 0.9f;
            viewBk.invalidate();
        });
        TextView start = chip("▶  Start", true);
        start.setOnClickListener(v -> {
            if (bkOver) { openGame(3); return; }
            bkRunning = !bkRunning;
            if (bkRunning) {
                activeLoop = bkTick;
                ui.removeCallbacks(bkTick);
                ui.postDelayed(bkTick, 16);
                start.setText("⏸  Pause");
            } else start.setText("▶  Resume");
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(10);
        root.addView(start, nl);
        bkStatus();
    }

    void bkStatus() {
        stBk.setText("Score: " + bkScore + "   •   Lives: " + bkLives
                + "   •   Best: " + Math.max(best("best_bricks", 0), bkScore));
    }

    void bkStep() {
        float w = viewBk.getWidth(), h = viewBk.getHeight();
        if (!bkLaunched) return;
        float r = w * 0.016f + dp(3);
        bkBallX += bkBallVX;
        bkBallY += bkBallVY;
        if (bkBallX < r) { bkBallX = r; bkBallVX = Math.abs(bkBallVX); }
        if (bkBallX > w - r) { bkBallX = w - r; bkBallVX = -Math.abs(bkBallVX); }
        if (bkBallY < r) { bkBallY = r; bkBallVY = Math.abs(bkBallVY); }
        float pw = w * 0.22f, ph = h * 0.022f, py = h * 0.93f;
        if (bkBallVY > 0 && bkBallY + r >= py && bkBallY - r <= py + ph
                && Math.abs(bkBallX - bkPaddleX) <= pw / 2f + r) {
            float off = (bkBallX - bkPaddleX) / (pw / 2f);
            float sp = (float) Math.hypot(bkBallVX, bkBallVY);
            double ang = Math.toRadians(-90 + off * 55);
            bkBallVX = (float) (sp * Math.cos(ang));
            bkBallVY = (float) (sp * Math.sin(ang));
            bkBallY = py - r - 1;
        }
        float bw = w / 8f, bh = h * 0.045f;
        boolean cleared = true;
        outer:
        for (int rr = 0; rr < 6; rr++) for (int cc = 0; cc < 8; cc++) {
            if (!bkBricks[rr][cc]) continue;
            cleared = false;
            float bx = cc * bw + 2, by = h * 0.06f + rr * (bh + 4);
            if (bkBallX + r > bx && bkBallX - r < bx + bw - 4
                    && bkBallY + r > by && bkBallY - r < by + bh) {
                bkBricks[rr][cc] = false;
                bkBallVY = -bkBallVY;
                bkScore += 10;
                saveBest("best_bricks", bkScore, false);
                bkStatus();
                break outer;
            }
        }
        if (cleared) {
            // all bricks gone — refill, faster ball
            for (int rr = 0; rr < 6; rr++) java.util.Arrays.fill(bkBricks[rr], true);
            bkBallVX *= 1.12f;
            bkBallVY *= 1.12f;
            bkScore += 100;
        }
        if (bkBallY - r > h) {
            bkLives--;
            bkLaunched = false;
            bkBallX = bkPaddleX;
            bkBallY = h * 0.9f;
            bkStatus();
            if (bkLives <= 0) {
                bkOver = true;
                bkRunning = false;
                stBk.setText("Game over! Score: " + bkScore);
            }
        }
    }

    // ------------------------------------------------------------ Knife Hit
    double khAngle, khSpeed;
    final List<Double> khStuck = new ArrayList<>();
    int khLevel, khNeed, khThrown;
    boolean khRunning, khOver, khFlying;
    float khKnifeY; // 0..1 progress of flying knife
    TextView stKh;
    ViewKnife viewKh;
    final Runnable khTick = new Runnable() {
        @Override public void run() {
            if (!khRunning) return;
            khAngle = (khAngle + khSpeed) % 360;
            if (khFlying) {
                khKnifeY += 0.22f;
                if (khKnifeY >= 1f) khKnifeLand();
            }
            viewKh.invalidate();
            ui.postDelayed(this, 30);
        }
    };

    final class ViewKnife extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewKnife() { super(GamesActivity.this); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h * 0.34f, rad = w * 0.26f;
            // log
            p.setColor(0xFF8D6E63);
            cv.drawCircle(cx, cy, rad, p);
            p.setColor(0xFF6D4C41);
            cv.drawCircle(cx, cy, rad * 0.68f, p);
            p.setColor(0xFF8D6E63);
            cv.drawCircle(cx, cy, rad * 0.36f, p);
            // stuck knives (angles rotate with the log)
            p.setColor(0xFFCFD8DC);
            p.setStrokeWidth(dp(5));
            for (double a : khStuck) {
                double ang = Math.toRadians(a + khAngle);
                float x1 = cx + (float) Math.cos(ang) * rad * 0.92f;
                float y1 = cy + (float) Math.sin(ang) * rad * 0.92f;
                float x2 = cx + (float) Math.cos(ang) * rad * 1.45f;
                float y2 = cy + (float) Math.sin(ang) * rad * 1.45f;
                cv.drawLine(x1, y1, x2, y2, p);
                p.setColor(0xFF5D4037);
                cv.drawCircle(x2, y2, dp(5), p);
                p.setColor(0xFFCFD8DC);
            }
            // ready / flying knife at the bottom
            float kx = cx;
            float kTop = khFlying
                    ? h * 0.86f - (h * 0.86f - (cy + rad * 1.02f)) * khKnifeY
                    : h * 0.86f;
            p.setStrokeWidth(dp(6));
            p.setColor(0xFFCFD8DC);
            cv.drawLine(kx, kTop, kx, kTop + h * 0.1f, p);
            p.setColor(0xFF5D4037);
            cv.drawCircle(kx, kTop + h * 0.1f, dp(6), p);
            p.setColor(cDim);
            p.setTextSize(dp(13));
            p.setTextAlign(Paint.Align.CENTER);
            if (!khOver) cv.drawText("Tap anywhere to throw", cx, h * 0.97f, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN && khRunning && !khOver && !khFlying) {
                khFlying = true;
                khKnifeY = 0f;
            }
            return true;
        }
    }

    void khKnifeLand() {
        khFlying = false;
        // knife hits the bottom of the log: its angle on the log frame
        double hit = (90 - khAngle % 360 + 360) % 360;
        for (double a : khStuck) {
            double diff = Math.abs(((a - hit) % 360 + 540) % 360 - 180);
            if (diff < 13) {
                khOver = true;
                khRunning = false;
                stKh.setText("💥 Knife clash! Reached level " + khLevel);
                viewKh.invalidate();
                return;
            }
        }
        khStuck.add(hit);
        khThrown++;
        if (khThrown >= khNeed) {
            saveBest("best_knife", khLevel, false);
            khLevel++;
            khSetupLevel();
            stKh.setText("🎉 Level cleared! Now level " + khLevel);
        } else {
            khStatus();
        }
        viewKh.invalidate();
    }

    void khSetupLevel() {
        khStuck.clear();
        khThrown = 0;
        khNeed = 4 + Math.min(4, khLevel);
        int pre = Math.min(2 + khLevel, 6);
        for (int i = 0; i < pre; i++) khStuck.add(rnd.nextDouble() * 360);
        double base = 1.6 + khLevel * 0.25;
        khSpeed = (khLevel % 3 == 2) ? -base : base;
    }

    void khStatus() {
        stKh.setText("Level " + khLevel + "   •   Knives: " + khThrown + " / " + khNeed
                + "   •   Best: " + best("best_knife", 0));
    }

    void gameKnife() {
        TextView[] st = new TextView[1];
        gameScaffold("Knife Hit", st);
        stKh = st[0];
        khLevel = 1;
        khAngle = 0;
        khFlying = false;
        khOver = false;
        khSetupLevel();
        viewKh = new ViewKnife();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewKh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView start = chip("▶  Start", true);
        start.setOnClickListener(v -> {
            if (khOver) { openGame(7); return; }
            khRunning = !khRunning;
            if (khRunning) {
                activeLoop = khTick;
                ui.removeCallbacks(khTick);
                ui.postDelayed(khTick, 30);
                start.setText("⏸  Pause");
            } else start.setText("▶  Resume");
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(10);
        root.addView(start, nl);
        khStatus();
    }

    // ------------------------------------------------------------ Stack Tower
    final List<float[]> stBlocks = new ArrayList<>(); // {x, w} in unit space (view width = 1)
    float stMoveX, stDir, stCurW, stBaseW;
    int stCombo;
    boolean stRunning, stOver;
    TextView stSt;
    ViewStack viewSt;
    final Runnable stTick = new Runnable() {
        @Override public void run() {
            if (!stRunning) return;
            stMoveX += stDir * 0.011f;
            if (stMoveX > 1f - stCurW) { stMoveX = 1f - stCurW; stDir = -1; }
            if (stMoveX < 0f) { stMoveX = 0f; stDir = 1; }
            viewSt.invalidate();
            ui.postDelayed(this, 16);
        }
    };

    final class ViewStack extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewStack() { super(GamesActivity.this); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float bh = h * 0.055f;
            int total = stBlocks.size();
            int from = Math.max(0, total - 12);
            p.setTextAlign(Paint.Align.CENTER);
            for (int i = from; i < total; i++) {
                float[] b = stBlocks.get(i);
                int rowFromBottom = i - from;
                float y = h - (rowFromBottom + 1) * bh;
                p.setColor(Color.HSVToColor(new float[]{(i * 24) % 360, 0.62f, 0.92f}));
                cv.drawRoundRect(new RectF(b[0] * w + 1, y + 1, (b[0] + b[1]) * w - 1, y + bh - 1), 4, 4, p);
            }
            if (!stOver) {
                float y = h - (Math.min(total - from, 12) + 1) * bh;
                p.setColor(Color.HSVToColor(new float[]{(total * 24) % 360, 0.62f, 0.92f}));
                cv.drawRoundRect(new RectF(stMoveX * w + 1, y + 1,
                        (stMoveX + stCurW) * w - 1, y + bh - 1), 4, 4, p);
                p.setColor(cDim);
                p.setTextSize(dp(13));
                cv.drawText("Tap to drop the block", w / 2f, h * 0.08f, p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN && stRunning && !stOver) stDrop();
            return true;
        }
    }

    void stDrop() {
        float[] top = stBlocks.get(stBlocks.size() - 1);
        float left = Math.max(stMoveX, top[0]);
        float right = Math.min(stMoveX + stCurW, top[0] + top[1]);
        if (right <= left) {
            stOver = true;
            stRunning = false;
            saveBest("best_stack", stBlocks.size() - 1, false);
            stSt.setText("Game over! Tower height: " + (stBlocks.size() - 1));
            viewSt.invalidate();
            return;
        }
        stBlocks.add(new float[]{left, right - left});
        stCurW = right - left;
        stMoveX = 0.1f;
        stDir = 1;
        stSt.setText("Blocks: " + (stBlocks.size() - 1)
                + "   •   Best: " + Math.max(best("best_stack", 0), stBlocks.size() - 1));
        viewSt.invalidate();
    }

    void gameStack() {
        TextView[] st = new TextView[1];
        gameScaffold("Stack Tower", st);
        stSt = st[0];
        stBlocks.clear();
        stBaseW = 0.56f;
        stBlocks.add(new float[]{0.22f, stBaseW});
        stCurW = stBaseW;
        stMoveX = 0.1f;
        stDir = 1;
        stOver = false;
        viewSt = new ViewStack();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewSt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView start = chip("▶  Start", true);
        start.setOnClickListener(v -> {
            if (stOver) { openGame(6); return; }
            stRunning = !stRunning;
            if (stRunning) {
                activeLoop = stTick;
                ui.removeCallbacks(stTick);
                ui.postDelayed(stTick, 16);
                start.setText("⏸  Pause");
            } else start.setText("▶  Resume");
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(10);
        root.addView(start, nl);
        stSt.setText("Blocks: 0   •   Best: " + best("best_stack", 0));
    }

    // ------------------------------------------------------------ Classic Snake
    static final int SN_N = 17;
    final List<int[]> snBody = new ArrayList<>();
    int snDirX = 1, snDirY = 0, snNextX = 1, snNextY = 0, snFoodX, snFoodY, snScore;
    boolean snRunning, snOver;
    TextView stSn;
    ViewSnake viewSn;
    final Runnable snTick = new Runnable() {
        @Override public void run() {
            if (!snRunning) return;
            snStep();
            viewSn.invalidate();
            ui.postDelayed(this, Math.max(70, 150 - snScore * 2));
        }
    };

    final class ViewSnake extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float downX, downY;
        ViewSnake() { super(GamesActivity.this); }
        @Override protected void onDraw(Canvas cv) {
            float cs = getWidth() / (float) SN_N;
            p.setColor(cCard);
            cv.drawRect(0, 0, getWidth(), getWidth(), p);
            p.setColor(0xFFEF5350);
            cv.drawCircle(snFoodX * cs + cs / 2f, snFoodY * cs + cs / 2f, cs * 0.36f, p);
            for (int i = 0; i < snBody.size(); i++) {
                int[] seg = snBody.get(i);
                p.setColor(i == 0 ? 0xFF2FD465 : 0xFF1FA855);
                cv.drawRoundRect(new RectF(seg[0] * cs + 1.5f, seg[1] * cs + 1.5f,
                        (seg[0] + 1) * cs - 1.5f, (seg[1] + 1) * cs - 1.5f), cs * 0.24f, cs * 0.24f, p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downX = e.getX(); downY = e.getY();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.max(Math.abs(dx), Math.abs(dy)) < dp(18)) return true;
                if (Math.abs(dx) > Math.abs(dy)) snSetDir(dx > 0 ? 1 : -1, 0);
                else snSetDir(0, dy > 0 ? 1 : -1);
                return true;
            }
            return true;
        }
    }

    void snSetDir(int x, int y) {
        if (snDirX == -x && snDirY == -y) return; // no reversing
        snNextX = x;
        snNextY = y;
    }

    void snStep() {
        snDirX = snNextX;
        snDirY = snNextY;
        int[] head = snBody.get(0);
        int nx = head[0] + snDirX, ny = head[1] + snDirY;
        if (nx < 0 || nx >= SN_N || ny < 0 || ny >= SN_N) { snDie(); return; }
        for (int[] seg : snBody) if (seg[0] == nx && seg[1] == ny) { snDie(); return; }
        snBody.add(0, new int[]{nx, ny});
        if (nx == snFoodX && ny == snFoodY) {
            snScore++;
            saveBest("best_snake", snScore, false);
            snPlaceFood();
            stSn.setText("Score: " + snScore + "   •   Best: " + Math.max(best("best_snake", 0), snScore));
        } else {
            snBody.remove(snBody.size() - 1);
        }
    }

    void snDie() {
        snOver = true;
        snRunning = false;
        stSn.setText("Game over! Score: " + snScore);
    }

    void snPlaceFood() {
        while (true) {
            snFoodX = rnd.nextInt(SN_N);
            snFoodY = rnd.nextInt(SN_N);
            boolean onBody = false;
            for (int[] seg : snBody) if (seg[0] == snFoodX && seg[1] == snFoodY) onBody = true;
            if (!onBody) return;
        }
    }

    void gameSnake() {
        TextView[] st = new TextView[1];
        gameScaffold("Classic Snake", st);
        stSn = st[0];
        snBody.clear();
        snBody.add(new int[]{8, 8});
        snBody.add(new int[]{7, 8});
        snBody.add(new int[]{6, 8});
        snDirX = 1; snDirY = 0; snNextX = 1; snNextY = 0;
        snScore = 0;
        snOver = false;
        snPlaceFood();
        viewSn = new ViewSnake();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewSn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout arrows = new LinearLayout(this);
        arrows.setGravity(Gravity.CENTER);
        arrows.setPadding(0, dp(10), 0, 0);
        String[] syms = {"◀", "▲", "▼", "▶"};
        for (int i = 0; i < 4; i++) {
            final int dir = i;
            TextView b = chip(syms[i], false);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(60), dp(50));
            bl.setMargins(dp(4), 0, dp(4), 0);
            b.setLayoutParams(bl);
            b.setTextSize(17);
            b.setOnClickListener(v -> {
                if (dir == 0) snSetDir(-1, 0);
                else if (dir == 1) snSetDir(0, -1);
                else if (dir == 2) snSetDir(0, 1);
                else snSetDir(1, 0);
            });
            arrows.addView(b);
        }
        root.addView(arrows);
        TextView start = chip("▶  Start", true);
        start.setOnClickListener(v -> {
            if (snOver) { openGame(4); return; }
            snRunning = !snRunning;
            if (snRunning) {
                activeLoop = snTick;
                ui.removeCallbacks(snTick);
                ui.postDelayed(snTick, 140);
                start.setText("⏸  Pause");
            } else start.setText("▶  Resume");
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(10);
        root.addView(start, nl);
        stSn.setText("Score: 0   •   Best: " + best("best_snake", 0) + "  —  swipe or use arrows");
    }

    // ------------------------------------------------------------ Flappy Bird
    float flBirdY, flBirdV, flPipeX, flGapY;
    int flScore;
    boolean flRunning, flOver, flStarted;
    TextView stFl;
    ViewFlappy viewFl;
    final Runnable flTick = new Runnable() {
        @Override public void run() {
            if (!flRunning) return;
            flStep();
            viewFl.invalidate();
            ui.postDelayed(this, 16);
        }
    };

    final class ViewFlappy extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewFlappy() { super(GamesActivity.this); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setColor(0xFF0E2233);
            cv.drawRect(0, 0, w, h, p);
            float gapH = h * 0.26f;
            p.setColor(0xFF2FD465);
            cv.drawRect(flPipeX, 0, flPipeX + w * 0.16f, flGapY - gapH / 2f, p);
            cv.drawRect(flPipeX, flGapY + gapH / 2f, flPipeX + w * 0.16f, h, p);
            p.setColor(0xFF1FA855);
            cv.drawRect(flPipeX - 3, flGapY - gapH / 2f - h * 0.035f, flPipeX + w * 0.16f + 3, flGapY - gapH / 2f, p);
            cv.drawRect(flPipeX - 3, flGapY + gapH / 2f, flPipeX + w * 0.16f + 3, flGapY + gapH / 2f + h * 0.035f, p);
            // bird
            float bx = w * 0.3f, br = w * 0.045f;
            p.setColor(0xFFFFEB3B);
            cv.drawCircle(bx, flBirdY, br, p);
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(bx + br * 0.35f, flBirdY - br * 0.25f, br * 0.32f, p);
            p.setColor(0xFF111111);
            cv.drawCircle(bx + br * 0.42f, flBirdY - br * 0.25f, br * 0.15f, p);
            p.setColor(0xFFFF9800);
            cv.drawRect(bx + br * 0.7f, flBirdY, bx + br * 1.35f, flBirdY + br * 0.3f, p);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(dp(22));
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText(String.valueOf(flScore), w / 2f, h * 0.12f, p);
            if (!flStarted && !flOver) {
                p.setTextSize(dp(14));
                cv.drawText("Tap to flap", w / 2f, h * 0.55f, p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN && flRunning && !flOver) {
                flStarted = true;
                flBirdV = -getHeight() * 0.011f;
            }
            return true;
        }
    }

    void flStep() {
        float w = viewFl.getWidth(), h = viewFl.getHeight();
        if (!flStarted) return;
        flBirdV += h * 0.00055f;
        flBirdY += flBirdV;
        flPipeX -= w * 0.005f;
        if (flPipeX < -w * 0.18f) {
            flPipeX = w;
            flGapY = h * (0.3f + rnd.nextFloat() * 0.4f);
            flScore++;
            saveBest("best_flappy", flScore, false);
            stFl.setText("Score: " + flScore + "   •   Best: " + Math.max(best("best_flappy", 0), flScore));
        }
        float br = w * 0.045f, bx = w * 0.3f, gapH = h * 0.26f;
        boolean hitPipe = bx + br > flPipeX && bx - br < flPipeX + w * 0.16f
                && (flBirdY - br < flGapY - gapH / 2f || flBirdY + br > flGapY + gapH / 2f);
        if (hitPipe || flBirdY + br > h || flBirdY - br < 0) {
            flOver = true;
            flRunning = false;
            stFl.setText("Game over! Score: " + flScore);
        }
    }

    void gameFlappy() {
        TextView[] st = new TextView[1];
        gameScaffold("Flappy Bird", st);
        stFl = st[0];
        flScore = 0;
        flOver = false;
        flStarted = false;
        viewFl = new ViewFlappy();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewFl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        ui.post(() -> {
            flBirdY = viewFl.getHeight() * 0.45f;
            flBirdV = 0;
            flPipeX = viewFl.getWidth() * 0.9f;
            flGapY = viewFl.getHeight() * 0.5f;
            viewFl.invalidate();
        });
        TextView start = chip("▶  Start", true);
        start.setOnClickListener(v -> {
            if (flOver) { openGame(5); return; }
            flRunning = !flRunning;
            if (flRunning) {
                activeLoop = flTick;
                ui.removeCallbacks(flTick);
                ui.postDelayed(flTick, 16);
                start.setText("⏸  Pause");
            } else start.setText("▶  Resume");
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(10);
        root.addView(start, nl);
        stFl.setText("Score: 0   •   Best: " + best("best_flappy", 0));
    }

    // ------------------------------------------------------------ Carrom Pool (lite)
    // Coin: {x, y, vx, vy, type} type: 0 white, 1 black, 2 queen, 3 striker (unit board 0..1)
    final List<float[]> crCoins = new ArrayList<>();
    boolean crVsAi = true, crP1Turn = true, crMoving, crAiming, crOver;
    int crScore1, crScore2;
    float crAimAngle = -90f, crAimPower = 0.6f, crTouchX, crTouchY;
    TextView stCr;
    ViewCarrom viewCr;
    static final float[][] CR_POCKETS = {{0.03f, 0.03f}, {0.5f, 0.02f}, {0.97f, 0.03f},
            {0.03f, 0.97f}, {0.5f, 0.98f}, {0.97f, 0.97f}};
    final Runnable crTick = new Runnable() {
        @Override public void run() {
            if (!crMoving) return;
            crStep();
            viewCr.invalidate();
            if (crAllStopped()) {
                crMoving = false;
                crEndTurn();
            } else {
                ui.postDelayed(this, 16);
            }
        }
    };

    final class ViewCarrom extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewCarrom() { super(GamesActivity.this); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            p.setColor(0xFFF3D9A4);
            cv.drawRect(0, 0, w, w, p);
            p.setColor(0xFFE8C87E);
            cv.drawRect(w * 0.05f, w * 0.05f, w * 0.95f, w * 0.95f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0xFFB07D3C);
            cv.drawCircle(w / 2f, w / 2f, w * 0.16f, p);
            p.setStyle(Paint.Style.FILL);
            for (float[] pk : CR_POCKETS) {
                p.setColor(0xFF263238);
                cv.drawCircle(pk[0] * w, pk[1] * w, w * 0.035f, p);
            }
            // baselines
            p.setColor(0x88B07D3C);
            cv.drawRect(w * 0.22f, w * 0.845f, w * 0.78f, w * 0.855f, p);
            cv.drawRect(w * 0.22f, w * 0.145f, w * 0.78f, w * 0.155f, p);
            // aim guide
            float[] st = crStriker();
            if (crAiming && st != null && crMyTurnInteractive()) {
                p.setColor(0xFFEF5350);
                p.setStrokeWidth(dp(2));
                float len = w * 0.3f * crAimPower + w * 0.08f;
                double a = Math.toRadians(crAimAngle);
                cv.drawLine(st[0] * w, st[1] * w,
                        st[0] * w + (float) Math.cos(a) * len, st[1] * w + (float) Math.sin(a) * len, p);
                p.setStyle(Paint.Style.FILL);
            }
            for (float[] c : crCoins) {
                int t = (int) c[4];
                p.setColor(t == 0 ? 0xFFFFFFFF : t == 1 ? 0xFF263238 : t == 2 ? 0xFFE53B3B : 0xFFFFEB3B);
                cv.drawCircle(c[0] * w, c[1] * w, w * (t == 3 ? 0.026f : 0.021f), p);
                p.setColor(0x33000000);
                cv.drawCircle(c[0] * w, c[1] * w, w * 0.008f, p);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (crMoving || crOver || !crMyTurnInteractive()) return true;
            float[] st = crStriker();
            if (st == null) return true;
            float w = getWidth();
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                // striker slide along the baseline
                float tx = e.getX() / w;
                if (Math.abs(e.getY() / w - st[1]) < 0.09f && tx > 0.22f && tx < 0.78f) {
                    st[0] = tx;
                    invalidate();
                    return true;
                }
                crAiming = true;
                crTouchX = e.getX() / w;
                crTouchY = e.getY() / w;
                crUpdateAim(st);
                invalidate();
            } else if (e.getAction() == MotionEvent.ACTION_MOVE && crAiming) {
                crTouchX = e.getX() / w;
                crTouchY = e.getY() / w;
                crUpdateAim(st);
                invalidate();
            } else if (e.getAction() == MotionEvent.ACTION_UP && crAiming) {
                crAiming = false;
                crShoot(st);
            }
            return true;
        }
    }

    boolean crMyTurnInteractive() {
        return !crVsAi || crP1Turn;
    }

    void crUpdateAim(float[] st) {
        float dx = crTouchX - st[0], dy = crTouchY - st[1];
        double ang = Math.toDegrees(Math.atan2(dy, dx));
        // shots must go toward the opponent side (up for P1 baseline at bottom)
        if (crP1Turn) ang = Math.max(-170, Math.min(-10, ang));
        else ang = Math.max(10, Math.min(170, ang));
        crAimAngle = (float) ang;
        crAimPower = Math.min(1f, (float) Math.hypot(dx, dy) * 1.6f);
    }

    float[] crStriker() {
        for (float[] c : crCoins) if ((int) c[4] == 3) return c;
        return null;
    }

    void crShoot(float[] st) {
        crPocketedThisTurn.clear();
        crStrikerPocketed = false;
        double a = Math.toRadians(crAimAngle);
        float sp = 0.012f + crAimPower * 0.02f;
        st[2] = (float) Math.cos(a) * sp;
        st[3] = (float) Math.sin(a) * sp;
        crMoving = true;
        activeLoop = crTick;
        ui.removeCallbacks(crTick);
        ui.postDelayed(crTick, 16);
    }

    void crAiShoot() {
        crPocketedThisTurn.clear();
        crStrikerPocketed = false;
        float[] st = crStriker();
        if (st == null || crOver) return;
        // pick the AI coin (black) closest to any pocket, aim striker through it
        float bestD = Float.MAX_VALUE;
        float[] target = null;
        for (float[] c : crCoins) {
            if ((int) c[4] != 1) continue;
            for (float[] pk : CR_POCKETS) {
                float d = (float) Math.hypot(c[0] - pk[0], c[1] - pk[1]);
                if (d < bestD) { bestD = d; target = c; }
            }
        }
        if (target == null) { // only queen/white left: aim at queen
            for (float[] c : crCoins) if ((int) c[4] == 2) target = c;
        }
        if (target == null) { crEndTurnPass(); return; }
        // slide striker toward the target's x a little, then shoot at it with noise
        st[0] = Math.max(0.23f, Math.min(0.77f, target[0] + (rnd.nextFloat() - 0.5f) * 0.1f));
        double ang = Math.atan2(target[1] - st[1], target[0] - st[0]);
        ang += (rnd.nextDouble() - 0.5) * 0.09;
        st[2] = (float) Math.cos(ang) * 0.024f;
        st[3] = (float) Math.sin(ang) * 0.024f;
        crMoving = true;
        activeLoop = crTick;
        ui.removeCallbacks(crTick);
        ui.postDelayed(crTick, 16);
        viewCr.invalidate();
    }

    boolean crAllStopped() {
        for (float[] c : crCoins) {
            if (Math.abs(c[2]) > 0.0006f || Math.abs(c[3]) > 0.0006f) return false;
        }
        return true;
    }

    List<float[]> crPocketedThisTurn = new ArrayList<>();
    boolean crStrikerPocketed;

    void crStep() {
        List<float[]> dead = new ArrayList<>();
        for (float[] c : crCoins) {
            c[0] += c[2];
            c[1] += c[3];
            c[2] *= 0.985f;
            c[3] *= 0.985f;
            if (Math.abs(c[2]) < 0.0004f) c[2] = 0;
            if (Math.abs(c[3]) < 0.0004f) c[3] = 0;
            float rad = (int) c[4] == 3 ? 0.026f : 0.021f;
            if (c[0] < rad) { c[0] = rad; c[2] = Math.abs(c[2]) * 0.8f; }
            if (c[0] > 1 - rad) { c[0] = 1 - rad; c[2] = -Math.abs(c[2]) * 0.8f; }
            if (c[1] < rad) { c[1] = rad; c[3] = Math.abs(c[3]) * 0.8f; }
            if (c[1] > 1 - rad) { c[1] = 1 - rad; c[3] = -Math.abs(c[3]) * 0.8f; }
            for (float[] pk : CR_POCKETS) {
                if (Math.hypot(c[0] - pk[0], c[1] - pk[1]) < 0.038f) {
                    dead.add(c);
                    if ((int) c[4] == 3) crStrikerPocketed = true;
                    else crPocketedThisTurn.add(c);
                }
            }
        }
        crCoins.removeAll(dead);
        // collisions (equal-mass elastic, simplified)
        for (int i = 0; i < crCoins.size(); i++) {
            for (int j = i + 1; j < crCoins.size(); j++) {
                float[] a = crCoins.get(i), b = crCoins.get(j);
                float dx = b[0] - a[0], dy = b[1] - a[1];
                float dist = (float) Math.hypot(dx, dy);
                float minD = 0.043f;
                if (dist > 0.0001f && dist < minD) {
                    float nx = dx / dist, ny = dy / dist;
                    float p = (a[2] - b[2]) * nx + (a[3] - b[3]) * ny;
                    a[2] -= p * nx; a[3] -= p * ny;
                    b[2] += p * nx; b[3] += p * ny;
                    float push = (minD - dist) / 2f;
                    a[0] -= nx * push; a[1] -= ny * push;
                    b[0] += nx * push; b[1] += ny * push;
                }
            }
        }
    }

    void crEndTurn() {
        int myType = crP1Turn ? 0 : 1;
        boolean scored = false;
        for (float[] c : crPocketedThisTurn) {
            int t = (int) c[4];
            if (t == myType) { if (crP1Turn) crScore1++; else crScore2++; scored = true; }
            else if (t == 2) { if (crP1Turn) crScore1 += 3; else crScore2 += 3; scored = true; }
            else {
                // opponent's coin stays pocketed but no points; turn passes
            }
        }
        if (crStrikerPocketed) {
            crStrikerPocketed = false;
            scored = false;
            // foul: give back one point if possible
            if (crP1Turn) crScore1 = Math.max(0, crScore1 - 1); else crScore2 = Math.max(0, crScore2 - 1);
            stCr.setText("Foul! Striker pocketed (−1).");
        }
        crPocketedThisTurn.clear();
        // remove old striker, check game end
        float[] old = crStriker();
        if (old != null) crCoins.remove(old);
        boolean coinsLeft = false;
        for (float[] c : crCoins) if ((int) c[4] != 3) coinsLeft = true;
        if (!coinsLeft) {
            crOver = true;
            String w = crScore1 > crScore2 ? "⚪ White wins!" : crScore2 > crScore1 ? "⚫ Black wins!" : "Draw!";
            stCr.setText("🎉 " + w + "  (" + crScore1 + "–" + crScore2 + ")");
            if (crVsAi && crScore1 > crScore2) saveBest("best_carrom", best("best_carrom", 0) + 1, false);
            viewCr.invalidate();
            return;
        }
        if (!scored) crP1Turn = !crP1Turn;
        // place striker for the next player (bottom = P1/white, top = P2/black)
        float[] st = {0.5f, crP1Turn ? 0.85f : 0.15f, 0, 0, 3};
        crCoins.add(st);
        crStatus();
        viewCr.invalidate();
        if (crVsAi && !crP1Turn && !crOver) {
            ui.postDelayed(this::crAiShoot, 700);
        }
    }

    void crEndTurnPass() { crP1Turn = !crP1Turn; crStatus(); }

    void crStatus() {
        stCr.setText("⚪ " + crScore1 + "  —  " + crScore2 + " ⚫   •   "
                + (crP1Turn ? "White" : "Black") + " to play"
                + (crVsAi ? (crP1Turn ? " (you)" : " (AI)") : ""));
    }

    void gameCarrom() {
        TextView[] st = new TextView[1];
        gameScaffold("Carrom Pool", st);
        stCr = st[0];
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        TextView mAi = chip("Vs AI", crVsAi);
        TextView m2p = chip("2 Players", !crVsAi);
        mAi.setOnClickListener(v -> { crVsAi = true; openGame(12); });
        m2p.setOnClickListener(v -> { crVsAi = false; openGame(12); });
        modes.addView(mAi);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(10), 1);
        modes.addView(new View(this), sp);
        modes.addView(m2p);
        root.addView(modes);
        crCoins.clear();
        crScore1 = 0;
        crScore2 = 0;
        crP1Turn = true;
        crOver = false;
        crMoving = false;
        crAiming = false;
        // center formation: queen in the middle, ring of 8 around
        crCoins.add(new float[]{0.5f, 0.5f, 0, 0, 2});
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            crCoins.add(new float[]{0.5f + (float) Math.cos(a) * 0.055f,
                    0.5f + (float) Math.sin(a) * 0.055f, 0, 0, i % 2 == 0 ? 0 : 1});
        }
        crCoins.add(new float[]{0.5f, 0.85f, 0, 0, 3});
        viewCr = new ViewCarrom();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewCr, new LinearLayout.LayoutParams(w, w));
        root.addView(label("You are ⚪ White. Slide the striker on the baseline, then drag "
                + "upward to aim and release to shoot. Queen = 3 pts (no cover rule in this lite version).",
                12, cDim, false));
        crStatus();
    }

    // ------------------------------------------------------------ Ludo (classic board, lite rules)
    // Standard 52-cell track on a 15x15 board. progress: -1 yard, 0..50 track,
    // 51..55 home column, 56 finished. 2 tokens per player (lite).
    static final int[][] LD_PATH = {
            {6, 1}, {6, 2}, {6, 3}, {6, 4}, {6, 5}, {5, 6}, {4, 6}, {3, 6}, {2, 6}, {1, 6}, {0, 6},
            {0, 7}, {0, 8}, {1, 8}, {2, 8}, {3, 8}, {4, 8}, {5, 8}, {6, 9}, {6, 10}, {6, 11}, {6, 12},
            {6, 13}, {6, 14}, {7, 14}, {8, 14}, {8, 13}, {8, 12}, {8, 11}, {8, 10}, {8, 9}, {9, 8},
            {10, 8}, {11, 8}, {12, 8}, {13, 8}, {14, 8}, {14, 7}, {14, 6}, {13, 6}, {12, 6}, {11, 6},
            {10, 6}, {9, 6}, {8, 5}, {8, 4}, {8, 3}, {8, 2}, {8, 1}, {8, 0}, {7, 0}, {6, 0}};
    static final int[][][] LD_HOME = {
            {{7, 1}, {7, 2}, {7, 3}, {7, 4}, {7, 5}},
            {{7, 13}, {7, 12}, {7, 11}, {7, 10}, {7, 9}}};
    static final int[] LD_START = {0, 27};
    static final int[] LD_COLORS = {0xFFE53935, 0xFFFFB300};
    int[][] ldPos;
    boolean ldVsAi = true, ldP1Turn = true, ldDone;
    int ldDice;
    TextView stLd, ldDiceBtn;
    ViewLudo viewLd;

    int ldTrackIdx(int pl, int prog) { return (LD_START[pl] + prog) % 52; }

    boolean ldIsSafe(int trackIdx) { return trackIdx == LD_START[0] || trackIdx == LD_START[1]; }

    final class ViewLudo extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ViewLudo() { super(GamesActivity.this); }
        float cs() { return getWidth() / 15f; }
        void cellRect(Canvas cv, int r, int c, int color) {
            float cs = cs();
            p.setColor(color);
            cv.drawRect(c * cs, r * cs, (c + 1) * cs, (r + 1) * cs, p);
            p.setColor(0xFFB0BEC5);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            cv.drawRect(c * cs, r * cs, (c + 1) * cs, (r + 1) * cs, p);
            p.setStyle(Paint.Style.FILL);
        }
        void yard(Canvas cv, int r0, int c0, int color) {
            float cs = cs();
            p.setColor(color);
            cv.drawRect(c0 * cs, r0 * cs, (c0 + 6) * cs, (r0 + 6) * cs, p);
            p.setColor(0xFFFFFFFF);
            cv.drawRoundRect(new RectF((c0 + 1) * cs, (r0 + 1) * cs, (c0 + 5) * cs, (r0 + 5) * cs),
                    cs * 0.4f, cs * 0.4f, p);
            int[][] slots = {{1, 1}, {1, 3}, {3, 1}, {3, 3}};
            for (int[] sl : slots) {
                p.setColor(color);
                cv.drawCircle((c0 + sl[1] + 0.5f) * cs, (r0 + sl[0] + 0.5f) * cs, cs * 0.42f, p);
                p.setColor(0xFFFFFFFF);
                cv.drawCircle((c0 + sl[1] + 0.5f) * cs, (r0 + sl[0] + 0.5f) * cs, cs * 0.3f, p);
            }
        }
        @Override protected void onDraw(Canvas cv) {
            float cs = cs();
            // track base
            for (int r = 0; r < 15; r++) for (int c = 0; c < 15; c++) {
                boolean inYard = (r < 6 && c < 6) || (r < 6 && c > 8) || (r > 8 && c < 6) || (r > 8 && c > 8);
                boolean inCenter = r >= 6 && r <= 8 && c >= 6 && c <= 8;
                if (!inYard && !inCenter) cellRect(cv, r, c, 0xFFFFFFFF);
            }
            // colored starts + home columns (all four, classic look)
            cellRect(cv, 6, 1, 0xFFE53935);
            cellRect(cv, 1, 8, 0xFF43A047);
            cellRect(cv, 8, 13, 0xFFFFB300);
            cellRect(cv, 13, 6, 0xFF1E88E5);
            for (int i = 1; i <= 5; i++) {
                cellRect(cv, 7, i, 0xFFE53935);
                cellRect(cv, 7, 14 - i, 0xFFFFB300);
                cellRect(cv, i, 7, 0xFF43A047);
                cellRect(cv, 14 - i, 7, 0xFF1E88E5);
            }
            yard(cv, 0, 0, 0xFFE53935);
            yard(cv, 0, 9, 0xFF43A047);
            yard(cv, 9, 9, 0xFFFFB300);
            yard(cv, 9, 0, 0xFF1E88E5);
            // center home: 4 triangles
            float x0 = 6 * cs, y0 = 6 * cs, x1 = 9 * cs, y1 = 9 * cs, cxm = 7.5f * cs, cym = 7.5f * cs;
            int[] triCols = {0xFFE53935, 0xFF43A047, 0xFFFFB300, 0xFF1E88E5};
            float[][][] tris = {
                    {{x0, y0}, {x0, y1}, {cxm, cym}},
                    {{x0, y0}, {x1, y0}, {cxm, cym}},
                    {{x1, y0}, {x1, y1}, {cxm, cym}},
                    {{x0, y1}, {x1, y1}, {cxm, cym}}};
            for (int i = 0; i < 4; i++) {
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(tris[i][0][0], tris[i][0][1]);
                path.lineTo(tris[i][1][0], tris[i][1][1]);
                path.lineTo(tris[i][2][0], tris[i][2][1]);
                path.close();
                p.setColor(triCols[i]);
                cv.drawPath(path, p);
            }
            // tokens
            for (int pl = 0; pl < 2; pl++) {
                for (int tk = 0; tk < 2; tk++) {
                    float[] xy = ldTokenXY(pl, tk);
                    p.setColor(LD_COLORS[pl]);
                    cv.drawCircle(xy[0], xy[1], cs * 0.34f, p);
                    p.setColor(0xFFFFFFFF);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(2));
                    cv.drawCircle(xy[0], xy[1], cs * 0.34f, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setTextSize(cs * 0.34f);
                    p.setTextAlign(Paint.Align.CENTER);
                    cv.drawText(String.valueOf(tk + 1), xy[0], xy[1] + cs * 0.13f, p);
                }
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_DOWN || ldDone) return true;
            if (ldDice <= 0) {
                ldRoll();
                return true;
            }
            if (ldVsAi && !ldP1Turn) return true;
            int pl = ldP1Turn ? 0 : 1;
            for (int tk = 0; tk < 2; tk++) {
                float[] xy = ldTokenXY(pl, tk);
                if (Math.hypot(e.getX() - xy[0], e.getY() - xy[1]) < cs() * 0.62) {
                    ldMoveToken(pl, tk);
                    return true;
                }
            }
            return true;
        }
    }

    float[] ldTokenXY(int pl, int tk) {
        float cs = viewLd.getWidth() / 15f;
        int prog = ldPos[pl][tk];
        if (prog < 0) {
            int r0 = pl == 0 ? 0 : 9, c0 = pl == 0 ? 0 : 9;
            int[][] slots = {{1, 1}, {1, 3}, {3, 1}, {3, 3}};
            int[] sl = slots[tk * 2];
            return new float[]{(c0 + sl[1] + 0.5f) * cs, (r0 + sl[0] + 0.5f) * cs};
        }
        if (prog >= 56) {
            return new float[]{(7.5f + (pl == 0 ? -0.45f : 0.45f)) * cs,
                    (7.5f + (tk == 0 ? -0.45f : 0.45f)) * cs};
        }
        int[] cell = prog <= 50 ? LD_PATH[ldTrackIdx(pl, prog)] : LD_HOME[pl][prog - 51];
        float off = 0;
        // offset when both tokens of a player share a cell
        int other = 1 - tk;
        if (ldPos[pl][other] == prog) off = tk == 0 ? -cs * 0.16f : cs * 0.16f;
        return new float[]{(cell[1] + 0.5f) * cs + off, (cell[0] + 0.5f) * cs};
    }

    boolean ldCanMove(int pl, int tk, int dice) {
        int prog = ldPos[pl][tk];
        if (prog >= 56) return false;
        if (prog < 0) return dice == 6;
        return prog + dice <= 56;
    }

    void ldMoveToken(int pl, int tk) {
        int dice = ldDice;
        if (!ldCanMove(pl, tk, dice)) {
            toast("That token can't move — pick another");
            return;
        }
        int prog = ldPos[pl][tk];
        if (prog < 0) {
            ldPos[pl][tk] = 0;
        } else {
            int next = prog + dice;
            ldPos[pl][tk] = next;
            if (next <= 50) {
                int cell = ldTrackIdx(pl, next);
                if (!ldIsSafe(cell)) {
                    int op = 1 - pl;
                    for (int otk = 0; otk < 2; otk++) {
                        int op2 = ldPos[op][otk];
                        if (op2 >= 0 && op2 <= 50 && ldTrackIdx(op, op2) == cell) {
                            ldPos[op][otk] = -1;
                            toast("Captured! Opponent token back to base");
                        }
                    }
                }
            }
        }
        ldDice = 0;
        viewLd.invalidate();
        if (ldPos[pl][0] >= 56 && ldPos[pl][1] >= 56) {
            ldDone = true;
            stLd.setText("🎉 " + (pl == 0 ? "🔴 Player 1" : "🟡 Player 2") + " wins Ludo!");
            ldDiceBtn.setText("🎉 Game over");
            if (!ldVsAi || pl == 0) saveBest("best_ludo", best("best_ludo", 0) + 1, false);
            return;
        }
        if (dice != 6) ldP1Turn = !ldP1Turn;
        ldStatus();
        ldMaybeAi();
    }

    void ldRoll() {
        if (ldDone || ldDice > 0) return;
        if (ldVsAi && !ldP1Turn) return;
        ldDoRoll();
    }

    void ldDoRoll() {
        ldDice = 1 + rnd.nextInt(6);
        int pl = ldP1Turn ? 0 : 1;
        boolean any = ldCanMove(pl, 0, ldDice) || ldCanMove(pl, 1, ldDice);
        ldDiceBtn.setText("🎲 Rolled " + ldDice + (any ? " — tap a token" : " — no move!"));
        stLd.setText((pl == 0 ? "🔴 Player 1" : "🟡 Player 2") + " rolled " + ldDice);
        if (!any) {
            ldDice = 0;
            ui.postDelayed(() -> {
                if (ldDone) return;
                ldP1Turn = !ldP1Turn;
                ldStatus();
                ldMaybeAi();
            }, 900);
            return;
        }
        if (ldVsAi && !ldP1Turn) {
            ui.postDelayed(() -> {
                int pick = -1;
                // prefer capture, then leaving the yard, then the furthest token
                for (int tk = 0; tk < 2 && pick < 0; tk++) {
                    if (!ldCanMove(1, tk, ldDice)) continue;
                    int prog = ldPos[1][tk];
                    int next = prog < 0 ? 0 : prog + ldDice;
                    if (next <= 50 && prog >= 0) {
                        int cell = ldTrackIdx(1, next);
                        if (!ldIsSafe(cell)) {
                            for (int otk = 0; otk < 2; otk++) {
                                int op2 = ldPos[0][otk];
                                if (op2 >= 0 && op2 <= 50 && ldTrackIdx(0, op2) == cell) pick = tk;
                            }
                        }
                    }
                }
                if (pick < 0) {
                    for (int tk = 0; tk < 2; tk++) {
                        if (ldCanMove(1, tk, ldDice) && ldPos[1][tk] < 0) { pick = tk; break; }
                    }
                }
                if (pick < 0) {
                    int bestP = -2;
                    for (int tk = 0; tk < 2; tk++) {
                        if (ldCanMove(1, tk, ldDice) && ldPos[1][tk] > bestP) {
                            bestP = ldPos[1][tk];
                            pick = tk;
                        }
                    }
                }
                if (pick >= 0) ldMoveToken(1, pick);
            }, 650);
        }
    }

    void ldMaybeAi() {
        if (ldVsAi && !ldP1Turn && !ldDone && ldDice <= 0) {
            ui.postDelayed(this::ldDoRoll, 650);
        }
    }

    void ldStatus() {
        stLd.setText((ldP1Turn ? "🔴 Player 1" : "🟡 Player 2") + "'s turn — tap the board or the dice");
        ldDiceBtn.setText("🎲  Roll dice");
    }

    void gameLudo() {
        TextView[] st = new TextView[1];
        gameScaffold("Ludo", st);
        stLd = st[0];
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        TextView mAi = chip("Vs AI", ldVsAi);
        TextView m2p = chip("2 Players", !ldVsAi);
        mAi.setOnClickListener(v -> { ldVsAi = true; openGame(13); });
        m2p.setOnClickListener(v -> { ldVsAi = false; openGame(13); });
        modes.addView(mAi);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(10), 1);
        modes.addView(new View(this), sp);
        modes.addView(m2p);
        root.addView(modes);
        ldPos = new int[][]{{-1, -1}, {-1, -1}};
        ldP1Turn = true;
        ldDone = false;
        ldDice = 0;
        viewLd = new ViewLudo();
        int w = getResources().getDisplayMetrics().widthPixels - dp(32);
        root.addView(viewLd, new LinearLayout.LayoutParams(w, w));
        ldDiceBtn = chip("🎲  Roll dice", true);
        ldDiceBtn.setOnClickListener(v -> ldRoll());
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.gravity = Gravity.CENTER_HORIZONTAL;
        nl.topMargin = dp(12);
        root.addView(ldDiceBtn, nl);
        root.addView(label("Classic board, lite rules: 2 tokens each. Roll 6 to leave your "
                + "yard, tap a token to move it. Race around the board and up your colored "
                + "home column — land on an opponent (outside the safe start cells) to send them back.",
                12, cDim, false));
        ldStatus();
    }
}
