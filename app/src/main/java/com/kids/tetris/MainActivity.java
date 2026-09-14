package com.kids.tetris;

import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private GameView gameView;
    private TextView lineCounter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        gameView = findViewById(R.id.gameView);
        lineCounter = findViewById(R.id.lineCounter);

        gameView.setLineListener(total ->
                lineCounter.setText(getString(R.string.lines_label, total)));

        ImageButton btnRotate = findViewById(R.id.btnRotate);
        ImageButton btnDown = findViewById(R.id.btnDown);
        ImageButton btnLeft = findViewById(R.id.btnLeft);
        ImageButton btnRight = findViewById(R.id.btnRight);

        btnRotate.setOnClickListener(v -> gameView.rotateClockwise());
        btnLeft.setOnClickListener(v -> gameView.moveLeft());
        btnRight.setOnClickListener(v -> gameView.moveRight());

        // Hold the DOWN button to make the piece fall faster.
        btnDown.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    gameView.setFastDrop(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    gameView.setFastDrop(false);
                    return true;
            }
            return false;
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        gameView.pauseGame();
    }

    @Override
    protected void onResume() {
        super.onResume();
        gameView.resumeGame();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        gameView.releaseSounds();
    }
}
