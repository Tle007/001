#include <jni.h>
#include <stdint.h>
#include "lame.h"

JNIEXPORT jlong JNICALL
Java_com_mp3converter_app_LameEncoder_nativeInit(JNIEnv *env, jclass cls, jint sampleRate, jint channels, jint kbps) {
    lame_global_flags *g = lame_init();
    if (!g) return 0;
    lame_set_in_samplerate(g, sampleRate);
    lame_set_num_channels(g, channels);
    lame_set_mode(g, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(g, vbr_off);
    lame_set_brate(g, kbps);
    lame_set_quality(g, 5);
    lame_set_bWriteVbrTag(g, 0);
    if (lame_init_params(g) < 0) { lame_close(g); return 0; }
    return (jlong)(intptr_t)g;
}

JNIEXPORT jint JNICALL
Java_com_mp3converter_app_LameEncoder_nativeEncode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm,
                                                    jint frames, jint channels, jbyteArray out) {
    lame_global_flags *g = (lame_global_flags *)(intptr_t)h;
    jshort *p = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize outSize = (*env)->GetArrayLength(env, out);
    int r;
    if (channels == 2) r = lame_encode_buffer_interleaved(g, p, frames, (unsigned char *)o, outSize);
    else r = lame_encode_buffer(g, p, p, frames, (unsigned char *)o, outSize);
    (*env)->ReleaseShortArrayElements(env, pcm, p, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_mp3converter_app_LameEncoder_nativeFlush(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    lame_global_flags *g = (lame_global_flags *)(intptr_t)h;
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    jsize outSize = (*env)->GetArrayLength(env, out);
    int r = lame_encode_flush(g, (unsigned char *)o, outSize);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);
    return r;
}

JNIEXPORT void JNICALL
Java_com_mp3converter_app_LameEncoder_nativeClose(JNIEnv *env, jclass cls, jlong h) {
    lame_close((lame_global_flags *)(intptr_t)h);
}
