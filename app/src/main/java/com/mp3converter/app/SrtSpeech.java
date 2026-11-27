package com.mp3converter.app;

import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** อ่านไฟล์ SRT ด้วยเสียงอ่านของเครื่อง (TextToSpeech) แล้วรวมเป็น MP3 ตามเวลาในซับ */
final class SrtSpeech {

    static final class Cue {
        long startMs, endMs;
        String text;
    }

    private SrtSpeech() {}

    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");

    // ---------- อ่านไฟล์ ----------
    static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        byte[] bytes = bos.toByteArray();
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        try {
            CharBuffer cb = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return cb.toString();
        } catch (CharacterCodingException e) {
            // ซับไทยรุ่นเก่ามักเป็นรหัส TIS-620 / Windows-874
            for (String cs : new String[]{"windows-874", "x-windows-874", "TIS-620"}) {
                try { return new String(bytes, Charset.forName(cs)); } catch (Exception ignored) {}
            }
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    static List<Cue> parse(String s) {
        s = s.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = s.split("\n");
        List<Cue> out = new ArrayList<>();
        Cue cur = null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher m = TIME.matcher(line);
            if (m.find()) {
                finish(cur, sb, out);
                cur = new Cue();
                cur.startMs = ms(m, 1);
                cur.endMs = ms(m, 5);
                sb.setLength(0);
            } else if (line.isEmpty()) {
                finish(cur, sb, out);
                cur = null;
                sb.setLength(0);
            } else if (cur != null) {
                // ข้ามเลขลำดับของบรรทัดถัดไป (กรณีไฟล์ไม่มีบรรทัดว่างคั่น)
                boolean nextIsTime = i + 1 < lines.length && TIME.matcher(lines[i + 1]).find();
                if (line.matches("\\d+") && nextIsTime) continue;
                sb.append(line).append(' ');
            }
        }
        finish(cur, sb, out);
        Collections.sort(out, (a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }

    private static void finish(Cue cur, StringBuilder sb, List<Cue> out) {
        if (cur == null) return;
        String t = sb.toString()
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\{[^}]*\\}", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (!t.isEmpty()) {
            cur.text = t;
            out.add(cur);
        }
    }

    private static long ms(Matcher m, int g) {
        String frac = m.group(g + 3);
        while (frac.length() < 3) frac += "0";
        return Long.parseLong(m.group(g)) * 3600000L + Long.parseLong(m.group(g + 1)) * 60000L
                + Long.parseLong(m.group(g + 2)) * 1000L + Long.parseLong(frac);
    }

    // ---------- สร้างเสียง ----------
    private static final class Wav {
        int sampleRate, channels;
        short[] samples;
    }

    static int failedLines = 0;

    static void generate(TextToSpeech tts, File cacheDir, List<Cue> cues, boolean timed, float baseRate,
                         OutputStream out, Converter.Listener listener) throws IOException {
        long lame = 0;
        int sr = 0;
        long pos = 0;
        short[] chunk = new short[8192];
        byte[] mp3 = new byte[(int) (1.25 * chunk.length) + 7200];
        int maxLen = TextToSpeech.getMaxSpeechInputLength();
        failedLines = 0;
        try {
            for (int i = 0; i < cues.size(); i++) {
                if (Converter.cancelled) throw new Converter.CancelledException();
                Cue cue = cues.get(i);
                String text = cue.text.length() > maxLen ? cue.text.substring(0, maxLen) : cue.text;
                File wavFile = new File(cacheDir, "tts_line.wav");

                Wav w = synth(tts, text, wavFile, baseRate);
                if (w == null) { failedLines++; continue; }

                // ถ้าพูดไม่ทันก่อนบรรทัดถัดไป → พูดใหม่ให้เร็วขึ้น (สูงสุด 1.8 เท่า)
                if (timed) {
                    long avail = (i + 1 < cues.size() ? cues.get(i + 1).startMs : cue.endMs) - cue.startMs;
                    long durMs = (long) w.samples.length * 1000L / w.sampleRate / Math.max(1, w.channels);
                    if (avail > 300 && durMs > avail * 1.1) {
                        float rate = Math.min(baseRate * 1.8f, baseRate * durMs / (float) avail);
                        Wav faster = synth(tts, text, wavFile, rate);
                        if (faster != null) w = faster;
                    }
                }

                short[] mono = toMono(w);
                if (lame == 0) {
                    sr = w.sampleRate;
                    lame = LameEncoder.nativeInit(sr, 1, 96);
                    if (lame == 0) throw new IOException("เริ่มตัวแปลง MP3 ไม่ได้ (" + sr + " Hz)");
                }
                if (w.sampleRate != sr) mono = resample(mono, w.sampleRate, sr);

                // เว้นช่วงเงียบให้ตรงเวลาในซับ
                long target = timed ? cue.startMs * sr / 1000L : (i == 0 ? 0 : pos + sr * 4L / 10L);
                java.util.Arrays.fill(chunk, (short) 0);
                while (pos < target) {
                    if (Converter.cancelled) throw new Converter.CancelledException();
                    int n = (int) Math.min(chunk.length, target - pos);
                    write(lame, chunk, n, mp3, out);
                    pos += n;
                }
                for (int off = 0; off < mono.length; off += chunk.length) {
                    int n = Math.min(chunk.length, mono.length - off);
                    System.arraycopy(mono, off, chunk, 0, n);
                    write(lame, chunk, n, mp3, out);
                }
                pos += mono.length;
                listener.onProgress(Math.min(99, (i + 1) * 100 / cues.size()));
            }
            if (lame == 0) throw new IOException("สร้างเสียงอ่านไม่ได้เลย ลองตรวจว่าเครื่องมีเสียงอ่านภาษานี้");
            int tail = LameEncoder.nativeFlush(lame, mp3);
            if (tail > 0) out.write(mp3, 0, tail);
            out.flush();
            listener.onProgress(100);
        } finally {
            if (lame != 0) LameEncoder.nativeClose(lame);
            //noinspection ResultOfMethodCallIgnored
            new File(cacheDir, "tts_line.wav").delete();
        }
    }

    private static void write(long lame, short[] pcm, int n, byte[] mp3, OutputStream out) throws IOException {
        int w = LameEncoder.nativeEncode(lame, pcm, n, 1, mp3);
        if (w < 0) throw new IOException("แปลงเสียงผิดพลาด (" + w + ")");
        if (w > 0) out.write(mp3, 0, w);
    }

    private static Wav synth(TextToSpeech tts, String text, File f, float rate) throws IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        final String id = "u" + System.nanoTime();
        tts.setSpeechRate(rate);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}
            @Override public void onDone(String utteranceId) {
                if (id.equals(utteranceId)) { ok[0] = true; latch.countDown(); }
            }
            @Override public void onError(String utteranceId) {
                if (id.equals(utteranceId)) latch.countDown();
            }
            @Override public void onError(String utteranceId, int errorCode) {
                if (id.equals(utteranceId)) latch.countDown();
            }
        });
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (tts.synthesizeToFile(text, null, f, id) != TextToSpeech.SUCCESS) return null;
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) {
            throw new Converter.CancelledException();
        }
        if (!ok[0] || f.length() <= 44) return null;
        return readWav(f);
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static int le32(byte[] b, int o) { return le16(b, o) | (le16(b, o + 2) << 16); }

    private static Wav readWav(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
        }
        if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return null;
        Wav w = new Wav();
        int bits = 16, p = 12, dataOff = -1, dataLen = 0;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, StandardCharsets.US_ASCII);
            int size = le32(b, p + 4);
            if (id.equals("fmt ")) {
                w.channels = le16(b, p + 10);
                w.sampleRate = le32(b, p + 12);
                bits = le16(b, p + 22);
            } else if (id.equals("data")) {
                dataOff = p + 8;
                dataLen = (size <= 0 || dataOff + size > b.length) ? b.length - dataOff : size;
                break;
            }
            if (size < 0) break;
            p += 8 + size + (size & 1);
        }
        if (dataOff < 0 || w.sampleRate <= 0 || bits != 16) return null;
        if (w.channels <= 0) w.channels = 1;
        int n = dataLen / 2;
        w.samples = new short[n];
        for (int i = 0; i < n; i++) w.samples[i] = (short) le16(b, dataOff + i * 2);
        return w;
    }

    private static short[] toMono(Wav w) {
        if (w.channels == 1) return w.samples;
        int frames = w.samples.length / w.channels;
        short[] m = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < w.channels; c++) sum += w.samples[i * w.channels + c];
            m[i] = (short) (sum / w.channels);
        }
        return m;
    }

    private static short[] resample(short[] in, int from, int to) {
        int n = (int) ((long) in.length * to / from);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double src = (double) i * from / to;
            int a = (int) src;
            int b = Math.min(a + 1, in.length - 1);
            double t = src - a;
            out[i] = (short) (in[Math.min(a, in.length - 1)] * (1 - t) + in[b] * t);
        }
        return out;
    }
}
