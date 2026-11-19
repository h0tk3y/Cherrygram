// CI build stub — the real implementation is closed-source and not in this repository.
//
// Integrity checks used by TgNetWrapper.cpp. Every check passes and nothing is ever disconnected.
//
// This header is compiled into two targets: security/secure_validator.cpp is listed in both the
// `tgnet` static library and the `tmessages.49` shared library (which also links `tgnet`). All
// functions are therefore defined `inline` here, and secure_validator.cpp defines no external
// symbols, so the two copies can never collide at link time.

#pragma once

#include <jni.h>
#include <cstdint>

namespace secure_validator {

// Called from TgNetWrapper.cpp init()/setSystemLangCode() as
// maybeForceDisconnectOrUpdate(env, instanceNum, 3). No-op.
inline void maybeForceDisconnectOrUpdate(JNIEnv *env, int32_t instanceNum, int32_t reason) {
    (void) env;
    (void) instanceNum;
    (void) reason;
}

// Referenced only from commented-out code in TgNetWrapper.cpp; provided for completeness.
inline bool has_jni_hook(JNIEnv *env) {
    (void) env;
    return false;
}

inline bool has_xhook() {
    return false;
}

inline bool validate_signature(JNIEnv *env) {
    (void) env;
    return true;
}

} // namespace secure_validator
