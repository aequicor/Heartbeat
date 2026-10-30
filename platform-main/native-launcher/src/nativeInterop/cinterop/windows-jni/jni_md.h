#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_
/* Windows JNI ABI, including when the native launcher is cross-compiled on macOS. */
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall
typedef int jint;
typedef long long jlong;
typedef signed char jbyte;
#endif
