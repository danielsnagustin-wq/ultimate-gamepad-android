/*
 * Mando virtual vía /dev/uinput — implementación nativa (Fase 2).
 *
 * Corre en el proceso shell (uid 2000) del UserService de Shizuku:
 * solo ese proceso puede abrir /dev/uinput. La app normal NUNCA
 * llama a esto directamente.
 *
 * Dispositivo expuesto (ver GamepadProfiles en :core, una sola
 * fuente de verdad): depende del perfil activo (profileId del
 * byte 28 del protocolo): Xbox 360 ("Xbox 360 Controller",
 * 0x045E/0x028E), Xbox One (0x045E/0x02DD), DualShock 4
 * ("Wireless Controller", 0x054C/0x05C4), DualSense
 * ("DualSense Wireless Controller", 0x054C/0x0CE6) o genérico
 * ("Generic Gamepad", 0x1234/0x5678); bustype BUS_USB, version
 * 0x0114. Solo botones y ejes de mando; PROHIBIDO cualquier tecla
 * de remoto/TV (KEY_POWER, KEY_HOME, KEY_VOLUME*, KEY_MENU,
 * KEY_BACK). Por eso Android y OpenNOW lo ven como un mando físico
 * y NO como un control de TV.
 *
 * Si /dev/uinput no existe o el kernel lo rechaza, create()
 * devuelve false y el receptor usa la inyección B
 * (injectInputEvent). Nunca se finge un mando listo.
 */

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <android/log.h>

#define TAG "UinputGamepad"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int uinput_fd = -1;
static int prev_buttons = 0;
static int prev_tl2 = 0;
static int prev_tr2 = 0;
static int current_profile_id = 0;
static pthread_mutex_t report_mutex = PTHREAD_MUTEX_INITIALIZER;

/* Perfiles de emulación (Fase 2, GamepadProfiles en :core):
 * mismo mapa de botones/ejes en todos (los códigos evdev de
 * BTN_SOUTH/EAST/WEST/NORTH son los mismos que BTN_A/B/X/Y, y
 * Options/Share/PS usan START/SELECT/BTN_MODE); lo que cambia es
 * el nombre expuesto y el vendor/product. Un id desconocido es
 * Xbox 360. El touchpad de DS4/DualSense NO se emula (opción (a)
 * del plan): no hay segundo dispositivo táctil en esta versión.
 * La háptica avanzada y los gatillos adaptativos del DualSense
 * tampoco: necesitan reportes HID feature que uinput no expone. */
struct gamepad_profile {
    int id;
    const char *name;
    int vendor;
    int product;
    int version;
};

static const struct gamepad_profile kProfiles[] = {
    {0, "Xbox 360 Controller", 0x045E, 0x028E, 0x0114},
    {1, "Xbox One Controller", 0x045E, 0x02DD, 0x0114},
    {2, "Wireless Controller", 0x054C, 0x05C4, 0x0114}, /* DualShock 4 */
    {3, "DualSense Wireless Controller", 0x054C, 0x0CE6, 0x0114},
    {4, "Generic Gamepad", 0x1234, 0x5678, 0x0114},
};

static const struct gamepad_profile *profile_for_id(int id) {
    for (size_t i = 0; i < sizeof(kProfiles) / sizeof(kProfiles[0]); i++) {
        if (kProfiles[i].id == id) return &kProfiles[i];
    }
    return &kProfiles[0]; /* Id desconocido o corrupto: Xbox 360. */
}

/* Bits del protocolo de 32 bytes (GamepadButtons) -> códigos evdev. */
static const struct {
    int bit;
    int code;
} kButtons[] = {
    {1 << 0, BTN_A},
    {1 << 1, BTN_B},
    {1 << 2, BTN_X},
    {1 << 3, BTN_Y},
    {1 << 4, BTN_TL},
    {1 << 5, BTN_TR},
    {1 << 6, BTN_START},
    {1 << 7, BTN_SELECT},
    {1 << 8, BTN_THUMBL},
    {1 << 9, BTN_THUMBR},
    {1 << 10, BTN_DPAD_UP},
    {1 << 11, BTN_DPAD_DOWN},
    {1 << 12, BTN_DPAD_LEFT},
    {1 << 13, BTN_DPAD_RIGHT},
    {1 << 14, BTN_MODE},           /* botón Xbox (guía) */
    {1 << 15, BTN_TRIGGER_HAPPY1}, /* Compartir */
};

static int write_event(__u16 type, __u16 code, __s32 value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    ssize_t n = write(uinput_fd, &ev, sizeof(ev));
    return n == (ssize_t) sizeof(ev) ? 0 : -1;
}

static int setup_abs(__u16 code, int min, int max, int fuzz, int flat) {
    struct uinput_abs_setup s;
    memset(&s, 0, sizeof(s));
    s.code = code;
    s.absinfo.minimum = min;
    s.absinfo.maximum = max;
    s.absinfo.fuzz = fuzz;
    s.absinfo.flat = flat;
    return ioctl(uinput_fd, UI_ABS_SETUP, &s);
}

static int ug_destroy_locked(void) {
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }
    prev_buttons = 0;
    prev_tl2 = 0;
    prev_tr2 = 0;
    current_profile_id = 0;
    return 0;
}

static int ug_create(int profile_id) {
    const struct gamepad_profile *profile = profile_for_id(profile_id);
    pthread_mutex_lock(&report_mutex);
    ug_destroy_locked();

    uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (uinput_fd < 0) {
        LOGE("no se pudo abrir /dev/uinput: %s", strerror(errno));
        pthread_mutex_unlock(&report_mutex);
        return 0;
    }

    if (ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(uinput_fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN) < 0) {
        LOGE("UI_SET_EVBIT falló: %s", strerror(errno));
        goto fail;
    }

    for (size_t i = 0; i < sizeof(kButtons) / sizeof(kButtons[0]); i++) {
        if (ioctl(uinput_fd, UI_SET_KEYBIT, kButtons[i].code) < 0) {
            LOGE("UI_SET_KEYBIT 0x%x falló: %s", kButtons[i].code, strerror(errno));
            goto fail;
        }
    }
    if (ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TL2) < 0 ||
        ioctl(uinput_fd, UI_SET_KEYBIT, BTN_TR2) < 0) {
        LOGE("UI_SET_KEYBIT TL2/TR2 falló: %s", strerror(errno));
        goto fail;
    }

    const __u16 abs_codes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y};
    for (size_t i = 0; i < sizeof(abs_codes) / sizeof(abs_codes[0]); i++) {
        if (ioctl(uinput_fd, UI_SET_ABSBIT, abs_codes[i]) < 0) {
            LOGE("UI_SET_ABSBIT 0x%x falló: %s", abs_codes[i], strerror(errno));
            goto fail;
        }
    }

    if (setup_abs(ABS_X, -32768, 32767, 16, 128) < 0 ||
        setup_abs(ABS_Y, -32768, 32767, 16, 128) < 0 ||
        setup_abs(ABS_RX, -32768, 32767, 16, 128) < 0 ||
        setup_abs(ABS_RY, -32768, 32767, 16, 128) < 0 ||
        setup_abs(ABS_Z, 0, 255, 0, 0) < 0 ||
        setup_abs(ABS_RZ, 0, 255, 0, 0) < 0 ||
        setup_abs(ABS_HAT0X, -1, 1, 0, 0) < 0 ||
        setup_abs(ABS_HAT0Y, -1, 1, 0, 0) < 0) {
        LOGE("UI_ABS_SETUP falló: %s", strerror(errno));
        goto fail;
    }

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_USB;
    setup.id.vendor = profile->vendor;
    setup.id.product = profile->product;
    setup.id.version = profile->version;
    strncpy(setup.name, profile->name, sizeof(setup.name) - 1);
    if (ioctl(uinput_fd, UI_DEV_SETUP, &setup) < 0) {
        LOGE("UI_DEV_SETUP falló: %s", strerror(errno));
        goto fail;
    }
    if (ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE falló: %s", strerror(errno));
        goto fail;
    }

    current_profile_id = profile->id;
    LOGI("mando virtual '%s' (perfil %d, vendor=0x%04x product=0x%04x) creado en /dev/uinput",
         profile->name, profile->id, profile->vendor, profile->product);
    pthread_mutex_unlock(&report_mutex);
    return 1;

fail:
    ug_destroy_locked();
    pthread_mutex_unlock(&report_mutex);
    return 0;
}

static float clampf(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

static int ug_send_report(int buttons, float lx, float ly, float rx, float ry, float lt, float rt) {
    pthread_mutex_lock(&report_mutex);
    if (uinput_fd < 0) {
        pthread_mutex_unlock(&report_mutex);
        return 0;
    }
    int ok = 0;

    /* Botones: solo los que cambiaron. */
    for (size_t i = 0; i < sizeof(kButtons) / sizeof(kButtons[0]); i++) {
        int was = (prev_buttons & kButtons[i].bit) != 0;
        int is = (buttons & kButtons[i].bit) != 0;
        if (was != is) {
            if (write_event(EV_KEY, kButtons[i].code, is ? 1 : 0) < 0) goto out;
        }
    }
    prev_buttons = buttons;

    /* Gatillos analógicos: el 360 real también los reporta como
     * botones digitales al pasar de la mitad (BTN_TL2/BTN_TR2). */
    int tl2 = clampf(lt, 0.0f, 1.0f) > 0.5f ? 1 : 0;
    int tr2 = clampf(rt, 0.0f, 1.0f) > 0.5f ? 1 : 0;
    if (tl2 != prev_tl2) {
        if (write_event(EV_KEY, BTN_TL2, tl2) < 0) goto out;
        prev_tl2 = tl2;
    }
    if (tr2 != prev_tr2) {
        if (write_event(EV_KEY, BTN_TR2, tr2) < 0) goto out;
        prev_tr2 = tr2;
    }

    /* Ejes. */
    int hat_x = ((buttons & (1 << 13)) ? 1 : 0) - ((buttons & (1 << 12)) ? 1 : 0);
    int hat_y = ((buttons & (1 << 11)) ? 1 : 0) - ((buttons & (1 << 10)) ? 1 : 0);
    if (write_event(EV_ABS, ABS_X, (int) (clampf(lx, -1.0f, 1.0f) * 32767.0f)) < 0) goto out;
    if (write_event(EV_ABS, ABS_Y, (int) (clampf(ly, -1.0f, 1.0f) * 32767.0f)) < 0) goto out;
    if (write_event(EV_ABS, ABS_RX, (int) (clampf(rx, -1.0f, 1.0f) * 32767.0f)) < 0) goto out;
    if (write_event(EV_ABS, ABS_RY, (int) (clampf(ry, -1.0f, 1.0f) * 32767.0f)) < 0) goto out;
    if (write_event(EV_ABS, ABS_Z, (int) (clampf(lt, 0.0f, 1.0f) * 255.0f)) < 0) goto out;
    if (write_event(EV_ABS, ABS_RZ, (int) (clampf(rt, 0.0f, 1.0f) * 255.0f)) < 0) goto out;
    if (write_event(EV_ABS, ABS_HAT0X, hat_x) < 0) goto out;
    if (write_event(EV_ABS, ABS_HAT0Y, hat_y) < 0) goto out;
    if (write_event(EV_SYN, SYN_REPORT, 0) < 0) goto out;
    ok = 1;

out:
    pthread_mutex_unlock(&report_mutex);
    return ok;
}

/* ---- JNI: com.ultimate.gamepad.tv.shizuku.UinputGamepad ---- */

JNIEXPORT jboolean JNICALL
Java_com_ultimate_gamepad_tv_shizuku_UinputGamepad_nativeCreate(JNIEnv *env, jobject thiz, jint profileId) {
    (void) env;
    (void) thiz;
    return (jboolean) ug_create((int) profileId);
}

JNIEXPORT jboolean JNICALL
Java_com_ultimate_gamepad_tv_shizuku_UinputGamepad_nativeSendReport(
        JNIEnv *env, jobject thiz,
        jint buttons, jfloat lx, jfloat ly, jfloat rx, jfloat ry, jfloat lt, jfloat rt) {
    (void) env;
    (void) thiz;
    return (jboolean) ug_send_report((int) buttons, (float) lx, (float) ly,
                                     (float) rx, (float) ry, (float) lt, (float) rt);
}

JNIEXPORT void JNICALL
Java_com_ultimate_gamepad_tv_shizuku_UinputGamepad_nativeDestroy(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&report_mutex);
    ug_destroy_locked();
    pthread_mutex_unlock(&report_mutex);
}
