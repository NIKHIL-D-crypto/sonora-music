package com.nikhildcrypto.sonora;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaMetadata;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "SonoraNativeMedia")
public class SonoraNativeMediaPlugin extends Plugin {
    @Override public void load() {
        SonoraPlaybackService.sink = (a, ms) -> {
            JSObject o = new JSObject();
            o.put("a", a);
            o.put("t", ms);
            notifyListeners("action", o);
        };
        // Android 13+: without this the media notification is hidden.
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
                && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    /** {clear:true} | {pos,dur,playing,speed, [id,title,artist,album,art(base64 jpeg)]} — meta only sent on track change. */
    @PluginMethod public void update(PluginCall c) {
        boolean clear = c.getBoolean("clear", false);
        boolean newTrack = c.hasOption("title");
        boolean playing = c.getBoolean("playing", false);
        long pos = c.getLong("pos", 0L), dur = c.getLong("dur", 0L);
        float speed = c.getDouble("speed", 1.0).floatValue();
        String id = c.getString("id", ""), art = c.getString("art", "");
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .setTitle(c.getString("title", "")).setArtist(c.getString("artist", "")).setAlbumTitle(c.getString("album", ""));
        if (!art.isEmpty()) m.setArtworkData(Base64.decode(art, Base64.DEFAULT), MediaMetadata.PICTURE_TYPE_FRONT_COVER);
        SonoraPlaybackService.H.post(() -> {
            SonoraPlaybackService.Proxy p = SonoraPlaybackService.proxy();
            if (clear) { p.clear(); return; }
            if (newTrack) p.setMeta(id, m.build());
            p.setState(playing, pos, dur, speed);
        });
        if (playing && !SonoraPlaybackService.up) {
            try {
                ContextCompat.startForegroundService(getContext(), new Intent(getContext(), SonoraPlaybackService.class));
            } catch (Exception ignored) { } // Android 12+ blocks this from the background
        }
        c.resolve();
    }
}
