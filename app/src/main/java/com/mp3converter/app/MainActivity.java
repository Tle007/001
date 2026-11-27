package com.mp3converter.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_PERM = 2;
    private static final String FOLDER = "MP3 Converter";
    private static final int PURPLE = 0xFF7C3AED;

    private RadioGroup qualityGroup;
    private Button pickButton, cancelButton;
    private TextView statusText;
    private ProgressBar progressBar;
    private LinearLayout resultList;
    private volatile boolean working = false;
    private final List<Uri> pending = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    // ---------- หน้าจอ ----------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(PURPLE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF1F5F9);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("แปลง MP4 เป็น MP3");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF0F172A);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("เลือกวิดีโอ แอปจะดึงเสียงออกมาเป็นไฟล์ MP3 ให้ (ไม่ต้องใช้เน็ต)\nไฟล์จะอยู่ในโฟลเดอร์ Music/" + FOLDER);
        sub.setTextSize(14);
        sub.setTextColor(0xFF64748B);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        // การ์ดคุณภาพเสียง
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(Color.WHITE, 16));
        root.addView(card);

        TextView qLabel = new TextView(this);
        qLabel.setText("คุณภาพเสียง");
        qLabel.setTextSize(15);
        qLabel.setTypeface(Typeface.DEFAULT_BOLD);
        qLabel.setTextColor(0xFF334155);
        card.addView(qLabel);

        qualityGroup = new RadioGroup(this);
        qualityGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"128k\nไฟล์เล็ก", "192k\nแนะนำ", "320k\nชัดสุด"};
        int[] rates = {128, 192, 320};
        for (int i = 0; i < rates.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(rates[i]);
            rb.setText(labels[i]);
            rb.setTextSize(14);
            rb.setLayoutParams(new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
            qualityGroup.addView(rb);
        }
        qualityGroup.check(192);
        card.addView(qualityGroup);

        pickButton = new Button(this);
        pickButton.setText("เลือกไฟล์วิดีโอ");
        pickButton.setTextSize(18);
        pickButton.setAllCaps(false);
        pickButton.setTextColor(Color.WHITE);
        pickButton.setBackground(rounded(PURPLE, 16));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        bp.topMargin = dp(16);
        pickButton.setLayoutParams(bp);
        pickButton.setOnClickListener(v -> pickFiles());
        root.addView(pickButton);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTextColor(0xFF334155);
        statusText.setPadding(0, dp(16), 0, dp(6));
        statusText.setText("ยังไม่ได้เลือกไฟล์ (เลือกได้หลายไฟล์พร้อมกัน)");
        root.addView(statusText);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));

        cancelButton = new Button(this);
        cancelButton.setText("ยกเลิก");
        cancelButton.setAllCaps(false);
        cancelButton.setTextColor(0xFFE11D48);
        cancelButton.setBackground(rounded(Color.WHITE, 12));
        cancelButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        cp.topMargin = dp(10);
        cancelButton.setLayoutParams(cp);
        cancelButton.setOnClickListener(v -> {
            Converter.cancelled = true;
            pending.clear();
            statusText.setText("กำลังยกเลิก...");
        });
        root.addView(cancelButton);

        TextView doneLabel = new TextView(this);
        doneLabel.setText("ไฟล์ที่แปลงเสร็จ (แตะเพื่อเปิดฟัง)");
        doneLabel.setTextSize(15);
        doneLabel.setTypeface(Typeface.DEFAULT_BOLD);
        doneLabel.setTextColor(0xFF334155);
        doneLabel.setPadding(0, dp(20), 0, dp(8));
        root.addView(doneLabel);

        resultList = new LinearLayout(this);
        resultList.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultList);

        setContentView(scroll);
    }

    // ---------- เลือกไฟล์ ----------
    private void pickFiles() {
        if (working) return;
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            toast("เปิดหน้าเลือกไฟล์ไม่ได้");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_PERM) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (!pending.isEmpty()) startQueue(); else pickFiles();
            } else {
                toast("ต้องอนุญาตให้บันทึกไฟล์ก่อน");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        enqueue(uris);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (!uris.isEmpty()) {
            intent.setAction(Intent.ACTION_MAIN); // กันแปลงซ้ำ
            enqueue(uris);
        }
    }

    private void enqueue(List<Uri> uris) {
        if (uris.isEmpty()) return;
        if (working) { toast("รอให้แปลงชุดเดิมเสร็จก่อน"); return; }
        pending.clear();
        pending.addAll(uris);
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        startQueue();
    }

    // ---------- แปลงไฟล์ ----------
    private void startQueue() {
        final List<Uri> queue = new ArrayList<>(pending);
        final int kbps = qualityGroup.getCheckedRadioButtonId() > 0 ? qualityGroup.getCheckedRadioButtonId() : 192;
        working = true;
        Converter.cancelled = false;
        setBusy(true);

        new Thread(() -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < queue.size(); i++) {
                if (Converter.cancelled) break;
                final Uri src = queue.get(i);
                final String name = baseName(src) + ".mp3";
                final String head = (queue.size() > 1 ? "ไฟล์ " + (i + 1) + "/" + queue.size() + " · " : "") + name;
                runOnUiThread(() -> { statusText.setText("กำลังแปลง: " + head + "\n0%"); progressBar.setProgress(0); });
                try {
                    Uri result = convertOne(src, name, kbps, pct -> runOnUiThread(() -> {
                        progressBar.setProgress(pct);
                        statusText.setText("กำลังแปลง: " + head + "\n" + pct + "%");
                    }));
                    ok++;
                    final Uri r = result;
                    runOnUiThread(() -> addResult(name, r));
                } catch (Converter.CancelledException e) {
                    break;
                } catch (Exception e) {
                    fail++;
                    final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    runOnUiThread(() -> addError(name, msg));
                }
            }
            final int fOk = ok, fFail = fail;
            final boolean wasCancelled = Converter.cancelled;
            runOnUiThread(() -> {
                working = false;
                pending.clear();
                setBusy(false);
                if (wasCancelled) statusText.setText("ยกเลิกแล้ว (เสร็จ " + fOk + " ไฟล์)");
                else statusText.setText("เสร็จแล้ว ✓ " + fOk + " ไฟล์" + (fFail > 0 ? " · ไม่สำเร็จ " + fFail + " ไฟล์" : "")
                        + "\nอยู่ในโฟลเดอร์ Music/" + FOLDER);
            });
        }).start();
    }

    private void setBusy(boolean busy) {
        pickButton.setEnabled(!busy);
        pickButton.setAlpha(busy ? 0.5f : 1f);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** แปลงหนึ่งไฟล์ แล้วคืน Uri ของ MP3 ที่บันทึกไว้ */
    private Uri convertOne(Uri src, String name, int kbps, Converter.Listener listener) throws Exception {
        ContentResolver cr = getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (item == null) throw new Exception("สร้างไฟล์ปลายทางไม่ได้");
            boolean success = false;
            try (OutputStream os = new BufferedOutputStream(cr.openOutputStream(item), 65536)) {
                Converter.convert(this, src, os, kbps, listener);
                success = true;
            } finally {
                if (success) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(item, done, null, null);
                } else {
                    try { cr.delete(item, null, null); } catch (Exception ignored) {}
                }
            }
            return item;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("สร้างโฟลเดอร์ไม่ได้");
            File f = uniqueFile(dir, name);
            boolean success = false;
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 65536)) {
                Converter.convert(this, src, os, kbps, listener);
                success = true;
            } finally {
                if (!success) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{"audio/mpeg"}, null);
            return Uri.fromFile(f);
        }
    }

    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        String stem = name.substring(0, name.length() - 4);
        int n = 1;
        while (f.exists()) f = new File(dir, stem + " (" + (n++) + ").mp3");
        return f;
    }

    private String baseName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "audio";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ---------- รายการผลลัพธ์ ----------
    private void addResult(String name, Uri uri) {
        TextView row = new TextView(this);
        row.setText("▶  " + name);
        row.setTextSize(16);
        row.setTextColor(0xFF0F172A);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setBackground(rounded(Color.WHITE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> openAudio(uri));
        resultList.addView(row, 0);
    }

    private void addError(String name, String msg) {
        TextView row = new TextView(this);
        row.setText("✕  " + name + "\n" + msg);
        row.setTextSize(14);
        row.setTextColor(0xFFBE123C);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(rounded(0xFFFFE4E6, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        resultList.addView(row, 0);
    }

    private void openAudio(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            toast("ไฟล์อยู่ในโฟลเดอร์ Music/" + FOLDER);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "audio/mpeg");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("ไม่มีแอปเล่นเพลงในเครื่อง");
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onDestroy() {
        if (working) Converter.cancelled = true;
        super.onDestroy();
    }
}
