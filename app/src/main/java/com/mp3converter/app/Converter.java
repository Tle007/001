package com.mp3converter.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** ถอดเสียงจากวิดีโอด้วยตัวถอดรหัสของ Android แล้วเข้ารหัสเป็น MP3 ด้วย LAME ทีละช่วง (ไม่กินแรมแม้ไฟล์ยาว) */
final class Converter {

    interface Listener { void onProgress(int percent); }

    static final class NoAudioException extends IOException {
        NoAudioException() { super("ไฟล์นี้ไม่มีเสียง"); }
    }

    static final class CancelledException extends IOException {
        CancelledException() { super("ยกเลิกแล้ว"); }
    }

    static volatile boolean cancelled = false;

    private Converter() {}

    static void convert(Context ctx, Uri input, OutputStream out, int kbps, Listener listener) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        long lame = 0;
        try {
            extractor.setDataSource(ctx, input, null);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; format = f; break; }
            }
            if (track < 0) throw new NoAudioException();
            extractor.selectTrack(track);

            String mime = format.getString(MediaFormat.KEY_MIME);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            short[] pcm = new short[8192];
            byte[] mp3 = new byte[16384];
            int outChannels = 0;
            int lastPercent = -1;

            while (!outputDone) {
                if (cancelled) throw new CancelledException();

                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIndex);
                        int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = decoder.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = decoder.getOutputBuffer(outIndex);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob = ob.slice().order(ByteOrder.LITTLE_ENDIAN);

                        int n;
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBuffer fb = ob.asFloatBuffer();
                            n = fb.remaining();
                            if (pcm.length < n) pcm = new short[n];
                            for (int i = 0; i < n; i++) {
                                float v = fb.get() * 32767f;
                                pcm[i] = (short) Math.max(-32768f, Math.min(32767f, v));
                            }
                        } else {
                            ShortBuffer sb = ob.asShortBuffer();
                            n = sb.remaining();
                            if (pcm.length < n) pcm = new short[n];
                            sb.get(pcm, 0, n);
                        }

                        int ch = Math.max(1, channels);
                        int frames = n / ch;

                        if (lame == 0) {
                            outChannels = ch >= 2 ? 2 : 1;
                            lame = LameEncoder.nativeInit(sampleRate, outChannels, kbps);
                            if (lame == 0) throw new IOException("เริ่มตัวแปลง MP3 ไม่ได้ (" + sampleRate + " Hz)");
                        }

                        // เสียงรอบทิศทาง (5.1 ฯลฯ) → รวมเป็นสเตอริโอ: ซ้าย/ขวา + เสียงกลาง
                        if (ch > 2) {
                            for (int f = 0; f < frames; f++) {
                                int base = f * ch;
                                int c = ch >= 3 ? pcm[base + 2] : 0;
                                int l = (int) (pcm[base] * 0.7f + c * 0.5f);
                                int r = (int) (pcm[base + 1] * 0.7f + c * 0.5f);
                                pcm[f * 2] = (short) Math.max(-32768, Math.min(32767, l));
                                pcm[f * 2 + 1] = (short) Math.max(-32768, Math.min(32767, r));
                            }
                        }

                        int need = (int) (1.25 * frames) + 7200;
                        if (mp3.length < need) mp3 = new byte[need];
                        int written = LameEncoder.nativeEncode(lame, pcm, frames, outChannels, mp3);
                        if (written < 0) throw new IOException("แปลงเสียงผิดพลาด (" + written + ")");
                        if (written > 0) out.write(mp3, 0, written);

                        if (durationUs > 0) {
                            int pct = (int) Math.min(99, Math.max(0, info.presentationTimeUs * 100 / durationUs));
                            if (pct != lastPercent) { lastPercent = pct; listener.onProgress(pct); }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }

            if (lame == 0) throw new NoAudioException();
            if (mp3.length < 7200) mp3 = new byte[7200];
            int tail = LameEncoder.nativeFlush(lame, mp3);
            if (tail > 0) out.write(mp3, 0, tail);
            out.flush();
            listener.onProgress(100);
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                decoder.release();
            }
            extractor.release();
            if (lame != 0) LameEncoder.nativeClose(lame);
        }
    }
}
