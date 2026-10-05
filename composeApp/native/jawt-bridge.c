#include <jni.h>
#include <jawt.h>
#include <dlfcn.h>
#include <stdio.h>

// Skiko loads libjawt by path; forward to NIK's statically linked AWT.
JNIEXPORT jboolean JNICALL JAWT_GetAWT(JNIEnv *env, JAWT *awt) {
    typedef jboolean (*GetAWT)(JNIEnv *, JAWT *);
    void *handle = RTLD_MAIN_ONLY;
    GetAWT getAWT = (GetAWT)dlsym(handle, "JAWT_GetAWT");
    if (getAWT == NULL) {
        fprintf(stderr, "Native Image JAWT export unavailable: %s\n", dlerror());
        return JNI_FALSE;
    }
    return getAWT(env, awt);
}
