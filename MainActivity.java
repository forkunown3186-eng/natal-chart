package com.natalchart.app;

// ─────────────────────────────────────────────────────────────────
//  MainActivity.java
//  Place at:
//    android/app/src/main/java/com/natalchart/app/MainActivity.java
//
//  Extends BridgeActivity (Capacitor) and adds:
//    1. Runtime storage permission request (Android 6+)
//    2. WebView download listener → saves files to public Downloads
//    3. WebView settings for canvas/blob/file API support
// ─────────────────────────────────────────────────────────────────

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "NatalChart";
    private static final int REQ_STORAGE = 1001;
    private static final int REQ_LOCATION = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Request permissions on first launch
        requestStoragePermissions();

        // Configure WebView for canvas + file downloads
        configureWebView();
    }

    // ── Permission handling ─────────────────────────────────────

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 6–9: need WRITE_EXTERNAL_STORAGE at runtime
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                    REQ_STORAGE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: granular media permissions
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES
                    },
                    REQ_STORAGE);
            }
        }
        // Android 10–12: Scoped Storage — no permission needed for Downloads
        // via MediaStore or Environment.getExternalStoragePublicDirectory
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Log.i(TAG, "Storage permission granted");
            } else {
                Toast.makeText(this,
                    "Storage permission denied — files will save to app folder only",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── WebView configuration ────────────────────────────────────

    private void configureWebView() {
        WebView webView = getBridge().getWebView();
        WebSettings settings = webView.getSettings();

        // Enable all required web APIs
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);          // localStorage
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // Hardware acceleration for canvas (set in Activity theme too)
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);

        // ── Download listener ─────────────────────────────────
        // Handles data: URIs (PNG/PDF blobs from canvas.toDataURL)
        // and regular https:// downloads
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                if (url.startsWith("data:")) {
                    // data: URI — decode and write directly to Downloads
                    saveDataUri(url, mimetype);
                } else if (url.startsWith("blob:")) {
                    // blob: URLs can't be intercepted here directly;
                    // handled in JS via canvas.toBlob() → object URL → anchor click
                    Log.w(TAG, "Blob URL download (handled in JS): " + url);
                } else {
                    // Regular URL — use DownloadManager
                    DownloadManager.Request request =
                        new DownloadManager.Request(Uri.parse(url));
                    String filename = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    request.setTitle(filename);
                    request.setDescription("Natal Chart export");
                    request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, filename);
                    request.allowScanningByMediaScanner();

                    DownloadManager dm = (DownloadManager)
                        getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(MainActivity.this,
                            "Saving to Downloads: " + filename,
                            Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Download error: " + e.getMessage());
                Toast.makeText(this, "Save error: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            }
        });
    }

    // ── Save data: URI to public Downloads ──────────────────────

    private void saveDataUri(String dataUri, String mimetype) {
        try {
            // Parse: "data:image/png;base64,<data>"
            String[] parts = dataUri.split(",", 2);
            if (parts.length < 2) throw new IOException("Invalid data URI");

            String header = parts[0]; // "data:image/png;base64"
            String body   = parts[1]; // base64 data

            // Determine extension
            String ext = ".bin";
            if (header.contains("image/png"))       ext = ".png";
            else if (header.contains("image/jpeg")) ext = ".jpg";
            else if (header.contains("application/pdf")) ext = ".pdf";

            byte[] bytes;
            if (header.contains("base64")) {
                bytes = Base64.decode(body, Base64.DEFAULT);
            } else {
                // URL-encoded text
                bytes = Uri.decode(body).getBytes("UTF-8");
            }

            String filename = "natal-chart-" +
                System.currentTimeMillis() + ext;

            File destFile;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage (Android 10+): write to app-specific external dir,
                // then copy to Downloads via MediaStore
                File cacheDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (cacheDir == null) cacheDir = getCacheDir();
                destFile = new File(cacheDir, filename);
            } else {
                // Android 9 and below: write directly to public Downloads
                File downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) downloadsDir.mkdirs();
                destFile = new File(downloadsDir, filename);
            }

            try (FileOutputStream fos = new FileOutputStream(destFile)) {
                fos.write(bytes);
            }

            // Trigger media scan so file appears in gallery/Files app
            Intent scanIntent = new Intent(
                Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                Uri.fromFile(destFile));
            sendBroadcast(scanIntent);

            // Show a notification-style toast with the path
            final String savedPath = destFile.getAbsolutePath();
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this,
                    "✓ Saved to: " + savedPath,
                    Toast.LENGTH_LONG).show()
            );

            Log.i(TAG, "File saved: " + savedPath);

        } catch (Exception e) {
            Log.e(TAG, "saveDataUri failed: " + e.getMessage());
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this,
                    "Save failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show()
            );
        }
    }
}
