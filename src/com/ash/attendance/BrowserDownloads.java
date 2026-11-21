package com.ash.attendance;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saves files the portal sends (fee receipts, bus pass, help PDFs) to the phone's Downloads folder and opens them.
 * GeckoView hands these to the app instead of saving them itself; without this a download link does nothing.
 */
final class BrowserDownloads {
    private BrowserDownloads() { }

    static void save(Activity a, WebResponse r) {
        if (r == null || r.body == null) return;
        String type = header(r.headers, "content-type");
        if (type != null) type = type.split(";")[0].trim().toLowerCase(Locale.ROOT);
        String name = fileName(r, type);
        String mime = type;
        Toast.makeText(a, "Downloading " + name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Uri saved = null;
            String error = null;
            try (InputStream in = r.body) {
                saved = Build.VERSION.SDK_INT >= 29 ? toMediaStore(a, in, name, mime) : toAppFolder(a, in, name);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final Uri uri = saved;
            final String err = error;
            a.runOnUiThread(() -> {
                if (uri == null) {
                    Toast.makeText(a, "Download failed" + (err == null ? "" : ": " + err), Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(a, "Saved " + name + " to Downloads", Toast.LENGTH_SHORT).show();
                if (Build.VERSION.SDK_INT >= 29) open(a, uri, mime);
            });
        }, "download").start();
    }

    private static Uri toMediaStore(Activity a, InputStream in, String name, String mime) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        if (mime != null) v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("no storage");
        try (OutputStream out = a.getContentResolver().openOutputStream(uri)) {
            copy(in, out);
        }
        v.clear();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        a.getContentResolver().update(uri, v, null, null);
        return uri;
    }

    /** Android 8-9: the app's own Downloads folder (no storage permission needed). */
    private static Uri toAppFolder(Activity a, InputStream in, String name) throws Exception {
        File dir = a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IllegalStateException("no storage");
        File f = new File(dir, name);
        try (OutputStream out = new FileOutputStream(f)) {
            copy(in, out);
        }
        return Uri.fromFile(f);
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    private static void open(Activity a, Uri uri, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime == null ? "*/*" : mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "Open with"));
        } catch (Exception ignored) { }
    }

    private static String header(Map<String, String> headers, String key) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }

    private static final Pattern DISPOSITION = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
            Pattern.CASE_INSENSITIVE);

    static String fileName(WebResponse r, String mime) {
        String name = null;
        String cd = header(r.headers, "content-disposition");
        if (cd != null) {
            Matcher m = DISPOSITION.matcher(cd);
            if (m.find()) name = Uri.decode(m.group(1));
        }
        if (name == null || name.trim().isEmpty()) name = Uri.parse(r.uri).getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "download";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (!name.contains(".") && mime != null) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) name += "." + ext;
        }
        return name;
    }
}
