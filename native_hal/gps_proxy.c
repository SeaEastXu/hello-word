#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "E108GpsProxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define HARDWARE_MODULE_TAG 0x48574d54u /* 'HWMT' */
#define HARDWARE_DEVICE_TAG 0x48574454u /* 'HWDT' */
#define GPS_CAPABILITY_MEASUREMENTS 64u
#define GPS_MEASUREMENT_INTERFACE "gps_measurement"
#define GPS_MEASUREMENT_OPERATION_SUCCESS 0
#define GPS_MEASUREMENT_ERROR_ALREADY_INIT (-100)
#define GPS_MEASUREMENT_ERROR_GENERIC (-101)
#define STOCK_HAL_PATH "/vendor/lib64/hw/gps.stock.so"

/*
 * UIS7870 / Unisoc legacy GPS ABI notes (verified from the working v8.0.5
 * reference binary and the target stock HAL):
 *
 *   GpsInterface.size == 88 bytes on arm64.
 *
 * The vendor ABI inserts inject_best_location between inject_location and
 * delete_aiding_data. AOSP's old public GpsInterface is only 80 bytes.
 * v0.6.0A accidentally returned an 80-byte table while advertising size=88,
 * shifting delete_aiding_data/set_position_mode/get_extension and leaving the
 * real get_extension slot at +80 invalid. UnisocGnss::getExtensionGnssConfiguration
 * then called through a NULL/invalid pointer and crashed the GNSS service.
 *
 * A2 treats the stock table as opaque bytes: copy all 88 bytes verbatim and
 * patch ONLY init (+8) and get_extension (+80). Every vendor-private slot is
 * retained exactly as shipped by the stock HAL.
 */
#define TARGET_GPS_IF_SIZE          88u
#define GPS_IF_OFF_INIT              8u
#define GPS_IF_OFF_GET_EXTENSION    80u
#define MAX_GPS_IF_SIZE            256u
#define GPS_CALLBACKS_MIN_SIZE      48u
#define GPS_CB_OFF_SET_CAPS         40u
#define MAX_CALLBACKS_SIZE         256u

struct hw_module_t;
struct hw_device_t;
struct gps_device_t;
extern struct hw_module_t HMI;

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

/* Return type is intentionally opaque: the target vendor GpsInterface is 88B. */
typedef const void* (*get_gps_interface_fn)(struct gps_device_t* dev);

struct gps_device_t {
    struct hw_device_t common;
    get_gps_interface_fn get_gps_interface;
};

typedef int (*gps_init_fn)(void* callbacks);
typedef const void* (*gps_get_extension_fn)(const char* name);
typedef void (*gps_set_capabilities_fn)(uint32_t capabilities);

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

static const uint8_t* g_stock_if_raw = NULL;
static size_t g_stock_if_size = 0;
static gps_init_fn g_stock_init = NULL;
static gps_get_extension_fn g_stock_get_extension = NULL;
static uint8_t g_proxy_if[MAX_GPS_IF_SIZE];

/* The stock HAL may retain this pointer after init, so keep the clone alive. */
static uint8_t* g_callbacks_clone = NULL;
static size_t g_callbacks_clone_size = 0;
static gps_set_capabilities_fn g_real_set_capabilities = NULL;
static uint32_t g_last_stock_caps = 0;

static GpsMeasurementCallbacks g_meas_callbacks;
static int g_meas_inited = 0;

static size_t raw_size_field(const void* p) {
    size_t n = 0;
    if (p) memcpy(&n, p, sizeof(n));
    return n;
}

static void* raw_get_ptr(const void* p, size_t off) {
    void* out = NULL;
    memcpy(&out, (const uint8_t*)p + off, sizeof(out));
    return out;
}

static void raw_set_ptr(void* p, size_t off, const void* fn) {
    memcpy((uint8_t*)p + off, &fn, sizeof(fn));
}

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
        dlclose(g_stock_handle);
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
    LOGI("capabilities stock=0x%08x -> proxy=0x%08x", caps, out);
    if (g_real_set_capabilities) g_real_set_capabilities(out);
}

static int proxy_gps_init(void* callbacks) {
    if (!g_stock_init) return -ENODEV;
    if (!callbacks) {
        LOGE("GpsCallbacks NULL");
        return -EINVAL;
    }

    const size_t cb_size = raw_size_field(callbacks);
    if (cb_size < GPS_CALLBACKS_MIN_SIZE || cb_size > MAX_CALLBACKS_SIZE) {
        LOGE("GpsCallbacks unexpected size=%zu", cb_size);
        return -EINVAL;
    }

    pthread_mutex_lock(&g_lock);
    uint8_t* fresh = (uint8_t*)malloc(cb_size);
    if (!fresh) {
        pthread_mutex_unlock(&g_lock);
        return -ENOMEM;
    }
    memcpy(fresh, callbacks, cb_size);

    /* set_capabilities_cb is the stable AOSP slot at +40 in GpsCallbacks. */
    g_real_set_capabilities = (gps_set_capabilities_fn)raw_get_ptr(callbacks, GPS_CB_OFF_SET_CAPS);
    if (g_real_set_capabilities) {
        raw_set_ptr(fresh, GPS_CB_OFF_SET_CAPS, (const void*)proxy_set_capabilities);
    } else {
        LOGW("GpsCallbacks has no set_capabilities_cb; leaving NULL");
    }

    /* Deliberately keep only the latest clone alive. The stock Unisoc HAL is a
     * singleton and init is expected once; if it re-inits, the new callback set
     * supersedes the old one. */
    uint8_t* old = g_callbacks_clone;
    g_callbacks_clone = fresh;
    g_callbacks_clone_size = cb_size;
    pthread_mutex_unlock(&g_lock);

    LOGI("GpsInterface.init callbacks_size=%zu stock_if_size=%zu", cb_size, g_stock_if_size);
    int rc = g_stock_init(g_callbacks_clone);
    LOGI("stock init rc=%d last_stock_caps=0x%08x", rc, g_last_stock_caps);

    /* Do not free 'old' before stock init succeeds; once init returns, Unisoc's
     * singleton should have switched to the new callback table. */
    if (old && old != g_callbacks_clone) free(old);
    return rc;
}

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

    LOGI("MEASUREMENT_INTERFACE init OK callbacks_size=%zu gps_cb=%p gnss_cb=%p ttyE108=%s",
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
        const void* stock_meas = g_stock_get_extension ? g_stock_get_extension(name) : NULL;
        LOGI("get_extension(%s): stock=%p -> E108 measurement=%p",
             name, stock_meas, &g_measurement_if);
        return &g_measurement_if;
    }

    const void* out = g_stock_get_extension ? g_stock_get_extension(name) : NULL;
    LOGI("get_extension(%s) -> stock=%p", name ? name : "NULL", out);
    return out;
}

static const void* proxy_get_gps_interface(struct gps_device_t* dev) {
    ProxyGpsDevice* p = (ProxyGpsDevice*)dev;
    if (!p || !p->stock_dev || !p->stock_dev->get_gps_interface) return NULL;

    const void* stock = p->stock_dev->get_gps_interface(p->stock_dev);
    if (!stock) {
        LOGE("stock get_gps_interface returned NULL");
        return NULL;
    }

    const size_t stock_size = raw_size_field(stock);
    if (stock_size != TARGET_GPS_IF_SIZE) {
        LOGE("unsupported stock GpsInterface size=%zu expected=%u; refusing unsafe proxy",
             stock_size, TARGET_GPS_IF_SIZE);
        return NULL;
    }
    if (stock_size > sizeof(g_proxy_if)) return NULL;

    pthread_mutex_lock(&g_lock);
    g_stock_if_raw = (const uint8_t*)stock;
    g_stock_if_size = stock_size;
    memcpy(g_proxy_if, stock, stock_size);

    g_stock_init = (gps_init_fn)raw_get_ptr(stock, GPS_IF_OFF_INIT);
    g_stock_get_extension = (gps_get_extension_fn)raw_get_ptr(stock, GPS_IF_OFF_GET_EXTENSION);
    if (!g_stock_init || !g_stock_get_extension) {
        LOGE("stock critical slots invalid init=%p get_extension=%p",
             (void*)g_stock_init, (void*)g_stock_get_extension);
        pthread_mutex_unlock(&g_lock);
        return NULL;
    }

    /* Preserve every other vendor slot byte-for-byte, especially the Unisoc
     * inject_best_location slot at +56. */
    raw_set_ptr(g_proxy_if, GPS_IF_OFF_INIT, (const void*)proxy_gps_init);
    raw_set_ptr(g_proxy_if, GPS_IF_OFF_GET_EXTENSION, (const void*)proxy_get_extension);
    pthread_mutex_unlock(&g_lock);

    LOGI("ABI88 proxy ready size=%zu init(stock=%p proxy=%p) get_ext(stock=%p proxy=%p) best_location_slot=%p",
         stock_size,
         (void*)g_stock_init, (void*)proxy_gps_init,
         (void*)g_stock_get_extension, (void*)proxy_get_extension,
         raw_get_ptr(stock, 56));
    return g_proxy_if;
}

static int proxy_device_close(struct hw_device_t* hwdev) {
    ProxyGpsDevice* p = (ProxyGpsDevice*)hwdev;
    int rc = 0;
    if (p && p->stock_dev && p->stock_dev->common.close) {
        rc = p->stock_dev->common.close(&p->stock_dev->common);
    }
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
    .name = "E108 GNSS ABI88 transparent legacy HAL proxy v0.6.0A2",
    .author = "SeaEast/OpenAI development build",
    .methods = &g_methods,
    .dso = NULL,
    .reserved = {0},
};
