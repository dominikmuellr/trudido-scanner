cmake_minimum_required(VERSION 3.22.1)
project("scanner")

# Point CMake to the folder containing the root OpenCVConfig.cmake
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencv/sdk/native/jni")

find_package(OpenCV REQUIRED)

# Include omp_stubs.c to provide OpenMP symbols missing from NDK 26's libomp
add_library(scanner SHARED scanner.cpp omp_stubs.c)

target_link_libraries(scanner
    ${OpenCV_LIBS}
    android
    log
)