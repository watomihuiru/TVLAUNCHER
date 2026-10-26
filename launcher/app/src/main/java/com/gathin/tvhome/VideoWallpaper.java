package com.gathin.tvhome;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;

/**
 * A theme's wallpaper.mp4 shown behind Home on a SurfaceView. TVs draw their UI at 1080p and stretch it to the
 * panel, but a hardware-decoded SurfaceView goes to the video plane at the panel's own resolution, so a still
 * encoded as a short 4K clip shows in 4K under the 1080p tiles. The clip is held on its first frame; the decoder
 * is released whenever Home leaves the screen, so apps always get it. The JPEG wallpaper stays under it as the
 * picture shown until the video's first frame is up.
 */
final class VideoWallpaper implements SurfaceHolder.Callback {
    static final String FILE_NAME = "wallpaper.mp4";
    private static final String TAG = "TvHome.VideoWallpaper";

    private final FrameLayout root;
    private final File file;
    private final WallpaperPlayer picture;
    private final SurfaceView surface;
    /** wallpaper_dim over the video, which cannot be painted into the frames like the picture's. */
    private final View dim;
    private MediaPlayer player;
    private boolean active;
    private boolean surfaceReady;

    private VideoWallpaper(Context context, FrameLayout root, File file, WallpaperPlayer picture, int dimPercent) {
        this.root = root;
        this.file = file;
        this.picture = picture;
        surface = new SurfaceView(context);
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        surface.getHolder().addCallback(this);
        root.addView(surface, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (dimPercent > 0) {
            dim = new View(context);
            dim.setBackgroundColor((dimPercent * 255 / 100) << 24);
            dim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            dim.setVisibility(View.GONE);
            root.addView(dim, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            dim = null;
        }
    }

    /** The video wallpaper for this theme, or null: no wallpaper.mp4, the wallpaper off, or a rotating collection. */
    static VideoWallpaper create(Context context, FrameLayout root, Config cfg, WallpaperPlayer picture) {
        if (!cfg.wallpaperEnabled || cfg.wallpaperRotationMinutes > 0) return null;
        File dir = Config.dir(context);
        if (dir == null) return null;
        File file = new File(dir, FILE_NAME);
        if (!file.isFile() || file.length() == 0) return null;
        return new VideoWallpaper(context, root, file, picture, cfg.wallpaperDim);
    }

    void start() {
        active = true;
        open();
    }

    /** Home left the screen: put the picture back and give the decoder up at once. */
    void stop() {
        active = false;
        release();
    }

    void close() {
        stop();
        surface.getHolder().removeCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        open();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        release();
    }

    private void open() {
        if (!active || !surfaceReady || player != null) return;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(file.getPath());
            mp.setSurface(surface.getHolder().getSurface());
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (p == player) p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (p == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    // A still: hold the first frame so the decoder idles.
                    if (p.getDuration() > 1000) p.pause();
                    // One frame later, so the picture never disappears before the video is on screen.
                    root.postDelayed(() -> {
                        if (p == player) reveal(true);
                    }, 120);
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "Playback failed: " + what + "/" + extra);
                if (p == player) release();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception failure) {
            Log.w(TAG, "Cannot open " + file.getName(), failure);
            release();
        }
    }

    private void release() {
        reveal(false);
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    /** True: the video shows through (picture hidden). False: the picture covers the surface again. */
    private void reveal(boolean video) {
        picture.setCovered(video);
        if (dim != null) dim.setVisibility(video ? View.VISIBLE : View.GONE);
    }
}
