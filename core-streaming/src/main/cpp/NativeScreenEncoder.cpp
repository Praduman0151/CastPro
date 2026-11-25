#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <atomic>
#include <cstdint>
#include <limits>
#include <thread>

#define TAG "CastProNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {
struct Engine {
    JavaVM* vm = nullptr;
    jobject listener = nullptr;
    jmethodID callback = nullptr;
    AMediaCodec* codec = nullptr;
    ANativeWindow* input = nullptr;
    std::thread worker;
    std::atomic<bool> running{false};
};

/** Deliver one encoded/codec-config buffer to the Kotlin sender. */
void deliver(Engine* e, JNIEnv* env, const uint8_t* data, size_t size,
             jlong presentationTimeUs, jint flags) {
    if (!data || size == 0 ||
        size > static_cast<size_t>(std::numeric_limits<jsize>::max())) return;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(size));
    if (!bytes) return;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size),
                            reinterpret_cast<const jbyte*>(data));
    env->CallVoidMethod(e->listener, e->callback, bytes, presentationTimeUs, flags);
    env->DeleteLocalRef(bytes);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

/**
 * Forward csd-0/csd-1/csd-2 (SPS/PPS for AVC, VPS/SPS/PPS for HEVC) from the
 * output MediaFormat. Some encoders only advertise parameter sets here and
 * never emit a BUFFER_FLAG_CODEC_CONFIG output buffer. Without them the TV's
 * decoder cannot start on an IDR and shows an empty (YUV-zero green) picture.
 * Kotlin caches this packet and replays it before every keyframe / reconnect.
 */
void forwardCodecConfig(Engine* e, JNIEnv* env) {
    AMediaFormat* format = AMediaCodec_getOutputFormat(e->codec);
    if (!format) return;
    static const char* const kCsdKeys[] = {"csd-0", "csd-1", "csd-2"};
    size_t total = 0;
    for (const char* key : kCsdKeys) {
        void* data = nullptr;
        size_t size = 0;
        if (AMediaFormat_getBuffer(format, key, &data, &size) && data) total += size;
    }
    if (total > 0 &&
        total <= static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(total));
        if (bytes) {
            size_t offset = 0;
            for (const char* key : kCsdKeys) {
                void* data = nullptr;
                size_t size = 0;
                if (AMediaFormat_getBuffer(format, key, &data, &size) && data && size > 0) {
                    env->SetByteArrayRegion(bytes, static_cast<jsize>(offset),
                        static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
                    offset += size;
                }
            }
            if (!env->ExceptionCheck()) {
                // Native callback bit 0 is the CastPro codec-config marker.
                env->CallVoidMethod(e->listener, e->callback, bytes,
                                    static_cast<jlong>(0), static_cast<jint>(1));
                LOGI("Forwarded codec config from output format: %zu bytes", total);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(bytes);
        }
    }
    AMediaFormat_delete(format);
}

void drain(Engine* e) {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (e->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (e->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    AMediaCodecBufferInfo info{};
    while (e->running.load()) {
        ssize_t index = AMediaCodec_dequeueOutputBuffer(e->codec, &info, 10000);
        if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            forwardCodecConfig(e, env);
            continue;
        }
        if (index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
        if (index >= 0) {
            size_t capacity = 0;
            uint8_t* data = AMediaCodec_getOutputBuffer(e->codec, index, &capacity);
            if (data && info.size > 0 && info.offset >= 0 &&
                static_cast<size_t>(info.offset + info.size) <= capacity) {
                jint flags = 0;
                // Use the raw MediaCodec flag values: the NDK keyframe enum is
                // only declared for API 34+ while the value is stable at 1.
                if (info.flags & 2) flags |= 1;  // CODEC_CONFIG -> CastPro config
                if (info.flags & 1) flags |= 2;  // KEY_FRAME    -> CastPro IDR
                deliver(e, env, data + info.offset, info.size,
                        static_cast<jlong>(info.presentationTimeUs), flags);
            }
            AMediaCodec_releaseOutputBuffer(e->codec, index, false);
        }
    }
    if (attached) e->vm->DetachCurrentThread();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_castpro_core_streaming_encoder_NativeScreenEncoder_nativeStart(
    JNIEnv* env, jobject self, jstring mimeString, jint width, jint height,
    jint fps, jint bitrate) {
    const char* mime = env->GetStringUTFChars(mimeString, nullptr);
    if (!mime) return 0;
    Engine* e = new Engine();
    env->GetJavaVM(&e->vm);
    e->listener = env->NewGlobalRef(self);
    jclass cls = env->GetObjectClass(self);
    e->callback = env->GetMethodID(cls, "onNativeEncoded", "([BJI)V");
    env->DeleteLocalRef(cls);
    e->codec = AMediaCodec_createEncoderByType(mime);

    AMediaFormat* fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
    env->ReleaseStringUTFChars(mimeString, mime);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, height);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7F000789);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, bitrate);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, fps);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
    AMediaFormat_setInt32(fmt, "low-latency", 1);
    AMediaFormat_setInt32(fmt, "priority", 0);
    AMediaFormat_setInt32(fmt, "max-bframes", 0);
    AMediaFormat_setInt32(fmt, "bitrate-mode", 1);
    // Put VPS/SPS/PPS in front of every sync frame as a second line of
    // defence for receivers that join mid-stream (matches the Kotlin path).
    AMediaFormat_setInt32(fmt, "prepend-header-to-sync-frames", 1);
    media_status_t status = e->codec ? AMediaCodec_configure(
        e->codec, fmt, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE) : AMEDIA_ERROR_UNKNOWN;
    AMediaFormat_delete(fmt);
    if (!e->codec || !e->callback || status != AMEDIA_OK ||
        AMediaCodec_createInputSurface(e->codec, &e->input) != AMEDIA_OK ||
        !e->input || AMediaCodec_start(e->codec) != AMEDIA_OK) {
        LOGE("NDK encoder initialization failed: %d", status);
        if (e->input) ANativeWindow_release(e->input);
        if (e->codec) AMediaCodec_delete(e->codec);
        if (e->listener) env->DeleteGlobalRef(e->listener);
        delete e;
        return 0;
    }
    e->running.store(true);
    e->worker = std::thread(drain, e);
    LOGI("NDK encoder started: %s %dx%d@%d", mime, width, height, fps);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_castpro_core_streaming_encoder_NativeScreenEncoder_nativeGetInputSurface(
    JNIEnv* env, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    return (e && e->input) ? ANativeWindow_toSurface(env, e->input) : nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_castpro_core_streaming_encoder_NativeScreenEncoder_nativeRequestKeyFrame(
    JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || !e->codec || !e->running.load()) return;
    AMediaFormat* params = AMediaFormat_new();
    AMediaFormat_setInt32(params, "request-sync", 0);
    AMediaCodec_setParameters(e->codec, params);
    AMediaFormat_delete(params);
}

extern "C" JNIEXPORT void JNICALL
Java_com_castpro_core_streaming_encoder_NativeScreenEncoder_nativeUpdateBitrate(
    JNIEnv*, jobject, jlong handle, jint bitrate) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e || !e->codec || !e->running.load()) return;
    AMediaFormat* params = AMediaFormat_new();
    AMediaFormat_setInt32(params, "video-bitrate", bitrate);
    AMediaCodec_setParameters(e->codec, params);
    AMediaFormat_delete(params);
}

extern "C" JNIEXPORT void JNICALL
Java_com_castpro_core_streaming_encoder_NativeScreenEncoder_nativeStop(
    JNIEnv* env, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>(handle);
    if (!e) return;
    e->running.store(false);
    if (e->worker.joinable()) e->worker.join();
    if (e->codec) { AMediaCodec_stop(e->codec); AMediaCodec_delete(e->codec); }
    if (e->input) ANativeWindow_release(e->input);
    if (e->listener) env->DeleteGlobalRef(e->listener);
    delete e;
}
