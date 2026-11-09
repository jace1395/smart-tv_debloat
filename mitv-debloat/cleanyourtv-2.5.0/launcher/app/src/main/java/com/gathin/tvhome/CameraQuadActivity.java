package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class CameraQuadActivity extends Activity {

    public static final String[][] CAMS = {
            {"Camera 1 (192.168.1.9)", "rtsp://olavoh18:Olavo%40123@192.168.1.9:554/stream2", "rtsp://olavoh18:Olavo%40123@192.168.1.9:554/stream1"},
            {"Camera 2 (192.168.1.10)", "rtsp://olavoh18:Olavo%40123@192.168.1.10:554/stream2", "rtsp://olavoh18:Olavo%40123@192.168.1.10:554/stream1"},
            {"Camera 3 (192.168.1.11)", "rtsp://olavoh:Olavo%40123@192.168.1.11:554/stream2", "rtsp://olavoh:Olavo%40123@192.168.1.11:554/stream1"},
            {"Camera 4 (192.168.1.12)", "rtsp://olavoh:Olavo%40123@192.168.1.12:554/stream2", "rtsp://olavoh:Olavo%40123@192.168.1.12:554/stream1"}
    };

    private final ExoPlayer[] players = new ExoPlayer[4];
    private final FrameLayout[] containers = new FrameLayout[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (6 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout rowTop = new LinearLayout(this);
        rowTop.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowTopLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rowTopLp.bottomMargin = pad / 2;
        rowTop.setLayoutParams(rowTopLp);

        LinearLayout rowBottom = new LinearLayout(this);
        rowBottom.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowBottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rowBottomLp.topMargin = pad / 2;
        rowBottom.setLayoutParams(rowBottomLp);

        for (int i = 0; i < 4; i++) {
            final int index = i;
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % 2 == 0) cellLp.rightMargin = pad / 2;
            else cellLp.leftMargin = pad / 2;
            cell.setLayoutParams(cellLp);
            cell.setFocusable(true);
            cell.setClickable(true);

            // Default border
            GradientDrawable normalBg = new GradientDrawable();
            normalBg.setColor(Color.parseColor("#151515"));
            normalBg.setStroke((int) (2 * density), Color.parseColor("#33FFFFFF"));
            normalBg.setCornerRadius(8 * density);
            cell.setBackground(normalBg);

            // Focused border
            GradientDrawable focusedBg = new GradientDrawable();
            focusedBg.setColor(Color.parseColor("#202020"));
            focusedBg.setStroke((int) (3 * density), Color.parseColor("#E10600")); // Kerb red accent
            focusedBg.setCornerRadius(8 * density);

            cell.setOnFocusChangeListener((v, hasFocus) -> {
                v.setBackground(hasFocus ? focusedBg : normalBg);
                v.setScaleX(hasFocus ? 1.02f : 1.0f);
                v.setScaleY(hasFocus ? 1.02f : 1.0f);
            });

            cell.setOnClickListener(v -> openInVlc(index));

            PlayerView pv = new PlayerView(this);
            pv.setUseController(false);
            pv.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            cell.addView(pv);

            TextView label = new TextView(this);
            label.setText(CAMS[i][0] + "  \u2022  LIVE");
            label.setTextColor(Color.WHITE);
            label.setTextSize(12f);
            label.setPadding((int) (10 * density), (int) (6 * density), (int) (10 * density), (int) (6 * density));
            label.setBackgroundColor(Color.parseColor("#80000000"));
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.gravity = Gravity.TOP | Gravity.START;
            cell.addView(label, labelLp);

            containers[i] = cell;

            if (i < 2) rowTop.addView(cell);
            else rowBottom.addView(cell);

            // Initialize player
            try {
                ExoPlayer player = new ExoPlayer.Builder(this).build();
                player.setVolume(0f); // Mute quad view to avoid sound clash
                MediaItem mediaItem = MediaItem.fromUri(CAMS[i][1]); // Stream2 sub-stream
                player.setMediaItem(mediaItem);
                player.prepare();
                player.play();
                pv.setPlayer(player);
                players[i] = player;
            } catch (Exception e) {
                // If RTSP init fails, fallback gracefully
            }
        }

        root.addView(rowTop);
        root.addView(rowBottom);
        setContentView(root);

        Toast.makeText(this, "Quad View: D-pad to select \u00B7 OK for 1080p Fullscreen in VLC", Toast.LENGTH_LONG).show();

        // Focus first cell
        if (containers[0] != null) {
            containers[0].requestFocus();
        }
    }

    private void openInVlc(int index) {
        try {
            String hdUrl = CAMS[index][2];
            Intent vlcIntent = new Intent(Intent.ACTION_VIEW);
            vlcIntent.setPackage("org.videolan.vlc");
            vlcIntent.setDataAndType(Uri.parse(hdUrl), "video/*");
            vlcIntent.putExtra("title", CAMS[index][0] + " (1080p HD)");
            vlcIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(vlcIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open VLC: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (ExoPlayer p : players) {
            if (p != null) p.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (ExoPlayer p : players) {
            if (p != null) p.play();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (int i = 0; i < players.length; i++) {
            if (players[i] != null) {
                players[i].stop();
                players[i].release();
                players[i] = null;
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}

