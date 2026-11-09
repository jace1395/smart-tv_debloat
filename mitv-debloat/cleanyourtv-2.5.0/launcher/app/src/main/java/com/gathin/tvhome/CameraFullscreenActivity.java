package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

public class CameraFullscreenActivity extends Activity {

    public static final String EXTRA_CAMERA_INDEX = "camera_index";

    public static final String[] SHORT_NAMES = {
            "Staircase (.9)",
            "Balcony (.10)",
            "Garage (.11)",
            "Big Window (.12)"
    };

    private int currentIndex = 0;
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout hudContainer;
    private TextView txtHudTitle;
    private TextView txtHudSub;
    private final Handler hudHandler = new Handler(Looper.getMainLooper());
    private boolean isHudVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        RtspAuthProxy.ensureRunning();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        currentIndex = getIntent().getIntExtra(EXTRA_CAMERA_INDEX, 0);
        if (currentIndex < 0 || currentIndex >= CameraQuadActivity.CAMS.length) {
            currentIndex = 0;
        }

        float density = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top HUD overlay (pill banner)
        hudContainer = new LinearLayout(this);
        hudContainer.setOrientation(LinearLayout.VERTICAL);
        hudContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = (int) (22 * density);
        int padV = (int) (10 * density);
        hudContainer.setPadding(padH, padV, padH, padV);

        GradientDrawable hudBg = new GradientDrawable();
        hudBg.setColor(Color.parseColor("#DD121212"));
        hudBg.setCornerRadius(14 * density);
        hudBg.setStroke((int) (1.5f * density), Color.parseColor("#40FFFFFF"));
        hudContainer.setBackground(hudBg);

        txtHudTitle = new TextView(this);
        txtHudTitle.setTextColor(Color.WHITE);
        txtHudTitle.setTextSize(16f);
        txtHudTitle.setGravity(Gravity.CENTER);

        txtHudSub = new TextView(this);
        txtHudSub.setTextColor(Color.parseColor("#B0FFFFFF"));
        txtHudSub.setTextSize(12f);
        txtHudSub.setGravity(Gravity.CENTER);
        txtHudSub.setPadding(0, (int) (4 * density), 0, 0);

        hudContainer.addView(txtHudTitle);
        hudContainer.addView(txtHudSub);

        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hudLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hudLp.topMargin = (int) (18 * density);
        root.addView(hudContainer, hudLp);

        setContentView(root);

        playCamera(currentIndex);
    }

    private void updateHud(int index) {
        int prevIndex = (index - 1 + CameraQuadActivity.CAMS.length) % CameraQuadActivity.CAMS.length;
        int nextIndex = (index + 1) % CameraQuadActivity.CAMS.length;

        txtHudTitle.setText(CameraQuadActivity.CAMS[index][0] + "  \u2022  LIVE");
        txtHudSub.setText("\u25C0 [Left] " + SHORT_NAMES[prevIndex] + "   \u2022   [Right] " + SHORT_NAMES[nextIndex] + " \u25B6   \u2022   [OK/Down] Quad");

        showHud();
    }

    private void showHud() {
        hudHandler.removeCallbacksAndMessages(null);
        hudContainer.animate().cancel();
        hudContainer.setVisibility(View.VISIBLE);
        hudContainer.setAlpha(1.0f);
        isHudVisible = true;
    }

    private void scheduleHudFade() {
        hudHandler.removeCallbacksAndMessages(null);
        hudHandler.postDelayed(() -> {
            hudContainer.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .withEndAction(() -> {
                        hudContainer.setVisibility(View.GONE);
                        isHudVisible = false;
                    })
                    .start();
        }, 2200);
    }

    private void playCamera(int index) {
        RtspAuthProxy.ensureRunning();
        currentIndex = index;
        updateHud(index);

        if (player != null) {
            player.stop();
            player.clearMediaItems();
        } else {
            player = new ExoPlayer.Builder(this).build();
            player.addListener(new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    scheduleHudFade();
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    showHud();
                    txtHudTitle.setText(CameraQuadActivity.CAMS[currentIndex][0] + " \u2022 Reconnecting...");
                    tryFallback(currentIndex);
                }
            });
            playerView.setPlayer(player);
        }

        try {
            String url = CameraQuadActivity.CAMS[index][1];
            MediaItem mediaItem = MediaItem.fromUri(url);
            RtspMediaSource mediaSource = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(mediaItem);
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();
        } catch (Exception e) {
            tryFallback(index);
        }
    }

    private void tryFallback(int index) {
        try {
            String subUrl = CameraQuadActivity.CAMS[index][2]; // Try stream1 if stream2 errored
            MediaItem mediaItem = MediaItem.fromUri(subUrl);
            RtspMediaSource mediaSource = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(mediaItem);
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();
        } catch (Exception ignored) {}
    }

    private void openInVlc(int index) {
        try {
            String directHd = CameraQuadActivity.CAMS[index][3];
            Intent vlcIntent = new Intent(Intent.ACTION_VIEW);
            vlcIntent.setPackage("org.videolan.vlc");
            vlcIntent.setDataAndType(Uri.parse(directHd), "video/*");
            vlcIntent.putExtra("title", CameraQuadActivity.CAMS[index][0] + " (1080p HD)");
            vlcIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(vlcIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open VLC: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showActionDialog() {
        final String[] actions = new String[]{
                "\u25B6 Switch to Quad View (All 4 Cameras)",
                "\u25B6 Open this Camera in VLC Player",
                "Dismiss"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(CameraQuadActivity.CAMS[currentIndex][0])
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        Intent quad = new Intent(this, CameraQuadActivity.class);
                        startActivity(quad);
                        finish();
                    } else if (which == 1) {
                        openInVlc(currentIndex);
                    }
                })
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                playCamera((currentIndex + 1) % CameraQuadActivity.CAMS.length);
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                playCamera((currentIndex - 1 + CameraQuadActivity.CAMS.length) % CameraQuadActivity.CAMS.length);
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
                finish();
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!isHudVisible) {
                    showHud();
                    scheduleHudFade();
                } else {
                    showActionDialog();
                }
                return true;

            case KeyEvent.KEYCODE_MENU:
                showActionDialog();
                return true;

            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hudHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }
}
