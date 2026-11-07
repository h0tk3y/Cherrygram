#!/usr/bin/env python3
"""
Prepares a fresh checkout of this repository for an unattended CI build of a *test* APK.

The published sources are not buildable as-is: some Cherrygram sources (Java/Kotlin and two
native modules) are closed-source and gitignored, and the signing key is private. This script:

  1. copies compile-only stubs for the missing sources into place (ci/apply-stubs.sh),
  2. creates secrets_for_ci.env from the example file,
  3. installs the throwaway CI signing key (ci/ci-test-signing.jks) where the build expects it,
  4. limits native builds to arm64-v8a (phones) and x86_64 (emulator), builds native code optimized
     even for the debug APK, and enables ccache.

(Debug builds already switch to Telegram's test servers when the country code 999 is entered on the
login screen, which the emulator UI test relies on.)

It only edits the working tree of the CI runner; nothing here is meant to be committed.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def path(*parts):
    return os.path.join(ROOT, *parts)


def replace_once(file, old, new):
    with open(file, encoding="utf-8") as f:
        text = f.read()
    count = text.count(old)
    if count != 1:
        sys.exit(f"prepare-build: expected exactly one match in {file}, found {count}:\n{old}")
    with open(file, "w", encoding="utf-8") as f:
        f.write(text.replace(old, new))


def main():
    subprocess.check_call(["bash", path("ci", "apply-stubs.sh")], cwd=ROOT)

    env_file = path("secrets_for_ci.env")
    if not os.path.exists(env_file):
        shutil.copy(path("secrets_for_ci.env.example"), env_file)

    key = path("Your_Key.jks")
    if not os.path.exists(key):
        shutil.copy(path("ci", "ci-test-signing.jks"), key)

    lib_gradle = path("TMessagesProj", "build.gradle")
    replace_once(
        lib_gradle,
        "                arguments '-DANDROID_STL=c++_static', '-DANDROID_PLATFORM=android-23' //, '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON'\n"
        "                targets 'tmessages.49'\n"
        "            }\n"
        "        }\n",
        "                arguments '-DANDROID_STL=c++_static', '-DANDROID_PLATFORM=android-23', '-DCMAKE_C_COMPILER_LAUNCHER=ccache', '-DCMAKE_CXX_COMPILER_LAUNCHER=ccache', '-DCMAKE_BUILD_TYPE=Release'\n"
        "                targets 'tmessages.49'\n"
        "            }\n"
        "        }\n"
        "        ndk {\n"
        "            abiFilters 'arm64-v8a', 'x86_64'\n"
        "        }\n",
    )

    app_gradle = path("TMessagesProj_App", "build.gradle")
    # Sign with v1 (JAR) + v2 + v3: AGP drops v1 for minSdk >= 24, but some file managers, third-party
    # installers and OEM security scanners still parse the v1 signature and report "problem parsing
    # the package" without it.
    replace_once(
        app_gradle,
        "        release {\n            storeFile file('../Your_Key.jks')\n            storePassword 'Your_Password'\n            keyAlias 'Your_Alias'\n            keyPassword 'Your_Password'\n        }\n",
        "        release {\n            storeFile file('../Your_Key.jks')\n            storePassword 'Your_Password'\n            keyAlias 'Your_Alias'\n            keyPassword 'Your_Password'\n            enableV1Signing true\n            enableV2Signing true\n            enableV3Signing true\n        }\n",
    )
    with open(app_gradle, encoding="utf-8") as f:
        text = f.read()
    marker = "        afat {\n            ndk {\n                //noinspection ChromeOsAbiSupport\n                abiFilters \"armeabi-v7a\", \"arm64-v8a\"/*, \"x86\", \"x86_64\"*/\n"
    if text.count(marker) != 1:
        sys.exit("prepare-build: afat abiFilters not found in TMessagesProj_App/build.gradle")
    text = text.replace(marker, marker.replace('abiFilters "armeabi-v7a", "arm64-v8a"/*, "x86", "x86_64"*/', 'abiFilters "arm64-v8a", "x86_64"'))
    with open(app_gradle, "w", encoding="utf-8") as f:
        f.write(text)

    print("prepare-build: done")


if __name__ == "__main__":
    main()
