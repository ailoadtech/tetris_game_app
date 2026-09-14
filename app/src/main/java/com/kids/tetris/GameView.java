package com.kids.tetris;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * Kid-friendly Tetris board.
 * - 8 columns wide (small board, easy to aim).
 * - Falling speed increases with progress (cleared lines).
 * - Big, bright colours for each piece type on a white / light-green theme.
 * - Tap the board to start or restart; tap elsewhere (outside the board) to pause.
 * - Classic-style sound effects via SoundPool.
 */
public class GameView extends View {

    public static final int COLS = 8;
    public static final int ROWS = 14; // short board so a line fills quickly -> rewarding

    private static final long DROP_INTERVAL_START = 1100L; // ms per step, kid pace
    private static final long DROP_INTERVAL_MIN = 350L;
    private static final float FAST_DROP_FACTOR = 0.25f;   // "down" button: 4x faster

    // Theme: white and light green
    private static final int BOARD_BG = Color.parseColor("#FFFFFFFF");      // white cells bg
    private static final int GRID_LINE = Color.parseColor("#FFA5D6A7");     // light green grid
    private static final int GAME_OVER_RED = Color.parseColor("#FFD32F2F"); // red game over text

    // Piece shapes as 4x4 bit patterns in 4 rotations. I, O, T, S, Z, J, L
    private static final int[][][] SHAPES = new int[7][4][4];

    // Bright, distinct colours per piece type (kid palette).
    private static final int[] PIECE_COLORS = {
            Color.parseColor("#00BCD4"), // I cyan
            Color.parseColor("#FBC02D"), // O yellow
            Color.parseColor("#9C27B0"), // T purple
            Color.parseColor("#43A047"), // S green
            Color.parseColor("#E53935"), // Z red
            Color.parseColor("#1E88E5"), // J blue
            Color.parseColor("#FB8C00"), // L orange
    };

    static {
        buildShapes();
    }

    private static void buildShapes() {
        addShape(0, new int[][]{{1,0},{1,1},{1,2},{1,3}});              // I
        addShape(1, new int[][]{{1,1},{1,2},{2,1},{2,2}});              // O
        addShape(2, new int[][]{{1,1},{2,0},{2,1},{2,2}});              // T
        addShape(3, new int[][]{{1,1},{1,2},{2,0},{2,1}});              // S
        addShape(4, new int[][]{{1,0},{1,1},{2,1},{2,2}});              // Z
        addShape(5, new int[][]{{1,0},{2,0},{2,1},{2,2}});              // J
        addShape(6, new int[][]{{1,2},{2,0},{2,1},{2,2}});              // L
    }

    private static void addShape(int type, int[][] cells) {
        boolean[][] grid = new boolean[4][4];
        for (int[] c : cells) grid[c[0]][c[1]] = true;
        for (int rot = 0; rot < 4; rot++) {
            boolean[][] r = rotateGrid(grid, rot);
            for (int row = 0; row < 4; row++) {
                int bits = 0;
                for (int col = 0; col < 4; col++) if (r[row][col]) bits |= (1 << col);
                SHAPES[type][rot][row] = bits;
            }
        }
    }

    /** Rotate a 4x4 grid clockwise n times. */
    private static boolean[][] rotateGrid(boolean[][] g, int times) {
        boolean[][] cur = new boolean[4][4];
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) cur[r][c] = g[r][c];
        for (int t = 0; t < times % 4; t++) {
            boolean[][] nx = new boolean[4][4];
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    nx[c][3 - r] = cur[r][c];
            cur = nx;
        }
        return cur;
    }

    // ----- game state -----
    private final int[][] board = new int[ROWS][COLS]; // 0 empty, else type+1
    private int curType, curRot, curRow, curCol;
    private boolean gameOver = false;
    private boolean started = false;
    private boolean paused = false;
    private boolean fastDrop = false;
    private int completedLines = 0;
    private final Random random = new Random();

    private Paint cellPaint, borderPaint, textPaint, overlayPaint;
    private float cellSize, originX, originY;

    // ----- sounds -----
    private SoundPool soundPool;
    private int sndLineClear, sndGameOver, sndHardDrop, sndTick;

    public interface LineListener { void onLinesChanged(int total); }
    private LineListener lineListener;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable dropTask = new Runnable() {
        @Override public void run() {
            if (!started || gameOver || paused) return;
            stepDown();
            invalidate();
            if (started && !gameOver && !paused) handler.postDelayed(this, currentInterval());
        }
    };

    public GameView(Context ctx) { this(ctx, null); }
    public GameView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(GRID_LINE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);
        overlayPaint = new Paint();
        overlayPaint.setColor(Color.parseColor("#88FFFFFF"));

        setupSounds();
    }

    private void setupSounds() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(attrs)
                    .build();
            sndLineClear = soundPool.load(getContext(), R.raw.lineclear, 1);
            sndGameOver  = soundPool.load(getContext(), R.raw.gameover, 1);
            sndHardDrop  = soundPool.load(getContext(), R.raw.harddrop, 1);
            sndTick      = soundPool.load(getContext(), R.raw.tick, 1);
        } catch (Exception ignored) {
            soundPool = null; // audio is optional; game must keep working
        }
    }

    private void play(int soundId) {
        if (soundPool != null && soundId != 0) soundPool.play(soundId, 0.8f, 0.8f, 1, 0, 1f);
    }

    public void setLineListener(LineListener l) { this.lineListener = l; }

    /** Speed grows with progress; while the down-button is held it is much faster. */
    private long currentInterval() {
        int level = completedLines / 4; // every 4 lines -> faster
        long interval = DROP_INTERVAL_START - level * 100L;
        interval = Math.max(DROP_INTERVAL_MIN, interval);
        if (fastDrop) interval = Math.max(40L, (long) (interval * FAST_DROP_FACTOR));
        return interval;
    }

    // ----- geometry -----
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cellSize = Math.min((float) w / COLS, (float) h / ROWS);
        originX = (w - cellSize * COLS) / 2f;
        originY = (h - cellSize * ROWS) / 2f;
    }

    // ----- controls -----
    public void moveLeft()  { if (canControl()) { tryMove(curRow, curCol - 1, curRot); play(sndTick); } }
    public void moveRight() { if (canControl()) { tryMove(curRow, curCol + 1, curRot); play(sndTick); } }

    public void rotateClockwise() {
        if (!canControl()) return;
        int nr = (curRot + 1) % 4;
        // try normal, then simple wall kicks left/right
        if (fits(curRow, curCol, nr)) curRot = nr;
        else if (fits(curRow, curCol - 1, nr)) { curCol -= 1; curRot = nr; }
        else if (fits(curRow, curCol + 1, nr)) { curCol += 1; curRot = nr; }
        else if (fits(curRow, curCol - 2, nr)) { curCol -= 2; curRot = nr; }
        else if (fits(curRow, curCol + 2, nr)) { curCol += 2; curRot = nr; }
        play(sndTick);
        invalidate();
    }

    private boolean canControl() { return started && !gameOver && !paused; }

    /** Hold the DOWN button to make the piece fall faster. */
    public void setFastDrop(boolean on) {
        if (fastDrop == on) return;
        fastDrop = on;
        if (on) play(sndHardDrop);
        if (canControl()) {
            handler.removeCallbacks(dropTask);
            handler.postDelayed(dropTask, currentInterval());
        }
    }

    private boolean tryMove(int nr, int nc, int rot) {
        if (fits(nr, nc, rot)) { curRow = nr; curCol = nc; invalidate(); return true; }
        return false;
    }

    private boolean fits(int row, int col, int rot) {
        int[][] cells = shapeCells(curType, rot);
        for (int[] c : cells) {
            int r = row + c[0], cc = col + c[1];
            if (cc < 0 || cc >= COLS || r >= ROWS) return false;
            if (r >= 0 && board[r][cc] != 0) return false;
        }
        return true;
    }

    /** Returns list of [row,col] filled cells for a type/rotation. */
    private static int[][] shapeCells(int type, int rot) {
        int[][] out = new int[4][2];
        int i = 0;
        int[] rows = SHAPES[type][rot];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if ((rows[r] & (1 << c)) != 0) {
                    out[i][0] = r; out[i][1] = c; i++;
                }
            }
        }
        return out;
    }

    private void stepDown() {
        if (!tryMove(curRow + 1, curCol, curRot)) {
            lockPiece();
        }
    }

    private void lockPiece() {
        for (int[] c : shapeCells(curType, curRot)) {
            int r = curRow + c[0], cc = curCol + c[1];
            if (r < 0) { endGame(); return; }
            board[r][cc] = curType + 1;
        }
        clearLines();
        spawnPiece();
    }

    private void clearLines() {
        int cleared = 0;
        for (int r = ROWS - 1; r >= 0; r--) {
            boolean full = true;
            for (int c = 0; c < COLS; c++) if (board[r][c] == 0) { full = false; break; }
            if (full) {
                cleared++;
                for (int rr = r; rr > 0; rr--)
                    System.arraycopy(board[rr - 1], 0, board[rr], 0, COLS);
                for (int c = 0; c < COLS; c++) board[0][c] = 0;
                r++; // re-check same row index after shift
            }
        }
        if (cleared > 0) {
            completedLines += cleared;
            play(sndLineClear); // classic ascending arpeggio
            if (lineListener != null) lineListener.onLinesChanged(completedLines);
        }
    }

    private void spawnPiece() {
        curType = random.nextInt(7);
        curRot = 0;
        curRow = -1;
        curCol = (COLS - 4) / 2; // centre-ish for a 4-wide box
        if (!fits(curRow, curCol, curRot)) endGame();
    }

    private void endGame() {
        gameOver = true;
        started = false;
        fastDrop = false;
        handler.removeCallbacks(dropTask);
        play(sndGameOver); // descending classic jingle
    }

    public void startGame() {
        for (int[] row : board) java.util.Arrays.fill(row, 0);
        completedLines = 0;
        if (lineListener != null) lineListener.onLinesChanged(0);
        gameOver = false;
        paused = false;
        started = true;
        spawnPiece();
        invalidate();
        handler.removeCallbacks(dropTask);
        handler.postDelayed(dropTask, currentInterval());
    }

    /** Pause from the app lifecycle (activity backgrounded): silent pause. */
    public void pauseGame() {
        handler.removeCallbacks(dropTask);
    }

    public void resumeGame() {
        if (started && !gameOver && !paused) {
            handler.removeCallbacks(dropTask);
            handler.postDelayed(dropTask, currentInterval());
        }
    }

    /** Toggle the in-game pause overlay (tap outside the board). */
    public void togglePause() {
        if (!started || gameOver) return;
        paused = !paused;
        if (paused) {
            handler.removeCallbacks(dropTask);
        } else {
            handler.removeCallbacks(dropTask);
            handler.postDelayed(dropTask, currentInterval());
        }
        invalidate();
    }

    public boolean isPaused() { return paused; }
    public boolean isGameOver() { return gameOver; }
    public boolean isStarted() { return started; }

    public void releaseSounds() {
        if (soundPool != null) { soundPool.release(); soundPool = null; }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (!started) { startGame(); return true; }
            // Tapping somewhere else (outside the playing field) pauses the game.
            float x = event.getX(), y = event.getY();
            boolean insideBoard = x >= originX && x <= originX + cellSize * COLS
                    && y >= originY && y <= originY + cellSize * ROWS;
            if (!insideBoard) togglePause();
            return true;
        }
        return super.onTouchEvent(event);
    }

    /** True if the touch point falls inside the board rectangle. */
    public boolean isInsideBoard(float x, float y) {
        return cellSize > 0
                && x >= originX && x <= originX + cellSize * COLS
                && y >= originY && y <= originY + cellSize * ROWS;
    }

    // ----- drawing -----
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // board background: white with light green grid
        cellPaint.setColor(BOARD_BG);
        canvas.drawRect(originX, originY,
                originX + cellSize * COLS, originY + cellSize * ROWS, cellPaint);

        // grid lines (light green)
        for (int r = 0; r <= ROWS; r++)
            canvas.drawLine(originX, originY + r * cellSize,
                    originX + cellSize * COLS, originY + r * cellSize, borderPaint);
        for (int c = 0; c <= COLS; c++)
            canvas.drawLine(originX + c * cellSize, originY,
                    originX + c * cellSize, originY + cellSize * ROWS, borderPaint);

        // locked cells
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                if (board[r][c] != 0) drawCell(canvas, r, c, PIECE_COLORS[board[r][c] - 1]);

        // current piece
        if (started && !gameOver) {
            for (int[] cell : shapeCells(curType, curRot)) {
                int r = curRow + cell[0], cc = curCol + cell[1];
                if (r >= 0) drawCell(canvas, r, cc, PIECE_COLORS[curType]);
            }
        }

        // overlays
        if (paused) {
            drawCenteredText(canvas, "PAUSED\nTap to continue", Color.parseColor("#FF2E7D32"));
        } else if (!started) {
            if (gameOver)
                drawCenteredText(canvas, "Game Over!\nTap to play again", GAME_OVER_RED);
            else
                drawCenteredText(canvas, "Tap to start!", Color.parseColor("#FF2E7D32"));
        }
    }

    private void drawCell(Canvas canvas, int row, int col, int color) {
        float l = originX + col * cellSize;
        float t = originY + row * cellSize;
        RectF rect = new RectF(l + 2, t + 2, l + cellSize - 2, t + cellSize - 2);
        cellPaint.setColor(color);
        canvas.drawRoundRect(rect, cellSize * 0.18f, cellSize * 0.18f, cellPaint);
        // light highlight for a chunky toy-block look
        cellPaint.setColor(Color.argb(70, 255, 255, 255));
        canvas.drawRoundRect(new RectF(l + 4, t + 4, l + cellSize - 2, t + cellSize * 0.45f),
                cellSize * 0.18f, cellSize * 0.18f, cellPaint);
    }

    private void drawCenteredText(Canvas canvas, String text, int color) {
        // dim the board slightly behind the message
        canvas.drawRect(originX, originY,
                originX + cellSize * COLS, originY + cellSize * ROWS, overlayPaint);
        textPaint.setColor(color);
        textPaint.setTextSize(cellSize * 0.9f);
        float cx = originX + cellSize * COLS / 2f;
        float cy = originY + cellSize * ROWS / 2f;
        String[] lines = text.split("\n");
        float lh = textPaint.getTextSize() * 1.3f;
        float y = cy - (lines.length - 1) * lh / 2f;
        for (String ln : lines) {
            canvas.drawText(ln, cx, y, textPaint);
            y += lh;
        }
    }
}
