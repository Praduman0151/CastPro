#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <atomic>
#include <thread>

#define TAG "CastProNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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
        if (index >= 0) {
            size_t capacity = 0;
            uint8_t* data = AMediaCodec_getOutputBuffer(e->codec, index, &capacity);
            if (data && info.size > 0 && info.offset >= 0 &&
                static_cast<size_t>(info.offset + info.size) <= capacity) {
                jbyteArray bytes = env->NewByteArray(info.size);
                if (bytes) {
                    env->SetByteArrayRegion(bytes, 0, info.size,
                        reinterpret_cast<const jbyte*>(data + info.offset));
                    jint flags = 0;
                    if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) flags |= 1;
                    if (info.flags & 1) flags |= 2;
                    env->CallVoidMethod(e->listener, e->callback, bytes,
                        static_cast<jlong>(info.presentationTimeUs), flags);
                    env->DeleteLocalRef(bytes);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
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
