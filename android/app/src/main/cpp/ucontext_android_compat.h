/*
 * Android NDK compatibility header for ucontext functions.
 * getcontext/makecontext/swapcontext exist in Android bionic but are not
 * declared in NDK 29+ headers (deprecated POSIX). Declare them explicitly
 * so we can call them.
 */
#pragma once
#include <ucontext.h>

#ifdef __ANDROID__
#ifdef __cplusplus
extern "C" {
#endif
extern int getcontext(ucontext_t *ucp);
extern void makecontext(ucontext_t *ucp, void (*func)(), int argc, ...);
extern int swapcontext(ucontext_t *oucp, const ucontext_t *ucp);
#ifdef __cplusplus
}
#endif
#endif /* __ANDROID__ */
