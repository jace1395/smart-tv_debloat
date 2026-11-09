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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

public class CameraQuadActivity extends Activity {

    public static final String[][] CAMS = {
            {"Camera 1 (192.168.1.9) - Staircase", "rtsp://olavoh18:pass@127.0.0.1:8554/stream2", "rtsp://olavoh18:pass@127.0.0.1:8554/stream1", "rtsp://olavoh18:Olavo%40123@192.168.1.9:554/stream1"},
            {"Camera 2 (192.168.1.10) - Balcony", "rtsp://olavoh18:pass@127.0.0.1:8555/stream2", "rtsp://olavoh18:pass@127.0.0.1:8555/stream1", "rtsp://olavoh18:Olavo%40123@192.168.1.10:554/stream1"},
            {"Camera 3 (192.168.1.11) - Garage", "rtsp://olavoh:pass@127.0.0.1:8556/stream2", "rtsp://olavoh:pass@127.0.0.1:8556/stream1", "rtsp://olavoh:Olavo%40123@192.168.1.11:554/stream1"},
            {"Camera 4 (192.168.1.12) - Big Window", "rtsp://olavoh:pass@127.0.0.1:8557/stream2", "rtsp://olavoh:pass@127.0.0.1:8557/stream1", "rtsp://olavoh:Olavo%40123@192.168.1.12:554/stream1"}
    };

    private final ExoPlayer[] players = new ExoPlayer[4];
    private final FrameLayout[] containers = new FrameLayout[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        RtspAuthProxy.startAll();

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

            cell.setOnClickListener(v -> openFullscreen(index));

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

            // Initialize player with forced TCP for flawless Android TV RTSP decoding
            try {
                ExoPlayer player = new ExoPlayer.Builder(this).build();
                player.setVolume(0f); // Mute quad view to avoid sound clash

                final TextView camLabel = label;
                final int camIndex = i;
                player.addListener(new Player.Listener() {
                    @Override
                    public void onRenderedFirstFrame() {
                        if (camLabel != null) {
                            camLabel.animate()
                                    .alpha(0f)
                                    .setDuration(400)
                                    .withEndAction(() -> camLabel.setVisibility(View.GONE))
                                    .start();
                        }
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        if (camLabel != null) {
                            camLabel.setText(CAMS[camIndex][0] + " \u2022 Reconnecting...");
                            camLabel.setVisibility(View.VISIBLE);
                            camLabel.setAlpha(1f);
                        }
                    }
                });

                MediaItem mediaItem = MediaItem.fromUri(CAMS[i][1]); // Stream2 sub-stream
                RtspMediaSource mediaSource = new RtspMediaSource.Factory()
                        .setForceUseRtpTcp(true)
                        .createMediaSource(mediaItem);
                player.setMediaSource(mediaSource);
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

        Toast.makeText(this, "Quad View: D-pad to select \u00B7 OK for Fullscreen with Arrow Keys", Toast.LENGTH_LONG).show();

        // Focus first cell
        if (containers[0] != null) {
            containers[0].requestFocus();
        }
    }

    private void openFullscreen(int index) {
        Intent intent = new Intent(this, CameraFullscreenActivity.class);
        intent.putExtra(CameraFullscreenActivity.EXTRA_CAMERA_INDEX, index);
        startActivity(intent);
    }

    public static void openInVlc(android.content.Context ctx, int index) {
        try {
            String directHd = CAMS[index][3];
            Intent vlcIntent = new Intent(Intent.ACTION_VIEW);
            vlcIntent.setPackage("org.videolan.vlc");
            vlcIntent.setDataAndType(Uri.parse(directHd), "video/*");
            vlcIntent.putExtra("title", CAMS[index][0] + " (1080p HD)");
            vlcIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(vlcIntent);
        } catch (Exception e) {
            Toast.makeText(ctx, "Could not open VLC: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (ExoPlayer p : players) {
            if (p != null) p.stop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (int i = 0; i < players.length; i++) {
            ExoPlayer p = players[i];
            if (p != null) {
                try {
                    MediaItem mediaItem = MediaItem.fromUri(CAMS[i][1]);
                    RtspMediaSource mediaSource = new RtspMediaSource.Factory()
                            .setForceUseRtpTcp(true)
                            .createMediaSource(mediaItem);
                    p.setMediaSource(mediaSource);
                    p.prepare();
                    p.play();
                } catch (Exception ignored) {}
            }
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

