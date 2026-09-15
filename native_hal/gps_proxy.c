#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "E108GpsProxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define HARDWARE_MODULE_TAG 0x48574d54u /* 'HWMT' */
#define HARDWARE_DEVICE_TAG 0x48574454u /* 'HWDT' */
#define GPS_CAPABILITY_MEASUREMENTS 64u
#define GPS_MEASUREMENT_INTERFACE "gps_measurement"
#define GPS_MEASUREMENT_OPERATION_SUCCESS 0
#define GPS_MEASUREMENT_ERROR_ALREADY_INIT (-100)
#define GPS_MEASUREMENT_ERROR_GENERIC (-101)
#define STOCK_HAL_PATH "/vendor/lib64/hw/gps.stock.so"

struct hw_module_t;
struct hw_device_t;

typedef struct hw_module_methods_t {
    int (*open)(const struct hw_module_t* module, const char* id, struct hw_device_t** device);
} hw_module_methods_t;

typedef struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char* id;
    const char* name;
    const char* author;
    struct hw_module_methods_t* methods;
    void* dso;
    uint64_t reserved[25];
} hw_module_t;

typedef struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    struct hw_module_t* module;
    uint64_t reserved[12];
    int (*close)(struct hw_device_t* device);
} hw_device_t;

/* Android legacy GPS callback table. Pointer-sized placeholder types are used for callbacks
 * we simply pass through unchanged. The layout matches hardware/libhardware gps.h. */
typedef struct GpsCallbacks {
    size_t size;
    void* location_cb;
    void* status_cb;
    void* sv_status_cb;
    void* nmea_cb;
    void (*set_capabilities_cb)(uint32_t capabilities);
    void* acquire_wakelock_cb;
    void* release_wakelock_cb;
    void* create_thread_cb;
    void* request_utc_time_cb;
    void* set_system_info_cb;
    void* gnss_sv_status_cb;
} GpsCallbacks;

typedef struct GpsInterface {
    size_t size;
    int (*init)(GpsCallbacks* callbacks);
    int (*start)(void);
    int (*stop)(void);
    void (*cleanup)(void);
    int (*inject_time)(int64_t time_ms, int64_t time_reference_ms, int uncertainty_ms);
    int (*inject_location)(double latitude, double longitude, float accuracy);
    void (*delete_aiding_data)(uint16_t flags);
    int (*set_position_mode)(int mode, int recurrence, uint32_t min_interval,
                             uint32_t preferred_accuracy, uint32_t preferred_time);
    const void* (*get_extension)(const char* name);
} GpsInterface;

struct gps_device_t {
    struct hw_device_t common;
    const GpsInterface* (*get_gps_interface)(struct gps_device_t* dev);
};

typedef struct GpsMeasurementCallbacks {
    size_t size;
    void* measurement_callback;
    void* gnss_measurement_callback;
} GpsMeasurementCallbacks;

typedef struct GpsMeasurementInterface {
    size_t size;
    int (*init)(GpsMeasurementCallbacks* callbacks);
    void (*close)(void);
} GpsMeasurementInterface;

typedef struct ProxyGpsDevice {
    struct gps_device_t public_dev;
    struct gps_device_t* stock_dev;
} ProxyGpsDevice;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static void* g_stock_handle = NULL;
static const hw_module_t* g_stock_module = NULL;
static const GpsInterface* g_stock_if = NULL;
static GpsInterface g_proxy_if;
static GpsCallbacks g_callbacks_copy;
static void (*g_real_set_capabilities)(uint32_t) = NULL;
static uint32_t g_last_stock_caps = 0;
static GpsMeasurementCallbacks g_meas_callbacks;
static int g_meas_inited = 0;

static int load_stock_module_locked(void) {
    if (g_stock_module) return 0;
    g_stock_handle = dlopen(STOCK_HAL_PATH, RTLD_NOW | RTLD_LOCAL);
    if (!g_stock_handle) {
        LOGE("dlopen %s failed: %s", STOCK_HAL_PATH, dlerror());
        return -ENOENT;
    }
    g_stock_module = (const hw_module_t*)dlsym(g_stock_handle, "HMI");
    if (!g_stock_module || !g_stock_module->methods || !g_stock_module->methods->open) {
        LOGE("stock HMI missing/invalid");
        if (g_stock_handle) dlclose(g_stock_handle);
        g_stock_handle = NULL;
        g_stock_module = NULL;
        return -EINVAL;
    }
    LOGI("stock loaded: id=%s name=%s module_api=0x%04x hal_api=0x%04x",
         g_stock_module->id ? g_stock_module->id : "?",
         g_stock_module->name ? g_stock_module->name : "?",
         g_stock_module->module_api_version, g_stock_module->hal_api_version);
    return 0;
}

static void proxy_set_capabilities(uint32_t caps) {
    g_last_stock_caps = caps;
    uint32_t out = caps | GPS_CAPABILITY_MEASUREMENTS;
    LOGI("capabilities stock=0x%08x -> proxy=0x%08x (MEASUREMENTS added)", caps, out);
    if (g_real_set_capabilities) g_real_set_capabilities(out);
}

static int proxy_gps_init(GpsCallbacks* callbacks) {
    if (!g_stock_if || !g_stock_if->init) return -ENODEV;
    if (!callbacks || callbacks->size < offsetof(GpsCallbacks, set_capabilities_cb) + sizeof(void*)) {
        LOGE("GpsCallbacks invalid size=%zu", callbacks ? callbacks->size : 0u);
        return -EINVAL;
    }
    memset(&g_callbacks_copy, 0, sizeof(g_callbacks_copy));
    size_t n = callbacks->size < sizeof(g_callbacks_copy) ? callbacks->size : sizeof(g_callbacks_copy);
    memcpy(&g_callbacks_copy, callbacks, n);
    g_real_set_capabilities = callbacks->set_capabilities_cb;
    g_callbacks_copy.set_capabilities_cb = proxy_set_capabilities;
    LOGI("GpsInterface.init callbacks_size=%zu stock_if_size=%zu", callbacks->size, g_stock_if->size);
    int rc = g_stock_if->init(&g_callbacks_copy);
    LOGI("stock init rc=%d last_stock_caps=0x%08x", rc, g_last_stock_caps);
    return rc;
}

static int proxy_start(void) {
    return (g_stock_if && g_stock_if->start) ? g_stock_if->start() : -ENODEV;
}
static int proxy_stop(void) {
    return (g_stock_if && g_stock_if->stop) ? g_stock_if->stop() : -ENODEV;
}
static void proxy_cleanup(void) {
    if (g_stock_if && g_stock_if->cleanup) g_stock_if->cleanup();
}
static int proxy_inject_time(int64_t t, int64_t ref, int unc) {
    return (g_stock_if && g_stock_if->inject_time) ? g_stock_if->inject_time(t, ref, unc) : -ENOSYS;
}
static int proxy_inject_location(double lat, double lon, float acc) {
    return (g_stock_if && g_stock_if->inject_location) ? g_stock_if->inject_location(lat, lon, acc) : -ENOSYS;
}
static void proxy_delete_aiding_data(uint16_t flags) {
    if (g_stock_if && g_stock_if->delete_aiding_data) g_stock_if->delete_aiding_data(flags);
}
static int proxy_set_position_mode(int mode, int recurrence, uint32_t min_interval,
                                   uint32_t preferred_accuracy, uint32_t preferred_time) {
    return (g_stock_if && g_stock_if->set_position_mode)
        ? g_stock_if->set_position_mode(mode, recurrence, min_interval, preferred_accuracy, preferred_time)
        : -ENOSYS;
}

/* v0.6.0A intentionally exposes the legacy measurement extension and verifies that the
 * stock HIDL 2.1 adapter accepts it. It does not emit GnssData yet. v0.6.0B will feed
 * E108 MSM7 observations after this ABI path is confirmed on the target head unit. */
static int proxy_measurement_init(GpsMeasurementCallbacks* callbacks) {
    pthread_mutex_lock(&g_lock);
    if (g_meas_inited) {
        pthread_mutex_unlock(&g_lock);
        LOGI("measurement init called twice");
        return GPS_MEASUREMENT_ERROR_ALREADY_INIT;
    }
    if (!callbacks || callbacks->size < sizeof(size_t) + 2 * sizeof(void*)) {
        pthread_mutex_unlock(&g_lock);
        LOGE("measurement callbacks invalid size=%zu", callbacks ? callbacks->size : 0u);
        return GPS_MEASUREMENT_ERROR_GENERIC;
    }
    memset(&g_meas_callbacks, 0, sizeof(g_meas_callbacks));
    size_t n = callbacks->size < sizeof(g_meas_callbacks) ? callbacks->size : sizeof(g_meas_callbacks);
    memcpy(&g_meas_callbacks, callbacks, n);
    g_meas_inited = 1;
    pthread_mutex_unlock(&g_lock);
    LOGI("MEASUREMENT_INTERFACE init OK callbacks_size=%zu gps_cb=%p gnss_cb=%p /dev/ttyE108=%s",
         callbacks->size, callbacks->measurement_callback, callbacks->gnss_measurement_callback,
         access("/dev/ttyE108", F_OK) == 0 ? "present" : "missing");
    return GPS_MEASUREMENT_OPERATION_SUCCESS;
}

static void proxy_measurement_close(void) {
    pthread_mutex_lock(&g_lock);
    g_meas_inited = 0;
    memset(&g_meas_callbacks, 0, sizeof(g_meas_callbacks));
    pthread_mutex_unlock(&g_lock);
    LOGI("MEASUREMENT_INTERFACE close");
}

static const GpsMeasurementInterface g_measurement_if = {
    .size = sizeof(GpsMeasurementInterface),
    .init = proxy_measurement_init,
    .close = proxy_measurement_close,
};

static const void* proxy_get_extension(const char* name) {
    if (name && strcmp(name, GPS_MEASUREMENT_INTERFACE) == 0) {
        const void* stock_meas = NULL;
        if (g_stock_if && g_stock_if->get_extension) stock_meas = g_stock_if->get_extension(name);
        LOGI("get_extension(%s): stock=%p -> E108 proxy measurement=%p", name, stock_meas, &g_measurement_if);
        return &g_measurement_if;
    }
    const void* out = (g_stock_if && g_stock_if->get_extension) ? g_stock_if->get_extension(name) : NULL;
    LOGI("get_extension(%s) -> stock=%p", name ? name : "NULL", out);
    return out;
}

static const GpsInterface* proxy_get_gps_interface(struct gps_device_t* dev) {
    ProxyGpsDevice* p = (ProxyGpsDevice*)dev;
    if (!p || !p->stock_dev || !p->stock_dev->get_gps_interface) return NULL;
    const GpsInterface* stock = p->stock_dev->get_gps_interface(p->stock_dev);
    if (!stock) {
        LOGE("stock get_gps_interface returned NULL");
        return NULL;
    }
    g_stock_if = stock;
    memset(&g_proxy_if, 0, sizeof(g_proxy_if));
    g_proxy_if.size = stock->size;
    g_proxy_if.init = proxy_gps_init;
    g_proxy_if.start = proxy_start;
    g_proxy_if.stop = proxy_stop;
    g_proxy_if.cleanup = proxy_cleanup;
    g_proxy_if.inject_time = proxy_inject_time;
    g_proxy_if.inject_location = proxy_inject_location;
    g_proxy_if.delete_aiding_data = proxy_delete_aiding_data;
    g_proxy_if.set_position_mode = proxy_set_position_mode;
    g_proxy_if.get_extension = proxy_get_extension;
    LOGI("proxy interface ready stock_size=%zu proxy_known_size=%zu", stock->size, sizeof(g_proxy_if));
    return &g_proxy_if;
}

static int proxy_device_close(struct hw_device_t* hwdev) {
    ProxyGpsDevice* p = (ProxyGpsDevice*)hwdev;
    int rc = 0;
    if (p && p->stock_dev && p->stock_dev->common.close) rc = p->stock_dev->common.close(&p->stock_dev->common);
    LOGI("proxy device close rc=%d", rc);
    free(p);
    return rc;
}

static int proxy_open(const struct hw_module_t* module, const char* id, struct hw_device_t** device) {
    (void)module;
    if (!device) return -EINVAL;
    *device = NULL;
    pthread_mutex_lock(&g_lock);
    int lrc = load_stock_module_locked();
    pthread_mutex_unlock(&g_lock);
    if (lrc != 0) return lrc;

    struct hw_device_t* stock_hw = NULL;
    int rc = g_stock_module->methods->open(g_stock_module, id, &stock_hw);
    if (rc != 0 || !stock_hw) {
        LOGE("stock open id=%s rc=%d dev=%p", id ? id : "NULL", rc, stock_hw);
        return rc != 0 ? rc : -ENODEV;
    }

    ProxyGpsDevice* p = (ProxyGpsDevice*)calloc(1, sizeof(*p));
    if (!p) {
        if (stock_hw->close) stock_hw->close(stock_hw);
        return -ENOMEM;
    }
    p->stock_dev = (struct gps_device_t*)stock_hw;
    p->public_dev.common = *stock_hw;
    p->public_dev.common.module = (struct hw_module_t*)&HMI;
    p->public_dev.common.close = proxy_device_close;
    p->public_dev.get_gps_interface = proxy_get_gps_interface;
    *device = &p->public_dev.common;
    LOGI("proxy open OK id=%s stock_dev=%p proxy_dev=%p", id ? id : "NULL", stock_hw, *device);
    return 0;
}

static hw_module_methods_t g_methods = {
    .open = proxy_open,
};

__attribute__((visibility("default")))
hw_module_t HMI = {
    .tag = HARDWARE_MODULE_TAG,
    .module_api_version = 1,
    .hal_api_version = 0,
    .id = "gps",
    .name = "E108 GNSS transparent legacy HAL proxy v0.6.0A",
    .author = "SeaEast/OpenAI development build",
    .methods = &g_methods,
    .dso = NULL,
    .reserved = {0},
};
