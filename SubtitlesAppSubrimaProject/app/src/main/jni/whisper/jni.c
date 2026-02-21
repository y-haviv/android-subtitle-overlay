#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

// Macro to mark unused parameters to avoid compiler warnings
#define UNUSED(x) (void)(x)
// Tag used for Android log messages
#define TAG "JNI"
// Android log macros for info and warning levels
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

// Returns the minimum of two integers
static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

// Returns the maximum of two integers
static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

// Context for wrapping a Java InputStream in native C.
// Allows native code to read from the InputStream using JNI.
struct input_stream_context {
    size_t offset;           // Number of bytes already read
    JNIEnv *env;             // JNI environment pointer
    jobject thiz;            // Java 'this' object
    jobject input_stream;    // Java InputStream to read from

    jmethodID mid_available; // Method ID for InputStream.available()
    jmethodID mid_read;      // Method ID for InputStream.read(byte[], offset, length)
};


// Reads up to 'read_size' bytes from Java InputStream into 'output' buffer.
// Returns the number of bytes successfully read.
size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}

// Returns true if the Java InputStream has no more bytes to read (i.e., available() <= 0)
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
// No-op cleanup function for InputStream context (currently unused) 
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    struct whisper_context_params p = whisper_context_default_params();
    p.flash_attn = true;
    p.use_gpu    = false;
    context = whisper_init_with_params(&loader, p);
    return (jlong) context;
}

// Reads up to 'read_size' bytes from an AAsset
static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}
// Returns true if end of asset is reached
static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}
// Closes the asset after reading is done
static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

// Loads a Whisper model directly from Android assets using asset manager.
// Returns a pointer to a Whisper context (or NULL on failure).
static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    // → use the proper default params type:
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;              // ❗ Set to true to enable GPU acceleration (if supported)
    cparams.flash_attn = true;            // ❗ Use FlashAttention for faster inference on supported devices
    cparams.dtw_token_timestamps = false; // ❗ Only enable if token-level alignment or language detection is needed

    return whisper_init_with_params(&loader, cparams);
}

JNIEXPORT jlong JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

// JNI function to initialize Whisper context from a file path.
// Called from Java: WhisperLib.initContext(String modelPath)
// Returns a native pointer to the Whisper context as jlong
JNIEXPORT jlong JNICALL 
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    // → Again, correct default params:
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;              // ❗ Enable GPU acceleration if supported and desired
    cparams.flash_attn = true;            // ❗ Enables faster attention computation
    cparams.dtw_token_timestamps = false; // ❗ Enable for advanced token-level alignment use cases

    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path_chars, cparams);

    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong)ctx;
}

// Frees the Whisper context memory created with initContext
// Called from Java: WhisperLib.freeContext(long contextPtr)
JNIEXPORT void JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

// Runs full transcription on a float[] array of audio samples.
// Called from Java: WhisperLib.fullTranscribe(long ctx, int numThreads, float[] audioData)
JNIEXPORT void JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jfloatArray audio_data) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    // The below adapted from the Objective-C iOS sample
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = true;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;             // ❗ If true, will translate to English instead of transcribing
    //params.language = "en";               // ❗ Set known language (e.g., "en", "he") for improved accuracy. Use NULL or "" for auto-detection.
    params.n_threads = num_threads;       // ❗ Set number of threads to use for inference (higher = faster, up to available cores)
    params.offset_ms = 0;
    params.no_context = true;             // ❗ If true, disables history context across transcriptions (recommended for isolated chunks)
    params.single_segment = false;        // ❗ If true, forces transcription into one segment
    //params.duration_ms = (int)((float)audio_data_length / WHISPER_SAMPLE_RATE * 1000.0f);


    whisper_reset_timings(context);

    LOGI("About to run whisper_full");
    if (whisper_full_parallel(context, params, audio_data_arr, audio_data_length, num_threads) != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

// Returns number of segments produced by the last whisper_full() call
// Java: WhisperLib.getTextSegmentCount(long ctx)
JNIEXPORT jint JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

// Returns the transcription text of the segment at the given index.
// Java: WhisperLib.getTextSegment(long ctx, int index)
JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}
// Returns the start time (in ms) of the segment at the given index
JNIEXPORT jlong JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getTextSegmentT0(JNIEnv *env, jobject thiz,jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const int64_t t0 = whisper_full_get_segment_t0(context, index);
    return (jlong)t0;
}
// Returns the end time (in ms) of the segment at the given index
JNIEXPORT jlong JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getTextSegmentT1(JNIEnv *env, jobject thiz,jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const int64_t t1 = whisper_full_get_segment_t1(context, index);
    return (jlong)t1;
}

JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_benchMemcpy(JNIEnv *env, jobject thiz,
                                                        jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);

    return string;
}

JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                            jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);

    return string;
}

/**
 * Returns the language code (e.g. "en", "he") detected after whisper_full().
 * If detection fails or context is null, returns "und".
 *
 * Java: WhisperLib.getDetectedLanguage(long ctx)
 */
JNIEXPORT jstring JNICALL
Java_com_example_subtitles_model_transcription_correction_whisper_lib_WhisperLib_getDetectedLanguage(
        JNIEnv* env, jobject thiz, jlong ctx_ptr) {
    UNUSED(thiz);

    // 1. Null-pointer check
    if (ctx_ptr == 0) {
        return (*env)->NewStringUTF(env, "und");
    }

    struct whisper_context* ctx = (struct whisper_context*)ctx_ptr;

    // 2. Get the last full-run language ID
    const int lang_id = whisper_full_lang_id(ctx);

    // 3. Map ID → short code
    const char* code = whisper_lang_str(lang_id);
    if (code == NULL) {
        code = "und";
    }

    // 4. Return a fresh Java string
    return (*env)->NewStringUTF(env, code);
}


