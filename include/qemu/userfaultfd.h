/*
 * Linux UFFD-WP support
 *
 * Copyright Virtuozzo GmbH, 2020
 *
 * Authors:
 *  Andrey Gruzdev   <andrey.gruzdev@virtuozzo.com>
 *
 * This work is licensed under the terms of the GNU GPL, version 2 or
 * later.  See the COPYING file in the top-level directory.
 */

#ifndef USERFAULTFD_H
#define USERFAULTFD_H

#ifdef CONFIG_LINUX

#include "exec/hwaddr.h"
#include <linux/userfaultfd.h>

/**
 * uffd_open(): Open an userfaultfd handle for current context.
 *
 * @flags: The flags we want to pass in when creating the handle.
 *
 * Returns: the uffd handle if >=0, or <0 if error happens.
 */
int uffd_open(int flags);
int uffd_query_features(uint64_t *features);
int uffd_create_fd(uint64_t features, bool non_blocking);
void uffd_close_fd(int uffd_fd);
int uffd_register_memory(int uffd_fd, void *addr, uint64_t length,
        uint64_t mode, uint64_t *ioctls);
int uffd_unregister_memory(int uffd_fd, void *addr, uint64_t length);
int uffd_change_protection(int uffd_fd, void *addr, uint64_t length,
        bool wp, bool dont_wake);
int uffd_copy_page(int uffd_fd, void *dst_addr, void *src_addr,
        uint64_t length, bool dont_wake);
int uffd_zero_page(int uffd_fd, void *addr, uint64_t length, bool dont_wake);
int uffd_wakeup(int uffd_fd, void *addr, uint64_t length);
int uffd_read_events(int uffd_fd, struct uffd_msg *msgs, int count);

#else /* CONFIG_LINUX */

#include "qemu/osdep.h"
#include "exec/hwaddr.h"

#define UFFD_API 0
#define UFFDIO_API 0
#define UFFDIO_REGISTER 0
#define UFFDIO_UNREGISTER 0
#define UFFDIO_WAKE 0
#define UFFDIO_COPY 0
#define UFFDIO_ZEROPAGE 0
#define UFFDIO_WRITEPROTECT 0
#define UFFDIO_REGISTER_MODE_WP 0
#define UFFD_FEATURE_PAGEFAULT_FLAG_WP 0
#define _UFFDIO_WRITEPROTECT 0
#define _UFFDIO_REGISTER 0
#define _UFFDIO_UNREGISTER 0

struct uffdio_api { uint64_t api; uint64_t features; uint64_t ioctls; };
struct uffd_msg {
    uint8_t event;
    union {
        struct {
            uint64_t flags;
            uint64_t address;
        } pagefault;
    } arg;
};

static inline int uffd_open(int flags) { return -1; }
static inline int uffd_query_features(uint64_t *features) { return -1; }
static inline int uffd_create_fd(uint64_t features, bool non_blocking) { return -1; }
static inline void uffd_close_fd(int uffd_fd) {}
static inline int uffd_register_memory(int uffd_fd, void *addr, uint64_t length,
        uint64_t mode, uint64_t *ioctls) { return -1; }
static inline int uffd_unregister_memory(int uffd_fd, void *addr, uint64_t length) { return -1; }
static inline int uffd_change_protection(int uffd_fd, void *addr, uint64_t length,
        bool wp, bool dont_wake) { return -1; }
static inline int uffd_copy_page(int uffd_fd, void *dst_addr, void *src_addr,
        uint64_t length, bool dont_wake) { return -1; }
static inline int uffd_zero_page(int uffd_fd, void *addr, uint64_t length, bool dont_wake) { return -1; }
static inline int uffd_wakeup(int uffd_fd, void *addr, uint64_t length) { return -1; }
static inline int uffd_read_events(int uffd_fd, struct uffd_msg *msgs, int count) { return -1; }

#endif /* CONFIG_LINUX */

#endif /* USERFAULTFD_H */
