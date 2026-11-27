package com.mp3converter.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.TextWatcher;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_PERM = 2;
    private static final int REQ_TREE = 3;
    private static final int REQ_SRT = 4;
    private static final String PREF_TREE = "tree_uri";
    private static final String FOLDER = "MP3 Converter";
    private static final int PURPLE = 0xFF7C3AED;

    private RadioGroup qualityGroup;
    private Button pickButton, cancelButton;
    private TextView statusText, folderText;
    private Button resetFolderButton;
    private SharedPreferences prefs;
    private ProgressBar progressBar;
    private LinearLayout resultList;
    private volatile boolean working = false;
    private final List<Uri> pending = new ArrayList<>();
    private Button srtButton;
    private RadioGroup langGroup, speedGroup, pitchGroup;
    private CheckBox timedCheck, emotionCheck;
    private Spinner voiceSpinner;
    private EditText dictEdit;
    private final List<Voice> voiceList = new ArrayList<>();
    private static final String PREF_VOICE = "voice";
    private static final String PREF_DICT = "dict";
    private Uri pendingSrt;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private interface StreamWriter { void write(OutputStream os) throws Exception; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        buildUi();
        updateFolderUi();
        ensureTts(null);
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
        sub.setText("เลือกวิดีโอ แอปจะดึงเสียงออกมาเป็นไฟล์ MP3 ให้ (ไม่ต้องใช้เน็ต)");
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

        // การ์ดที่เก็บไฟล์
        LinearLayout fcard = new LinearLayout(this);
        fcard.setOrientation(LinearLayout.VERTICAL);
        fcard.setPadding(dp(16), dp(14), dp(16), dp(14));
        fcard.setBackground(rounded(Color.WHITE, 16));
        LinearLayout.LayoutParams fcp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fcp.topMargin = dp(12);
        fcard.setLayoutParams(fcp);
        root.addView(fcard);

        TextView fLabel = new TextView(this);
        fLabel.setText("ที่เก็บไฟล์ MP3");
        fLabel.setTextSize(15);
        fLabel.setTypeface(Typeface.DEFAULT_BOLD);
        fLabel.setTextColor(0xFF334155);
        fcard.addView(fLabel);

        folderText = new TextView(this);
        folderText.setTextSize(15);
        folderText.setTextColor(PURPLE);
        folderText.setPadding(0, dp(4), 0, dp(8));
        fcard.addView(folderText);

        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        fcard.addView(frow);

        Button changeFolder = new Button(this);
        changeFolder.setText("เปลี่ยนที่เก็บ");
        changeFolder.setAllCaps(false);
        changeFolder.setTextColor(PURPLE);
        changeFolder.setBackground(rounded(0xFFEDE9FE, 12));
        changeFolder.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1f));
        changeFolder.setOnClickListener(v -> chooseFolder());
        frow.addView(changeFolder);

        resetFolderButton = new Button(this);
        resetFolderButton.setText("ใช้ที่เดิม");
        resetFolderButton.setAllCaps(false);
        resetFolderButton.setTextColor(0xFF475569);
        resetFolderButton.setBackground(rounded(0xFFF1F5F9, 12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        rlp.leftMargin = dp(8);
        resetFolderButton.setLayoutParams(rlp);
        resetFolderButton.setOnClickListener(v -> {
            Uri old = getTreeUri();
            if (old != null) {
                try { getContentResolver().releasePersistableUriPermission(old,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) {}
            }
            prefs.edit().remove(PREF_TREE).apply();
            updateFolderUi();
        });
        frow.addView(resetFolderButton);

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

        // ---------- การ์ดอ่านไฟล์ SRT ----------
        LinearLayout scard = new LinearLayout(this);
        scard.setOrientation(LinearLayout.VERTICAL);
        scard.setPadding(dp(16), dp(14), dp(16), dp(14));
        scard.setBackground(rounded(Color.WHITE, 16));
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scp.topMargin = dp(20);
        scard.setLayoutParams(scp);
        root.addView(scard);

        TextView sTitle = new TextView(this);
        sTitle.setText("อ่านข้อความจากไฟล์ SRT เป็น MP3");
        sTitle.setTextSize(17);
        sTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sTitle.setTextColor(0xFF0F172A);
        scard.addView(sTitle);

        TextView sSub = new TextView(this);
        sSub.setText("ใช้เสียงอ่านของโทรศัพท์อ่านซับทีละบรรทัด แล้วรวมเป็นไฟล์ MP3");
        sSub.setTextSize(13);
        sSub.setTextColor(0xFF64748B);
        sSub.setPadding(0, dp(2), 0, dp(8));
        scard.addView(sSub);

        langGroup = new RadioGroup(this);
        langGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] langs = {"ภาษาไทย", "English"};
        for (int i = 0; i < langs.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(langs[i]);
            rb.setLayoutParams(new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
            langGroup.addView(rb);
        }
        langGroup.check(1000);
        langGroup.setOnCheckedChangeListener((g, id) -> refreshVoices());
        scard.addView(langGroup);

        TextView vLabel = new TextView(this);
        vLabel.setText("เสียงผู้อ่าน");
        vLabel.setTextSize(14);
        vLabel.setTypeface(Typeface.DEFAULT_BOLD);
        vLabel.setTextColor(0xFF334155);
        vLabel.setPadding(0, dp(8), 0, 0);
        scard.addView(vLabel);

        voiceSpinner = new Spinner(this);
        voiceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                Voice v = pos > 0 && pos - 1 < voiceList.size() ? voiceList.get(pos - 1) : null;
                prefs.edit().putString(PREF_VOICE, v == null ? "" : v.getName()).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        scard.addView(voiceSpinner);

        TextView pLabel = new TextView(this);
        pLabel.setText("โทนเสียง");
        pLabel.setTextSize(14);
        pLabel.setTypeface(Typeface.DEFAULT_BOLD);
        pLabel.setTextColor(0xFF334155);
        pLabel.setPadding(0, dp(8), 0, 0);
        scard.addView(pLabel);

        pitchGroup = new RadioGroup(this);
        pitchGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] pitches = {"ทุ้ม", "ปกติ", "สดใส"};
        for (int i = 0; i < pitches.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(3000 + i);
            rb.setText(pitches[i]);
            rb.setLayoutParams(new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
            pitchGroup.addView(rb);
        }
        pitchGroup.check(3001);
        scard.addView(pitchGroup);

        TextView spLabel = new TextView(this);
        spLabel.setText("ความเร็ว");
        spLabel.setTextSize(14);
        spLabel.setTypeface(Typeface.DEFAULT_BOLD);
        spLabel.setTextColor(0xFF334155);
        spLabel.setPadding(0, dp(8), 0, 0);
        scard.addView(spLabel);

        speedGroup = new RadioGroup(this);
        speedGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] speeds = {"พูดช้า", "ปกติ", "พูดเร็ว"};
        for (int i = 0; i < speeds.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(2000 + i);
            rb.setText(speeds[i]);
            rb.setLayoutParams(new RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
            speedGroup.addView(rb);
        }
        speedGroup.check(2001);
        scard.addView(speedGroup);

        timedCheck = new CheckBox(this);
        timedCheck.setText("ให้เสียงตรงเวลาตามซับ (ถ้าพูดไม่ทันจะเร่งให้เอง)");
        timedCheck.setTextSize(14);
        timedCheck.setChecked(true);
        scard.addView(timedCheck);

        emotionCheck = new CheckBox(this);
        emotionCheck.setText("ใส่อารมณ์ (คำถามเสียงสูงขึ้น ตกใจ/ตื่นเต้นเร็วขึ้น ... พูดช้าลง)");
        emotionCheck.setTextSize(14);
        emotionCheck.setChecked(true);
        scard.addView(emotionCheck);

        TextView dLabel = new TextView(this);
        dLabel.setText("แก้คำอ่าน (บรรทัดละคำ: คำที่อ่านผิด=อ่านว่า)");
        dLabel.setTextSize(14);
        dLabel.setTypeface(Typeface.DEFAULT_BOLD);
        dLabel.setTextColor(0xFF334155);
        dLabel.setPadding(0, dp(8), 0, dp(4));
        scard.addView(dLabel);

        dictEdit = new EditText(this);
        dictEdit.setHint("เช่น\nAI=เอไอ\nTikTok=ติ๊กต็อก\nกทม.=กรุงเทพมหานคร");
        dictEdit.setTextSize(15);
        dictEdit.setMinLines(3);
        dictEdit.setGravity(Gravity.TOP | Gravity.START);
        dictEdit.setBackground(rounded(0xFFF1F5F9, 10));
        dictEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        dictEdit.setText(prefs.getString(PREF_DICT, ""));
        dictEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable e) { prefs.edit().putString(PREF_DICT, e.toString()).apply(); }
        });
        scard.addView(dictEdit);

        Button sampleButton = new Button(this);
        sampleButton.setText("▶ ฟังตัวอย่างเสียง");
        sampleButton.setAllCaps(false);
        sampleButton.setTextColor(0xFF0369A1);
        sampleButton.setBackground(rounded(0xFFE0F2FE, 12));
        LinearLayout.LayoutParams smp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        smp.topMargin = dp(10);
        sampleButton.setLayoutParams(smp);
        sampleButton.setOnClickListener(v -> playSample());
        scard.addView(sampleButton);

        srtButton = new Button(this);
        srtButton.setText("เลือกไฟล์ SRT");
        srtButton.setTextSize(17);
        srtButton.setAllCaps(false);
        srtButton.setTextColor(Color.WHITE);
        srtButton.setBackground(rounded(0xFF0EA5E9, 14));
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        sbp.topMargin = dp(10);
        srtButton.setLayoutParams(sbp);
        srtButton.setOnClickListener(v -> pickSrt());
        scard.addView(srtButton);

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
        if (Build.VERSION.SDK_INT < 29 && getTreeUri() == null
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
                if (pendingSrt != null) startSrt(); else if (!pending.isEmpty()) startQueue(); else pickFiles();
            } else {
                toast("ต้องอนุญาตให้บันทึกไฟล์ก่อน");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    prefs.edit().putString(PREF_TREE, tree.toString()).apply();
                    updateFolderUi();
                    toast("จะเก็บไฟล์ที่: " + folderLabel());
                } catch (Exception e) {
                    toast("ใช้โฟลเดอร์นี้ไม่ได้ ลองเลือกโฟลเดอร์อื่น");
                }
            }
            return;
        }
        if (requestCode == REQ_SRT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                pendingSrt = data.getData();
                if (Build.VERSION.SDK_INT < 29 && getTreeUri() == null
                        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
                    return;
                }
                startSrt();
            }
            return;
        }
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
        if (Build.VERSION.SDK_INT < 29 && getTreeUri() == null
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
                final String name = timeName() + ".mp3";
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
                        + "\nเก็บไว้ที่: " + folderLabel());
            });
        }).start();
    }

    private void setBusy(boolean busy) {
        pickButton.setEnabled(!busy);
        pickButton.setAlpha(busy ? 0.5f : 1f);
        srtButton.setEnabled(!busy);
        srtButton.setAlpha(busy ? 0.5f : 1f);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** แปลงหนึ่งไฟล์ แล้วคืน Uri ของ MP3 ที่บันทึกไว้ */
    private Uri convertOne(Uri src, String name, int kbps, Converter.Listener listener) throws Exception {
        return saveMp3(name, os -> Converter.convert(this, src, os, kbps, listener));
    }

    /** สร้างไฟล์ MP3 ในที่เก็บที่เลือก แล้วให้ writer เขียนข้อมูลลงไป */
    private Uri saveMp3(String name, StreamWriter writer) throws Exception {
        ContentResolver cr = getContentResolver();
        Uri tree = getTreeUri();
        if (tree != null) {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Uri item;
            try {
                item = DocumentsContract.createDocument(cr, parent, "audio/mpeg", name);
            } catch (Exception e) {
                item = null;
            }
            if (item == null) throw new Exception("เขียนไฟล์ลงโฟลเดอร์ที่เลือกไม่ได้ ลองกด \"เปลี่ยนที่เก็บ\" ใหม่");
            boolean success = false;
            try (OutputStream os = new BufferedOutputStream(cr.openOutputStream(item), 65536)) {
                writer.write(os);
                success = true;
            } finally {
                if (!success) {
                    try { DocumentsContract.deleteDocument(cr, item); } catch (Exception ignored) {}
                }
            }
            return item;
        } else if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri item = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (item == null) throw new Exception("สร้างไฟล์ปลายทางไม่ได้");
            boolean success = false;
            try (OutputStream os = new BufferedOutputStream(cr.openOutputStream(item), 65536)) {
                writer.write(os);
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
                writer.write(os);
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

    /** ชื่อไฟล์แบบอ่านง่าย: เวลาขึ้นก่อน เช่น "14.35น. 27-11-2569" */
    private String timeName() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(Locale.US, "%02d.%02dน. %02d-%02d-%d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.YEAR) + 543);
    }

    // ---------- อ่าน SRT เป็นเสียง ----------
    private void pickSrt() {
        if (working) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_SRT);
        } catch (ActivityNotFoundException e) {
            toast("เปิดหน้าเลือกไฟล์ไม่ได้");
        }
    }

    private void startSrt() {
        final Uri src = pendingSrt;
        pendingSrt = null;
        if (src == null || working) return;
        ensureTts(() -> runSrt(src));
    }

    private final List<Runnable> ttsWaiting = new ArrayList<>();

    private void ensureTts(Runnable then) {
        if (tts != null && ttsReady) { if (then != null) then.run(); return; }
        if (then != null) ttsWaiting.add(then);
        if (tts != null) return; // กำลังเตรียมอยู่
        tts = new TextToSpeech(this, status -> runOnUiThread(() -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                refreshVoices();
                List<Runnable> run = new ArrayList<>(ttsWaiting);
                ttsWaiting.clear();
                for (Runnable r : run) r.run();
            } else {
                tts = null;
                ttsWaiting.clear();
                statusText.setText("เครื่องนี้ไม่มีระบบอ่านออกเสียง\nลองติดตั้ง \"Speech Services by Google\" จาก Play Store");
            }
        }));
    }

    private boolean isThai() { return langGroup.getCheckedRadioButtonId() == 1000; }

    /** เติมรายชื่อเสียงของภาษาที่เลือก เรียงเสียงคุณภาพสูงไว้ก่อน */
    private void refreshVoices() {
        if (tts == null || !ttsReady || voiceSpinner == null) return;
        voiceList.clear();
        String lang = isThai() ? "th" : "en";
        try {
            java.util.Set<Voice> all = tts.getVoices();
            if (all != null) for (Voice v : all) {
                if (v.getLocale() == null || !lang.equals(v.getLocale().getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (!isThai() && !"US".equals(v.getLocale().getCountry()) && !"GB".equals(v.getLocale().getCountry())) continue;
                voiceList.add(v);
            }
        } catch (Exception ignored) {}
        java.util.Collections.sort(voiceList, (a, b) -> {
            if (a.getQuality() != b.getQuality()) return b.getQuality() - a.getQuality();
            return a.getName().compareTo(b.getName());
        });
        List<String> labels = new ArrayList<>();
        labels.add("เสียงเริ่มต้นของเครื่อง");
        String saved = prefs.getString(PREF_VOICE, "");
        int sel = 0;
        for (int i = 0; i < voiceList.size(); i++) {
            Voice v = voiceList.get(i);
            String q = v.getQuality() >= Voice.QUALITY_VERY_HIGH ? "คุณภาพสูงมาก"
                    : v.getQuality() >= Voice.QUALITY_HIGH ? "คุณภาพสูง" : "ปกติ";
            labels.add("เสียงที่ " + (i + 1) + " · " + q + (v.isNetworkConnectionRequired() ? " · ต้องใช้เน็ต" : " · ไม่ต้องใช้เน็ต"));
            if (v.getName().equals(saved)) sel = i + 1;
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(ad);
        voiceSpinner.setSelection(sel);
    }

    /** ตั้งภาษาและเสียงตามที่เลือก คืนค่า false ถ้าเครื่องไม่มีเสียงภาษานั้น */
    private boolean applyVoiceSettings() {
        boolean thai = isThai();
        int lang = tts.setLanguage(thai ? new Locale("th", "TH") : Locale.US);
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            statusText.setText("เครื่องยังไม่มีเสียงอ่าน" + (thai ? "ภาษาไทย" : "ภาษาอังกฤษ")
                    + "\nกำลังเปิดหน้าดาวน์โหลดเสียง ให้โหลดแล้วลองใหม่");
            try {
                startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
            } catch (ActivityNotFoundException ignored) {}
            return false;
        }
        int pos = voiceSpinner.getSelectedItemPosition();
        if (pos > 0 && pos - 1 < voiceList.size()) {
            try { tts.setVoice(voiceList.get(pos - 1)); } catch (Exception ignored) {}
        }
        return true;
    }

    private float selectedRate() {
        int sp = speedGroup.getCheckedRadioButtonId();
        return sp == 2000 ? 0.85f : sp == 2002 ? 1.25f : 1.0f;
    }

    private float selectedPitch() {
        int p = pitchGroup.getCheckedRadioButtonId();
        return p == 3000 ? 0.88f : p == 3002 ? 1.12f : 1.0f;
    }

    private void playSample() {
        ensureTts(() -> {
            if (!applyVoiceSettings()) return;
            SrtSpeech.Cue c = new SrtSpeech.Cue();
            c.text = isThai() ? "สวัสดีครับ วันนี้มีเรื่องสนุกๆ มาเล่าให้ฟัง รู้ไหมว่าเกิดอะไรขึ้น? โอ้โห! ไม่น่าเชื่อเลย"
                    : "Hello everyone! Do you know what happened today? Wow, I can't believe it!";
            if (emotionCheck.isChecked()) SrtSpeech.applyEmotion(c, isThai());
            String text = SrtSpeech.normalize(c.text, isThai(), SrtSpeech.parseDictionary(dictEdit.getText().toString()));
            tts.setSpeechRate(selectedRate());
            tts.setPitch(selectedPitch());
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sample");
        });
    }

    private void runSrt(Uri src) {
        if (!applyVoiceSettings()) return;
        tts.stop();
        final boolean thai = isThai();
        final float rate = selectedRate();
        final float pitch = selectedPitch();
        final boolean emotion = emotionCheck.isChecked();
        final List<String[]> dict = SrtSpeech.parseDictionary(dictEdit.getText().toString());
        final boolean timed = timedCheck.isChecked();
        final String name = baseName(src) + "_เสียงอ่าน.mp3";

        working = true;
        Converter.cancelled = false;
        setBusy(true);
        progressBar.setProgress(0);
        statusText.setText("กำลังอ่านไฟล์ SRT...");

        new Thread(() -> {
            try {
                List<SrtSpeech.Cue> cues;
                try (InputStream in = getContentResolver().openInputStream(src)) {
                    if (in == null) throw new Exception("เปิดไฟล์ไม่ได้");
                    cues = SrtSpeech.parse(SrtSpeech.readText(in));
                }
                for (SrtSpeech.Cue c : cues) {
                    if (emotion) SrtSpeech.applyEmotion(c, thai);
                    c.text = SrtSpeech.normalize(c.text, thai, dict);
                }
                List<SrtSpeech.Cue> keep = new ArrayList<>();
                for (SrtSpeech.Cue c : cues) if (!c.text.isEmpty()) keep.add(c);
                cues = keep;
                if (cues.isEmpty()) throw new Exception("ไม่พบข้อความในไฟล์นี้ (ต้องเป็นไฟล์ซับ .srt)");
                final int total = cues.size();
                final List<SrtSpeech.Cue> finalCues = cues;
                Uri result = saveMp3(name, os -> SrtSpeech.generate(tts, getCacheDir(), finalCues, timed, rate, pitch, os,
                        pct -> runOnUiThread(() -> {
                            progressBar.setProgress(pct);
                            statusText.setText("กำลังสร้างเสียงอ่าน: " + name + "\nบรรทัด "
                                    + Math.max(1, pct * total / 100) + "/" + total + " · " + pct + "%");
                        })));
                final int failed = SrtSpeech.failedLines;
                runOnUiThread(() -> {
                    addResult(name, result);
                    statusText.setText("เสร็จแล้ว ✓ " + name + (failed > 0 ? "\n(อ่านไม่ได้ " + failed + " บรรทัด)" : "")
                            + "\nเก็บไว้ที่: " + folderLabel());
                });
            } catch (Converter.CancelledException e) {
                runOnUiThread(() -> statusText.setText("ยกเลิกแล้ว"));
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> { addError(name, msg); statusText.setText("ไม่สำเร็จ"); });
            } finally {
                runOnUiThread(() -> { working = false; setBusy(false); });
            }
        }).start();
    }

    // ---------- ที่เก็บไฟล์ ----------
    private void chooseFolder() {
        if (working) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (ActivityNotFoundException e) {
            toast("เครื่องนี้เปิดหน้าเลือกโฟลเดอร์ไม่ได้");
        }
    }

    private Uri getTreeUri() {
        String s = prefs.getString(PREF_TREE, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        // ถ้าสิทธิ์หลุด (เช่น โฟลเดอร์ถูกลบ) กลับไปใช้ที่เดิม
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        }
        return null;
    }

    private String folderLabel() {
        Uri tree = getTreeUri();
        if (tree == null) return "Music/" + FOLDER + " (ค่าเริ่มต้น)";
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        String vol = colon >= 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        if (path.isEmpty()) path = "(หน้าแรกของที่เก็บ)";
        return "primary".equals(vol) || vol.isEmpty() ? path : "SD card/" + path;
    }

    private void updateFolderUi() {
        if (folderText == null) return;
        folderText.setText("📁 " + folderLabel());
        resetFolderButton.setVisibility(getTreeUri() == null ? View.GONE : View.VISIBLE);
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
            toast("ไฟล์อยู่ที่: " + folderLabel());
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
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
