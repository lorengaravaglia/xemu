/* Minimal config-host.h for Android */
#define CONFIG_LINUX 1
#define CONFIG_POSIX 1
#define CONFIG_XEMU 1
#define CONFIG_XBOX 1
#define CONFIG_AUDIO_DRV_LIST "default"

/* OpenGL Support */
#define CONFIG_OPENGL 1
#define CONFIG_EPOXY 1

/* Tell osdep.h that the system provides these */
#define HAVE_SYS_UIO_H 1
#define HAVE_STRUCT_IOVEC 1
#define CONFIG_IOVEC 1

#define HAVE_UTIMENSAT 1
#define HAVE_COPY_FILE_RANGE 1
#define HAVE_MLOCKALL 1
/* #undef HAVE_STRCHRNUL */
#define HAVE_MEMFD 1
#define HAVE_SIGEV_THREAD_ID 1
#define HAVE_DRM_H 1
#define HAVE_GETRANDOM 1
#define HAVE_ACCEPT4 1
#define HAVE_INOTIFY 1
#define HAVE_INOTIFY_INIT1 1
#define HAVE_MADVISE 1
#define HAVE_POSIX_MADVISE 1
#define HAVE_POSIX_MEMALIGN 1
#define HAVE_POSIX_FALLOCATE 1
#define HAVE_PREADV 1
#define HAVE_SENDFILE 1
#define HAVE_SYNC_FILE_RANGE 1
#define HAVE_SPLICE 1
#define HAVE_TEE 1
#define HAVE_VMSPLICE 1
#define HAVE_OPENPTY 1
#define HAVE_STRSIGNAL 1
#define HAVE_MALLOC_TRIM 1
#define HAVE_STATX 1
#define HAVE_FS_IOC_GETFLAGS 1

#define QEMU_VERSION "10.2.0"
#define QEMU_VERSION_MAJOR 10
#define QEMU_VERSION_MINOR 2
#define QEMU_VERSION_MICRO 0

#define CONFIG_SIGALTSTACK_COROUTINE 1
/* #undef CONFIG_UCONTEXT_COROUTINE */
