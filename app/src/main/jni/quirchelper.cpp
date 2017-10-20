#include <jni.h>
#include <string>
#include <assert.h>
#include <android/log.h>
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOG_TAG "QuircHelp-JNI"
#include "quirc.h"

static const char* CLS_PATH_QRCODE = "com.lakxtab.android.quircdemo.QuircHelper$Qrcode";

static struct quirc* gQr = NULL;
static jclass gQrCode_cls;
static jmethodID gQrCode_constructorID;
static jclass gList_cls;
static jmethodID gList_add_methodID;

//// Utility methods ////
bool hasException(JNIEnv* env)
{
    if (env->ExceptionCheck() != 0)
    {
        ALOGE("*** Uncaught exception returned from Java call! ***");
        env->ExceptionDescribe();
        return true;
    }
    return false;
}

jclass makeGlobalRef(JNIEnv* env, const char classname[])
{
    jclass c = env->FindClass(classname);
    if (c == NULL)
        return NULL;
    return (jclass)env->NewGlobalRef(c);
}
/////////////////////////
extern "C"
jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env;
    jint result = -1;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK)
    {
        ALOGE("GetEnv failed!!");
        return result;
    }
    // Preload all classes and methods ID which we need.
    gQrCode_cls = makeGlobalRef(env, CLS_PATH_QRCODE);
    assert(gQrCode_cls);
    gQrCode_constructorID =
            env->GetMethodID(gQrCode_cls, "<init>", "(IIII[BII)V");
    assert(gQrCode_constructorID);
    hasException(env);
    gList_cls = makeGlobalRef(env, "java/util/List");
    gList_add_methodID =
            env->GetMethodID(gList_cls, "add", "(Ljava/lang/Object;)Z");
    hasException(env);
    return JNI_VERSION_1_6;
}

extern "C"
void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK)
    {
        ALOGE("JNI_OnUnload() GetEnv failed!!");
        return;
    } else
    {
        if (gQrCode_cls != NULL)
            env->DeleteGlobalRef(gQrCode_cls);
        if (gList_cls != NULL)
            env->DeleteGlobalRef(gList_cls);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lakxtab_android_quircdemo_QuircHelper_prepare(JNIEnv *env, jobject instance, jint width,
                                                       jint height)
{
    if (gQr != NULL)
    {
        ALOGD("Already prepare, destroy old one.");
        quirc_destroy(gQr);
    }
    gQr = quirc_new();
    if (!gQr)
    {
        ALOGE("Failed to allocate memory");
        return JNI_FALSE;
    }
    if (quirc_resize(gQr, width, height) < 0)
    {
        ALOGE("Failed to allocate frame memory");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lakxtab_android_quircdemo_QuircHelper_resizeFrame(JNIEnv *env, jobject instance,
                                                           jint width, jint height)
{
    if (gQr == NULL)
    {
        ALOGE("Failed, already release() or not prepare() yet.");
        return JNI_FALSE;
    }
    if (quirc_resize(gQr, width, height) < 0)
    {
        ALOGE("Failed to allocate frame memory");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lakxtab_android_quircdemo_QuircHelper_detectGrids(JNIEnv *env, jobject instance,
                                                           jbyteArray frame_, jboolean writeback)
{
    if (gQr == NULL)
    {
        ALOGE("Failed, already release() or not prepare() yet.");
        return JNI_ERR;
    }
    int len = env->GetArrayLength(frame_);
    jbyte* frame = env->GetByteArrayElements(frame_, JNI_FALSE);

    uint8_t* image;
    int w, h;
    image = quirc_begin(gQr, &w, &h);
    if (len > (h*w))
    {
        ALOGE("Frame size to big.");
        return JNI_ERR;
    }
    memcpy(image, frame, len);
    quirc_end(gQr);
    if (writeback == JNI_TRUE)
    {
        memcpy(frame, image, len);
    }
    env->ReleaseByteArrayElements(frame_, frame, 0);
    int count = quirc_count(gQr);
    return count;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lakxtab_android_quircdemo_QuircHelper_decodeLastFrame(JNIEnv *env, jobject instance,
                                                               jobject respList)
{
    if (gQr == NULL)
    {
        ALOGE("Failed, already release() or not prepare() yet.");
        return JNI_ERR;
    }
    int count = quirc_count(gQr);
    if (count <= 0)
        return JNI_ERR;
    if (respList == NULL || env->IsInstanceOf(respList, gList_cls))
    {
        return JNI_ERR;
    }
    int findCount = 0;
    for (int i=0; i<count; ++i)
    {
        struct quirc_code code;
        struct quirc_data data;

        quirc_extract(gQr, i, &code);
        if (!quirc_decode(&code, &data))
        {
            ++findCount;
            ALOGD("==> %s", data.payload);
            ALOGD("    Version: %d, ECC: %c, Mask: %d, Type: %d",
                data.version, "MLHQ"[data.ecc_level], data.mask, data.data_type);
            jbyteArray payload = env->NewByteArray(data.payload_len);
            env->SetByteArrayRegion(payload, 0, data.payload_len,
                                    reinterpret_cast<jbyte*>(data.payload));
            jobject obj = env->NewObject(gQrCode_cls, gQrCode_constructorID, data.version,
                                         data.ecc_level, data.mask, data.data_type,
                                         payload, data.payload_len, data.eci);
            env->DeleteLocalRef(payload);
            env->CallBooleanMethod(respList, gList_add_methodID, obj);
            env->DeleteLocalRef(obj);
        }
    }
    return findCount;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lakxtab_android_quircdemo_QuircHelper_release(JNIEnv *env, jobject instance)
{
    if (gQr != NULL)
        quirc_destroy(gQr);
    gQr = NULL;
}