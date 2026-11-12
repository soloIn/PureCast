#pragma once

#include <algorithm>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <vector>

using jint = int32_t;
using jlong = int64_t;
using jbyte = int8_t;
using jsize = int32_t;
using jboolean = uint8_t;

#define JNI_TRUE static_cast<jboolean>(1)
#define JNI_FALSE static_cast<jboolean>(0)
#define JNI_VERSION_1_6 0x00010006

struct _jobject {
    virtual ~_jobject() = default;
};
using jobject = _jobject*;
using jclass = _jobject*;

struct _jbyteArray : public _jobject {
    std::vector<jbyte> data;
    explicit _jbyteArray(size_t n) : data(n, 0) {}
};
using jbyteArray = _jbyteArray*;

struct _jmethodID {
    int id;
};
using jmethodID = _jmethodID*;

struct MockJniInputStream : public _jobject {
    std::vector<uint8_t> bytes;
    size_t cursor{0};
    bool throw_on_read{false};
};

struct JNIEnv {
    bool exception_pending{false};
    _jobject dummy_class{};
    _jmethodID read_mid{1};
    _jmethodID skip_mid{2};

    bool ExceptionCheck() const { return exception_pending; }
    void ExceptionClear() { exception_pending = false; }
    void ExceptionDescribe() const {}

    jclass GetObjectClass(jobject /*obj*/) { return &dummy_class; }

    jmethodID GetMethodID(jclass /*cls*/, const char* name, const char* /*sig*/) {
        if (!name) return nullptr;
        if (std::strcmp(name, "read") == 0) return &read_mid;
        if (std::strcmp(name, "skip") == 0) return &skip_mid;
        return nullptr;
    }

    jbyteArray NewByteArray(jsize len) {
        if (len < 0) return nullptr;
        return new _jbyteArray(static_cast<size_t>(len));
    }

    void DeleteLocalRef(jobject ref) {
        auto* arr = dynamic_cast<jbyteArray>(ref);
        delete arr;
    }

    jint CallIntMethod(jobject obj, jmethodID mid, ...) {
        auto* stream = dynamic_cast<MockJniInputStream*>(obj);
        if (!stream || !mid || mid->id != read_mid.id) return -1;
        if (stream->throw_on_read) {
            exception_pending = true;
            return -1;
        }
        va_list ap;
        va_start(ap, mid);
        jbyteArray jbuf = va_arg(ap, jbyteArray);
        jint off = va_arg(ap, jint);
        jint len = va_arg(ap, jint);
        va_end(ap);
        if (!jbuf || off < 0 || len <= 0) return 0;
        if (stream->cursor >= stream->bytes.size()) return -1;
        size_t avail = stream->bytes.size() - stream->cursor;
        size_t max_buf = (static_cast<size_t>(off) < jbuf->data.size())
            ? (jbuf->data.size() - static_cast<size_t>(off))
            : 0;
        size_t n = std::min({avail, static_cast<size_t>(len), max_buf});
        if (n == 0) return 0;
        std::memcpy(jbuf->data.data() + off, stream->bytes.data() + stream->cursor, n);
        stream->cursor += n;
        return static_cast<jint>(n);
    }

    jlong CallLongMethod(jobject obj, jmethodID mid, ...) {
        auto* stream = dynamic_cast<MockJniInputStream*>(obj);
        if (!stream || !mid || mid->id != skip_mid.id) return 0;
        va_list ap;
        va_start(ap, mid);
        jlong to_skip = va_arg(ap, jlong);
        va_end(ap);
        if (to_skip <= 0 || stream->cursor >= stream->bytes.size()) return 0;
        size_t avail = stream->bytes.size() - stream->cursor;
        size_t skipped = std::min(avail, static_cast<size_t>(to_skip));
        stream->cursor += skipped;
        return static_cast<jlong>(skipped);
    }

    void GetByteArrayRegion(jbyteArray arr, jsize start, jsize len, jbyte* out) {
        if (!arr || !out || start < 0 || len <= 0) return;
        size_t s = static_cast<size_t>(start);
        size_t l = static_cast<size_t>(len);
        if (s + l <= arr->data.size()) {
            std::memcpy(out, arr->data.data() + s, l);
        }
    }
};
