#!/usr/bin/env python3
"""
Prepares a fresh checkout of this repository for an unattended CI build of a *test* APK.

The published sources are not buildable as-is: some Cherrygram sources (Java/Kotlin and two
native modules) are closed-source and gitignored, and the signing key is private. This script:

  1. copies compile-only stubs for the missing sources into place (ci/apply-stubs.sh),
  2. creates secrets_for_ci.env from the example file,
  3. installs the throwaway CI signing key (ci/ci-test-signing.jks) where the build expects it,
  4. limits native builds to arm64-v8a (phones) and x86_64 (emulator), builds native code optimized
     even for the debug APK, and enables ccache,
  5. makes buildSrc resolve plugins from Maven Central before the Gradle Plugin Portal,
  6. applies the test-build-only patches in ci/patches (diagnostics kept out of the source).

It only edits the working tree of the CI runner; nothing here is meant to be committed.
"""
import glob
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

    # Test-build-only additions (diagnostics) that must not be part of the feature's source.
    for patch in sorted(glob.glob(path("ci", "patches", "*.patch"))):
        subprocess.check_call(["git", "apply", patch], cwd=ROOT)
        print("prepare-build: applied " + os.path.relpath(patch, ROOT))

    env_file = path("secrets_for_ci.env")
    if not os.path.exists(env_file):
        shutil.copy(path("secrets_for_ci.env.example"), env_file)

    key = path("Your_Key.jks")
    if not os.path.exists(key):
        shutil.copy(path("ci", "ci-test-signing.jks"), key)

    lib_gradle = path("TMessagesProj", "build.gradle")
    # ccache only when installed (the CI step installing it may give up on unreachable mirrors).
    launcher = ", '-DCMAKE_C_COMPILER_LAUNCHER=ccache', '-DCMAKE_CXX_COMPILER_LAUNCHER=ccache'" if shutil.which("ccache") else ""
    replace_once(
        lib_gradle,
        "                arguments '-DANDROID_STL=c++_static', '-DANDROID_PLATFORM=android-23' //, '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON'\n"
        "                targets 'tmessages.49'\n"
        "            }\n"
        "        }\n",
        "                arguments '-DANDROID_STL=c++_static', '-DANDROID_PLATFORM=android-23'" + launcher + ", '-DCMAKE_BUILD_TYPE=Release'\n"
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
    # Only the afat flavor's filter (the same line also appears in other flavors).
    afat_abis = "        afat {\n            ndk {\n                //noinspection ChromeOsAbiSupport\n                abiFilters \"armeabi-v7a\", \"arm64-v8a\"/*, \"x86\", \"x86_64\"*/\n"
    replace_once(app_gradle, afat_abis, afat_abis.replace('abiFilters "armeabi-v7a", "arm64-v8a"/*, "x86", "x86_64"*/', 'abiFilters "arm64-v8a", "x86_64"'))

    # buildSrc's `kotlin-dsl` plugin (and the Kotlin compiler it pulls in) resolves through plugin
    # management, which defaults to the Gradle Plugin Portal only. The portal redirects JetBrains
    # artifacts to GitHub release downloads, which returned HTTP 504; prefer Maven Central.
    buildsrc_settings = path("buildSrc", "settings.gradle.kts")
    if not os.path.exists(buildsrc_settings):
        with open(buildsrc_settings, "w", encoding="utf-8") as f:
            f.write(
                "pluginManagement {\n"
                "    repositories {\n"
                "        mavenCentral()\n"
                "        google()\n"
                "        gradlePluginPortal()\n"
                "    }\n"
                "}\n"
            )

    print("prepare-build: done")


if __name__ == "__main__":
    main()
