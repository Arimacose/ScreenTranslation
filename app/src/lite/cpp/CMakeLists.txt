cmake_minimum_required(VERSION 3.18)

project(screentranslation_bergamot_runner LANGUAGES C CXX)

if(NOT DEFINED BERGAMOT_SOURCE)
  message(FATAL_ERROR "Set BERGAMOT_SOURCE to Bergamot commit 9271618ebbdc5d21ac4dc4df9e72beb7ce644774")
endif()
if(NOT EXISTS "${BERGAMOT_SOURCE}/BERGAMOT_VERSION")
  message(FATAL_ERROR "BERGAMOT_SOURCE is not a bergamot-translator checkout")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
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

add_executable(bergamot_runner bergamot_runner.cpp)
target_link_libraries(bergamot_runner PRIVATE bergamot-translator)
target_include_directories(
  bergamot_runner
  PRIVATE "${CMAKE_BINARY_DIR}/bergamot/src"
)
target_compile_options(
  bergamot_runner
  PRIVATE -ffunction-sections -fdata-sections
)
target_link_options(
  bergamot_runner
  PRIVATE -Wl,--gc-sections
)
