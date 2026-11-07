# CI build stub — the real implementation is closed-source and not in this repository.
#
# Pulled in by jni/CMakeLists.txt via include(${CMAKE_HOME_DIRECTORY}/colorado/CMakeLists.txt);
# `colorado` is then linked into tmessages.49. include() does not change
# CMAKE_CURRENT_SOURCE_DIR, so sources are addressed relative to this file's directory.

add_library(colorado STATIC
        ${CMAKE_CURRENT_LIST_DIR}/colorado.c)
target_include_directories(colorado PUBLIC
        ${CMAKE_CURRENT_LIST_DIR})
set_target_properties(colorado PROPERTIES
        ANDROID_ARM_MODE arm)
