package com.mp3converter.app;

final class LameEncoder {
    static { System.loadLibrary("mp3lame"); }
    private LameEncoder() {}
    static native long nativeInit(int sampleRate, int channels, int kbps);
    static native int nativeEncode(long handle, short[] pcm, int frames, int channels, byte[] out);
    static native int nativeFlush(long handle, byte[] out);
    static native void nativeClose(long handle);
}
