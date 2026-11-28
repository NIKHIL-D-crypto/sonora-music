package com.nikhildcrypto.sonora;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Notification / lock-screen / headset surface only. The WebView <audio> element stays the real
 * player; this session mirrors its state and forwards button presses back to JS via {@link #sink}.
 */
@UnstableApi
public class SonoraPlaybackService extends MediaSessionService {
    interface Sink { void send(String action, long ms); }

    static final Handler H = new Handler(Looper.getMainLooper());
    static volatile boolean up;
    static volatile Sink sink;
    private static Proxy proxy; // main thread only

    static Proxy proxy() {
        if (proxy == null) proxy = new Proxy();
        return proxy;
    }

    /** Fake 3-item playlist (prev, current, next) so Media3 enables skip buttons; JS owns the real queue. */
    static final class Proxy extends SimpleBasePlayer {
        private static final Commands CMDS = new Commands.Builder().addAll(
                COMMAND_PLAY_PAUSE, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_FORWARD, COMMAND_SEEK_BACK, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_METADATA, COMMAND_GET_TIMELINE).build();

        private boolean has, playing;
        private String id = "";
        private MediaMetadata meta = MediaMetadata.EMPTY;
        private long pos, dur, posAt;
        private float speed = 1f;

        Proxy() { super(Looper.getMainLooper()); }

        void clear() { has = false; playing = false; invalidateState(); }

        void setMeta(String id, MediaMetadata meta) { this.id = id; this.meta = meta; has = true; }

        void setState(boolean playing, long pos, long dur, float speed) {
            this.playing = playing; this.pos = pos; this.dur = dur; this.speed = speed;
            posAt = SystemClock.elapsedRealtime();
            invalidateState();
        }

        @Override protected State getState() {
            State.Builder b = new State.Builder().setAvailableCommands(CMDS);
            if (!has) return b.build();
            // getState() can be called again later, so extrapolate from the last JS update.
            long now = pos + (playing ? (long) ((SystemClock.elapsedRealtime() - posAt) * speed) : 0);
            MediaItem item = new MediaItem.Builder().setMediaId(id).setMediaMetadata(meta).build();
            MediaItemData cur = new MediaItemData.Builder("c").setMediaItem(item)
                    .setDurationUs(dur > 0 ? dur * 1000 : androidx.media3.common.C.TIME_UNSET).setIsSeekable(true).build();
            return b.setPlaylist(ImmutableList.of(new MediaItemData.Builder("p").build(), cur, new MediaItemData.Builder("n").build()))
                    .setCurrentMediaItemIndex(1)
                    .setPlaybackState(Player.STATE_READY)
                    .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .setPlaybackParameters(new PlaybackParameters(speed))
                    .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
                    .setContentPositionMs(playing ? PositionSupplier.getExtrapolating(now, speed) : PositionSupplier.getConstant(now))
                    .build();
        }

        private ListenableFuture<?> out(String a, long ms) {
            Sink s = sink;
            if (s != null) s.send(a, ms);
            return Futures.immediateVoidFuture();
        }

        @Override protected ListenableFuture<?> handleSetPlayWhenReady(boolean play) { return out(play ? "play" : "pause", 0); }

        @Override protected ListenableFuture<?> handleSeek(int index, long positionMs, int cmd) {
            switch (cmd) {
                case COMMAND_SEEK_TO_NEXT: case COMMAND_SEEK_TO_NEXT_MEDIA_ITEM: return out("next", 0);
                case COMMAND_SEEK_TO_PREVIOUS: case COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM: return out("prev", 0);
                default: return out("seek", Math.max(0, positionMs)); // forward/back 10s arrive as absolute positions
            }
        }
    }

    private MediaSession session;

    @Override public void onCreate() {
        super.onCreate();
        up = true;
        CommandButton fwd = new CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setDisplayName("Forward 10 seconds").build();
        session = new MediaSession.Builder(this, proxy()).setCustomLayout(ImmutableList.of(fwd)).build();
        addSession(session); // post the notification without waiting for a controller to connect
    }

    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo c) { return session; }

    @Override public void onTaskRemoved(android.content.Intent root) {
        Proxy p = proxy();
        if (!p.getPlayWhenReady()) stopSelf(); // keep playing in background when swiped away
    }

    @Override public void onDestroy() {
        up = false;
        if (session != null) { session.release(); session = null; }
        super.onDestroy();
    }
}
