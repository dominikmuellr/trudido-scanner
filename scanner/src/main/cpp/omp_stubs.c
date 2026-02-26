/**
 * Stub implementations for OpenMP symbols missing from NDK 26's libomp.
 * These are no-ops that satisfy the linker when using opencv-mobile static libs
 * built against a newer version of clang/LLVM than the NDK provides.
 */
void __kmpc_dispatch_deinit(void) {}
