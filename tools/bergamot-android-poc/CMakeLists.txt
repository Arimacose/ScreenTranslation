cmake_minimum_required(VERSION 3.18)

project(screentranslation_bergamot_android_poc LANGUAGES C CXX)

if(NOT DEFINED BERGAMOT_SOURCE)
  message(FATAL_ERROR "Set BERGAMOT_SOURCE to a pinned bergamot-translator checkout")
endif()

if(NOT EXISTS "${BERGAMOT_SOURCE}/BERGAMOT_VERSION")
  message(FATAL_ERROR "BERGAMOT_SOURCE is not a bergamot-translator checkout")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Keep this target aligned with Bergamot's own Android ARM64 workflow. The
# upstream project selects Ruy for ARM and uses NEON for its int8 path.
set(BUILD_ARCH "armv8-a" CACHE STRING "" FORCE)
set(COMPILE_CPU ON CACHE BOOL "" FORCE)
set(COMPILE_CUDA OFF CACHE BOOL "" FORCE)
set(COMPILE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(COMPILE_PYTHON OFF CACHE BOOL "" FORCE)
set(COMPILE_SERVER OFF CACHE BOOL "" FORCE)
set(COMPILE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_CACHE_STATS OFF CACHE BOOL "" FORCE)
set(GIT_SUBMODULE OFF CACHE BOOL "" FORCE)
set(SSPLIT_COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(SSPLIT_USE_INTERNAL_PCRE2 ON CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(USE_STATIC_LIBS ON CACHE BOOL "" FORCE)

add_subdirectory(
  "${BERGAMOT_SOURCE}"
  "${CMAKE_BINARY_DIR}/bergamot"
  EXCLUDE_FROM_ALL
)

add_executable(
  bergamot-android-benchmark
  bergamot_benchmark.cpp
)
target_link_libraries(
  bergamot-android-benchmark
  PRIVATE bergamot-translator
)
target_include_directories(
  bergamot-android-benchmark
  PRIVATE "${CMAKE_BINARY_DIR}/bergamot/src"
)
target_compile_options(
  bergamot-android-benchmark
  PRIVATE -ffunction-sections -fdata-sections
)
target_link_options(
  bergamot-android-benchmark
  PRIVATE -Wl,--gc-sections
)

if(DEFINED BERGAMOT_COMMIT)
  target_compile_definitions(
    bergamot-android-benchmark
    PRIVATE "SCREENTRANSLATION_BERGAMOT_COMMIT=\"${BERGAMOT_COMMIT}\""
  )
endif()
