// CI build stub — the real implementation is closed-source and not in this repository.
//
// Compile-time string obfuscation used by TgNetWrapper.cpp, e.g.
//     env->FindClass(skCrypt("org/telegram/tgnet/ConnectionsManager"))
// The stub does not obfuscate: skCrypt(str) is the plain string literal (a const char *).

#pragma once

#ifndef skCrypt
#define skCrypt(str) (str)
#endif

#ifndef skCrypt_key
#define skCrypt_key(str, key1, key2) (str)
#endif
