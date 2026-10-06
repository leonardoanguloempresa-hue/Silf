cmake_minimum_required(VERSION 3.22.1)
project("silf_llama")

set(CMAKE_CXX_STANDARD 17)

# Fase 6: Máxima optimización de compilación (-O3) para rendimiento extremo en Android ARM64
add_compile_options(-O3 -DNDEBUG -fexceptions -frtti)
set(CMAKE_BUILD_TYPE Release)

# Opciones para compilar llama.cpp como librería estática mínima
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libs" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server" FORCE)

# Backend GPU Vulkan: requiere ejecución de shaders en host durante cross-compilación ARM64.
# Se utiliza máxima optimización -O3 y vectorización NEON de CPU para máxima velocidad en SoC móvil
set(GGML_VULKAN OFF CACHE BOOL "ggml: use Vulkan" FORCE)

add_subdirectory(llama.cpp)

add_library(
        silf_llama
        SHARED
        silf_llama_jni.cpp
)

target_include_directories(silf_llama PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

target_link_libraries(
        silf_llama
        llama
        log
)
