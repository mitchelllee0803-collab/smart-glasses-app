# Native engine for the Android app: CTranslate2 (translation + Whisper speech),
# SentencePiece (tokenizer) and OpenCC (Chinese script conversion), all compiled from the
# same versions the Windows worker uses. Sources: scripts/android/fetch-native-deps.ps1.
cmake_minimum_required(VERSION 3.22.1)
project(otengine C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

if(NOT EXISTS ${TP}/CTranslate2/CMakeLists.txt)
  message(FATAL_ERROR "Native sources missing. Run: powershell -File scripts/android/fetch-native-deps.ps1")
endif()

# ---- CTranslate2: CPU only, Ruy GEMM backend for ARM (int8 + float), OpenMP from the NDK.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WITH_MKL OFF CACHE BOOL "" FORCE)
set(WITH_DNNL OFF CACHE BOOL "" FORCE)
set(WITH_OPENBLAS OFF CACHE BOOL "" FORCE)
set(WITH_ACCELERATE OFF CACHE BOOL "" FORCE)
set(WITH_RUY ON CACHE BOOL "" FORCE)
set(WITH_CUDA OFF CACHE BOOL "" FORCE)
set(WITH_CUDNN OFF CACHE BOOL "" FORCE)
set(ENABLE_CPU_DISPATCH OFF CACHE BOOL "" FORCE)
set(BUILD_CLI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(OPENMP_RUNTIME "COMP" CACHE STRING "" FORCE)
# NDK OpenMP, linked statically (Gradle would not package libomp.so).
file(GLOB _NDK_OMP "${ANDROID_TOOLCHAIN_ROOT}/lib/clang/*/lib/linux/aarch64/libomp.a")
list(GET _NDK_OMP 0 _NDK_OMP)
set(OpenMP_C_FLAGS "-fopenmp" CACHE STRING "" FORCE)
set(OpenMP_CXX_FLAGS "-fopenmp" CACHE STRING "" FORCE)
set(OpenMP_C_LIB_NAMES "omp" CACHE STRING "" FORCE)
set(OpenMP_CXX_LIB_NAMES "omp" CACHE STRING "" FORCE)
set(OpenMP_omp_LIBRARY "${_NDK_OMP}" CACHE FILEPATH "" FORCE)
add_subdirectory(${TP}/CTranslate2 ctranslate2 EXCLUDE_FROM_ALL)

# ---- SentencePiece (static, no tcmalloc).
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
# SentencePiece would fetch Abseil with FetchContent_Populate, which re-clones from GitHub in
# every fresh build directory (and deletes the local copy first). The pinned checkout
# (20260526.0, placed by fetch-native-deps.ps1 with the third_party/absl junction) is added
# here instead, and SentencePiece's own Abseil branch is switched off: the build is offline.
set(TP_ABSL ${TP}/sentencepiece/third_party/abseil-cpp)
if(NOT EXISTS ${TP_ABSL}/CMakeLists.txt OR NOT EXISTS ${TP}/sentencepiece/third_party/absl)
  message(FATAL_ERROR "Abseil sources missing in ${TP_ABSL}. Copy them from the Translation project (see README).")
endif()
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
set(ABSL_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(${TP_ABSL} abseil-cpp EXCLUDE_FROM_ALL)
set(SPM_ABSL_PROVIDER "external" CACHE STRING "" FORCE)
add_subdirectory(${TP}/sentencepiece sentencepiece EXCLUDE_FROM_ALL)

# ---- OpenCC (static library only; the dictionaries come with the imported models).
set(BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(ENABLE_GTEST OFF CACHE BOOL "" FORCE)
set(ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(BUILD_OPENCC_JIEBA_PLUGIN OFF CACHE BOOL "" FORCE)
set(OPENCC_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(${TP}/OpenCC opencc EXCLUDE_FROM_ALL)

add_library(otengine SHARED engine_jni.cpp)
target_include_directories(otengine PRIVATE
  ${TP}/CTranslate2/include
  ${TP}/sentencepiece/src
  ${TP}/sentencepiece   # its headers include "third_party/absl/..."
  ${TP}/OpenCC/src)
target_link_libraries(otengine PRIVATE ctranslate2 sentencepiece-static libopencc log)
target_compile_options(otengine PRIVATE -O2 -Wall)
# Keep exported symbols to the JNI entry points only.
set_target_properties(otengine PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_options(otengine PRIVATE -static-openmp -Wl,--gc-sections -Wl,--exclude-libs,ALL)
