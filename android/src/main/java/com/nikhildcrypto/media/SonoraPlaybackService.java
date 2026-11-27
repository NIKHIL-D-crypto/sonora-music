package com.nikhildcrypto.sonora.media;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

@UnstableApi
public class SonoraPlaybackService extends MediaSessionService {
    public static final String ACTION_STATE = "com.nikhildcrypto.sonora.PLAYBACK_STATE";
    public static final String ACTION_PLAY_QUEUE = "com.nikhildcrypto.sonora.PLAY_QUEUE";
    public static final String ACTION_PLAY = "com.nikhildcrypto.sonora.PLAY";
    public static final String ACTION_PAUSE = "com.nikhildcrypto.sonora.PAUSE";
    public static final String ACTION_TOGGLE = "com.nikhildcrypto.sonora.TOGGLE";
    public static final String ACTION_NEXT = "com.nikhildcrypto.sonora.NEXT";
    public static final String ACTION_PREVIOUS = "com.nikhildcrypto.sonora.PREVIOUS";
    public static final String ACTION_SEEK = "com.nikhildcrypto.sonora.SEEK";
    public static final String ACTION_SEEK_BY = "com.nikhildcrypto.sonora.SEEK_BY";
    public static final String ACTION_VOLUME = "com.nikhildcrypto.sonora.VOLUME";
    public static final String ACTION_SPEED = "com.nikhildcrypto.sonora.SPEED";
    public static final String ACTION_REPEAT = "com.nikhildcrypto.sonora.REPEAT";

    private ExoPlayer player;
    private MediaSession mediaSession;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) {
                broadcastState(false);
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(attrs, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setSeekBackIncrementMs(10_000);
        player.setSeekForwardIncrementMs(10_000);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                broadcastState(false);
                handler.removeCallbacks(ticker);
                if (isPlaying) handler.post(ticker);
            }
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) { broadcastState(false); }
            @Override public void onPlaybackStateChanged(int state) { broadcastState(state == Player.STATE_ENDED); }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) { broadcastState(false); }
        });
        mediaSession = new MediaSession.Builder(this, player).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) handle(intent);
        return super.onStartCommand(intent, flags, startId);
    }

    private void handle(Intent i) {
        String action = i.getAction();
        if (ACTION_PLAY_QUEUE.equals(action)) {
            try {
                JSONArray data = new JSONArray(i.getStringExtra("tracks"));
                ArrayList<MediaItem> items = new ArrayList<>();
                for (int n = 0; n < data.length(); n++) {
                    JSONObject t = data.getJSONObject(n);
                    MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                            .setTitle(t.optString("title", "Unknown track"))
                            .setArtist(t.optString("artist", "Unknown artist"))
                            .setAlbumTitle(t.optString("album", "SONORA"));
                    String art = t.optString("artworkUri", "");
                    if (!art.isEmpty()) metadata.setArtworkUri(Uri.parse(art));
                    MediaItem item = new MediaItem.Builder()
                            .setMediaId(t.optString("id"))
                            .setUri(Uri.parse(t.getString("uri")))
                            .setMediaMetadata(metadata.build()).build();
                    items.add(item);
                }
                if (items.isEmpty()) return;
                int index = Math.max(0, Math.min(i.getIntExtra("index", 0), items.size() - 1));
                player.setMediaItems(items, index, Math.max(0, i.getLongExtra("position", 0L)));
                int repeat = i.getIntExtra("repeat", 0);
                player.setRepeatMode(repeat == 2 ? Player.REPEAT_MODE_ONE : repeat == 1 ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
                player.setShuffleModeEnabled(false);
                player.setVolume((float) Math.max(0, Math.min(1, i.getDoubleExtra("volume", 1.0))));
                player.setPlaybackSpeed((float) Math.max(0.5, Math.min(2.0, i.getDoubleExtra("speed", 1.0))));
                player.prepare(); player.play();
            } catch (Exception e) { broadcastState(false); }
        } else if (player != null) {
            switch (action == null ? "" : action) {
                case ACTION_PLAY: player.play(); break;
                case ACTION_PAUSE: player.pause(); break;
                case ACTION_TOGGLE: if (player.isPlaying()) player.pause(); else player.play(); break;
                case ACTION_NEXT:
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem();
                    else if (player.getRepeatMode() == Player.REPEAT_MODE_ALL && player.getMediaItemCount() > 0) player.seekTo(0, 0);
                    else if (player.getMediaItemCount() > 0) { player.seekTo(player.getCurrentMediaItemIndex(), 0); player.pause(); }
                    break;
                case ACTION_PREVIOUS:
                    if (player.getCurrentPosition() > 3000) player.seekTo(0);
                    else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
                    else if (player.getMediaItemCount() > 0) player.seekTo(0, 0);
                    break;
                case ACTION_SEEK: player.seekTo(Math.max(0, i.getLongExtra("position", 0L))); break;
                case ACTION_SEEK_BY: player.seekTo(Math.max(0, player.getCurrentPosition() + i.getLongExtra("position", 0L))); break;
                case ACTION_VOLUME: player.setVolume((float) Math.max(0, Math.min(1, i.getDoubleExtra("volume", 1.0)))); break;
                case ACTION_SPEED: player.setPlaybackSpeed((float) Math.max(0.5, Math.min(2.0, i.getDoubleExtra("speed", 1.0)))); break;
                case ACTION_REPEAT:
                    int repeat = i.getIntExtra("repeat", 0);
                    player.setRepeatMode(repeat == 2 ? Player.REPEAT_MODE_ONE : repeat == 1 ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
                    break;
            }
            broadcastState(false);
        }
    }

    private void broadcastState(boolean ended) {
        if (player == null) return;
        Intent state = new Intent(ACTION_STATE);
        state.setPackage(getPackageName());
        MediaItem item = player.getCurrentMediaItem();
        state.putExtra("id", item == null ? "" : item.mediaId);
        state.putExtra("index", player.getCurrentMediaItemIndex());
        state.putExtra("position", player.getCurrentPosition());
        state.putExtra("duration", player.getDuration() == C.TIME_UNSET ? 0L : player.getDuration());
        state.putExtra("playing", player.isPlaying());
        state.putExtra("ended", ended);
        sendBroadcast(state);
    }

    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return mediaSession; }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (player == null || !player.isPlaying()) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (mediaSession != null) { mediaSession.release(); mediaSession = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
