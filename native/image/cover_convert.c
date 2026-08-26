#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#define STB_IMAGE_RESIZE_IMPLEMENTATION
#include "stb_image_resize2.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#include "webp/decode.h"
#include "avif/avif.h"

static int is_webp(const uint8_t* d, int len) {
    return len >= 12 && !memcmp(d, "RIFF", 4) && !memcmp(d + 8, "WEBP", 4);
}

static int is_avif(const uint8_t* d, int len) {
    if (len < 12 || memcmp(d + 4, "ftyp", 4)) return 0;
    return !memcmp(d + 8, "avif", 4) || !memcmp(d + 8, "mif1", 4);
}

static uint8_t* decode_webp_rgb(const uint8_t* data, int len, int* w, int* h) {
    uint8_t* pixels = WebPDecodeRGB(data, (size_t) len, w, h);
    if (!pixels) return NULL;
    size_t size = (size_t) *w * *h * 3;
    uint8_t* rgb = malloc(size);
    if (rgb) memcpy(rgb, pixels, size);
    WebPFree(pixels);
    return rgb;
}

static uint8_t* decode_avif_rgb(const uint8_t* data, int len, int* w, int* h) {
    uint8_t* rgb = NULL;
    avifDecoder* decoder = avifDecoderCreate();
    if (!decoder) return NULL;
    decoder->maxThreads = 1; /* callers parallelize per image */

    if (avifDecoderSetIOMemory(decoder, data, (size_t) len) != AVIF_RESULT_OK
        || avifDecoderParse(decoder) != AVIF_RESULT_OK
        || avifDecoderNextImage(decoder) != AVIF_RESULT_OK) {
        avifDecoderDestroy(decoder);
        return NULL;
    }

    avifRGBImage rgbImage;
    avifRGBImageSetDefaults(&rgbImage, decoder->image);
    rgbImage.format = AVIF_RGB_FORMAT_RGB;
    rgbImage.depth = 8; /* 10/12-bit sources are converted down for covers */
    if (avifRGBImageAllocatePixels(&rgbImage) == AVIF_RESULT_OK) {
        if (avifImageYUVToRGB(decoder->image, &rgbImage) == AVIF_RESULT_OK) {
            *w = (int) rgbImage.width;
            *h = (int) rgbImage.height;
            size_t stride = rgbImage.rowBytes;
            rgb = malloc((size_t) *w * *h * 3);
            if (rgb) {
                for (int y = 0; y < *h; y++) {
                    memcpy(rgb + (size_t) y * *w * 3, rgbImage.pixels + (size_t) y * stride,
                           (size_t) *w * 3);
                }
            }
        }
        avifRGBImageFreePixels(&rgbImage);
    }
    avifDecoderDestroy(decoder);
    return rgb;
}

static uint8_t* decode_stb_rgb(const uint8_t* data, int len, int* w, int* h) {
    int comp;
    return stbi_load_from_memory(data, len, w, h, &comp, 3);
}

/* --------------------------------------------------------------- encode */

typedef struct {
    uint8_t* buf;
    size_t len;
    size_t cap;
    int failed;
} growbuf;

static void jpeg_write_cb(void* ctx, void* data, int size) {
    growbuf* g = (growbuf*) ctx;
    if (g->failed) return;
    if (g->len + (size_t) size > g->cap) {
        size_t cap = g->cap ? g->cap * 2 : 65536;
        while (g->len + (size_t) size > cap) cap *= 2;
        uint8_t* buf = realloc(g->buf, cap);
        if (!buf) {
            g->failed = 1; /* dropping a chunk would silently truncate the jpeg */
            return;
        }
        g->buf = buf;
        g->cap = cap;
    }
    memcpy(g->buf + g->len, data, size);
    g->len += (size_t) size;
}

static void resize(int w, int h, int max_w, int max_h, int* tw, int* th) {
    if (w <= 0 || h <= 0) {
        *tw = max_w;
        *th = max_h;
        return;
    }
    double ratio = (double) w / h;
    double target = (double) max_w / max_h;
    if (ratio > target) {
        *tw = max_w;
        *th = (int) (max_w / ratio + 0.5);
    } else {
        *th = max_h;
        *tw = (int) (max_h * ratio + 0.5);
    }
    if (*tw < 1) *tw = 1;
    if (*th < 1) *th = 1;
}

/* ------------------------------------------------------------------ API */

int cover_to_jpeg(const uint8_t* data, int len, int max_w, int max_h,
                  int quality, uint8_t** out, size_t* out_len) {
    int w, h;
    uint8_t* src;
    if (is_webp(data, len)) {
        src = decode_webp_rgb(data, len, &w, &h);
    } else if (is_avif(data, len)) {
        src = decode_avif_rgb(data, len, &w, &h);
    } else {
        src = decode_stb_rgb(data, len, &w, &h);
    }
    if (!src) return 0;

    int tw, th;
    resize(w, h, max_w, max_h, &tw, &th);
    uint8_t* scaled = malloc((size_t) tw * th * 3);
    if (!scaled || !stbir_resize_uint8_linear(src, w, h, 0, scaled, tw, th, 0, STBIR_RGB)) {
        free(scaled);
        free(src);
        return 0;
    }
    free(src);

    growbuf g = {0};
    int ok = stbi_write_jpg_to_func(jpeg_write_cb, &g, tw, th, 3, scaled, quality);
    free(scaled);
    if (!ok || g.failed || g.len == 0) {
        free(g.buf);
        return 0;
    }
    *out = g.buf;
    *out_len = g.len;
    return 1;
}

void cover_free(uint8_t* ptr) {
    free(ptr);
}
