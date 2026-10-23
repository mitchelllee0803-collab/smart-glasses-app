# llama.cpp (release b11102, 2026-09-22) as a static library plus the JNI bridge libllmbridge.so.
# Sources: scripts/fetch-llama-cpp.ps1 (checksum-verified tarball).
cmake_minimum_required(VERSION 3.22.1)
project(llmbridge C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp)

if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
  message(FATAL_ERROR "llama.cpp sources missing. Run: powershell -File scripts/fetch-llama-cpp.ps1")
endif()

# Only the core library: no tools, servers, tests or network code.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
# ggml's own thread pool (no libomp to package) and no KleidiAI (fetched from the network).
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# One CPU build: ARMv8.2 with dot-product and FP16 vector instructions (every phone able to hold a
# 1 GB model has them). HyMtRuntime checks the CPU features before loading this library.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_DIR} llama.cpp EXCLUDE_FROM_ALL)

add_library(llmbridge SHARED llm_bridge.cpp)
target_link_libraries(llmbridge PRIVATE llama log)
target_compile_options(llmbridge PRIVATE -O2 -Wall)
set_target_properties(llmbridge PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_options(llmbridge PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
