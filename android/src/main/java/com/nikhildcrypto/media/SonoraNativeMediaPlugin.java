package com.nikhildcrypto.sonora.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@CapacitorPlugin(name = "SonoraNativeMedia")
public class SonoraNativeMediaPlugin extends Plugin {
    private BroadcastReceiver stateReceiver;

    @Override
    public void load() {
        super.load();
        stateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                JSObject data = new JSObject();
                data.put("id", intent.getStringExtra("id"));
                data.put("index", intent.getIntExtra("index", -1));
                data.put("position", intent.getLongExtra("position", 0));
                data.put("duration", intent.getLongExtra("duration", 0));
                data.put("playing", intent.getBooleanExtra("playing", false));
                data.put("ended", intent.getBooleanExtra("ended", false));
                notifyListeners("playbackState", data);
            }
        };
        ContextCompat.registerReceiver(getContext(), stateReceiver,
                new IntentFilter(SonoraPlaybackService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void handleOnDestroy() {
        if (stateReceiver != null) {
            try { getContext().unregisterReceiver(stateReceiver); } catch (Exception ignored) { }
        }
        super.handleOnDestroy();
    }

    private String key(String id) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(id.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    private File assetFile(String id, String kind, String mime) throws Exception {
        File dir = new File(getContext().getFilesDir(), "sonora-media");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create media directory");
        String ext = "audio".equals(kind) ? ".audio" : ".jpg";
        if ("audio".equals(kind)) {
            String m = mime == null ? "" : mime.toLowerCase();
            if (m.contains("mpeg")) ext = ".mp3";
            else if (m.contains("mp4") || m.contains("m4a")) ext = ".m4a";
            else if (m.contains("wav")) ext = ".wav";
            else if (m.contains("ogg")) ext = ".ogg";
            else if (m.contains("flac")) ext = ".flac";
            else if (m.contains("aac")) ext = ".aac";
        }
        return new File(dir, key(id) + ("audio".equals(kind) ? ext : ".jpg"));
    }

    @PluginMethod
    public void getAssetUri(PluginCall call) {
        try {
            File f = assetFile(call.getString("id"), call.getString("kind", "audio"), call.getString("mime", ""));
            JSObject out = new JSObject();
            File marker = new File(f.getAbsolutePath() + ".complete");
            out.put("exists", f.isFile() && f.length() > 0 && marker.isFile());
            out.put("uri", f.isFile() && marker.isFile() ? Uri.fromFile(f).toString() : "");
            call.resolve(out);
        } catch (Exception e) { call.reject("Could not inspect native media file", e); }
    }

    @PluginMethod
    public void beginAsset(PluginCall call) {
        try {
            String id = call.getString("id");
            String kind = call.getString("kind", "audio");
            File f = assetFile(id, kind, call.getString("mime", ""));
            long size = call.getLong("size", 0L);
            new File(f.getAbsolutePath() + ".complete").delete();
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) { raf.setLength(Math.max(0L, size)); }
            JSObject out = new JSObject(); out.put("uri", Uri.fromFile(f).toString()); call.resolve(out);
        } catch (Exception e) { call.reject("Could not prepare native media file", e); }
    }

    @PluginMethod
    public void writeAssetChunk(PluginCall call) {
        try {
            File f = assetFile(call.getString("id"), call.getString("kind", "audio"), call.getString("mime", ""));
            long offset = call.getLong("offset", 0L);
            byte[] bytes = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) { raf.seek(offset); raf.write(bytes); }
            call.resolve();
        } catch (Exception e) { call.reject("Could not write native media data", e); }
    }

    @PluginMethod
    public void finishAsset(PluginCall call) {
        try {
            File f = assetFile(call.getString("id"), call.getString("kind", "audio"), call.getString("mime", ""));
            if (!f.isFile() || f.length() <= 0) throw new Exception("Media file is empty");
            File marker = new File(f.getAbsolutePath() + ".complete");
            if (!marker.createNewFile() && !marker.isFile()) throw new Exception("Could not mark media file complete");
            call.resolve();
        } catch (Exception e) { call.reject("Could not finish native media file", e); }
    }

    @PluginMethod
    public void deleteAsset(PluginCall call) {
        try {
            File f = assetFile(call.getString("id"), call.getString("kind", "audio"), call.getString("mime", ""));
            if (f.exists()) f.delete();
            new File(f.getAbsolutePath() + ".complete").delete();
            call.resolve();
        } catch (Exception e) { call.reject("Could not delete native media file", e); }
    }

    @PluginMethod
    public void playQueue(PluginCall call) {
        try {
            JSArray tracks = call.getArray("tracks", new JSArray());
            Intent intent = new Intent(getContext(), SonoraPlaybackService.class);
            intent.setAction(SonoraPlaybackService.ACTION_PLAY_QUEUE);
            intent.putExtra("tracks", tracks.toString());
            intent.putExtra("index", call.getInt("index", 0));
            intent.putExtra("position", call.getLong("position", 0L));
            intent.putExtra("repeat", call.getInt("repeat", 0));
            intent.putExtra("shuffle", false);
            intent.putExtra("volume", call.getDouble("volume", 1.0));
            intent.putExtra("speed", call.getDouble("speed", 1.0));
            ContextCompat.startForegroundService(getContext(), intent);
            call.resolve();
        } catch (Exception e) { call.reject("Could not start native playback", e); }
    }

    private void command(PluginCall call, String action) {
        Intent intent = new Intent(getContext(), SonoraPlaybackService.class);
        intent.setAction(action);
        if (SonoraPlaybackService.ACTION_SEEK.equals(action) || SonoraPlaybackService.ACTION_SEEK_BY.equals(action)) intent.putExtra("position", call.getLong("position", 0L));
        if (SonoraPlaybackService.ACTION_REPEAT.equals(action)) intent.putExtra("repeat", call.getInt("repeat", 0));
        if (call.getDouble("volume", -1.0) >= 0) intent.putExtra("volume", call.getDouble("volume", 1.0));
        if (call.getDouble("speed", -1.0) > 0) intent.putExtra("speed", call.getDouble("speed", 1.0));
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod public void play(PluginCall call) { command(call, SonoraPlaybackService.ACTION_PLAY); }
    @PluginMethod public void pause(PluginCall call) { command(call, SonoraPlaybackService.ACTION_PAUSE); }
    @PluginMethod public void toggle(PluginCall call) { command(call, SonoraPlaybackService.ACTION_TOGGLE); }
    @PluginMethod public void next(PluginCall call) { command(call, SonoraPlaybackService.ACTION_NEXT); }
    @PluginMethod public void previous(PluginCall call) { command(call, SonoraPlaybackService.ACTION_PREVIOUS); }
    @PluginMethod public void seek(PluginCall call) { command(call, SonoraPlaybackService.ACTION_SEEK); }
    @PluginMethod public void seekBy(PluginCall call) { command(call, SonoraPlaybackService.ACTION_SEEK_BY); }
    @PluginMethod public void setVolume(PluginCall call) { command(call, SonoraPlaybackService.ACTION_VOLUME); }
    @PluginMethod public void setSpeed(PluginCall call) { command(call, SonoraPlaybackService.ACTION_SPEED); }
    @PluginMethod public void setRepeat(PluginCall call) { command(call, SonoraPlaybackService.ACTION_REPEAT); }
}
