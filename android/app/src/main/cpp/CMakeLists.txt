cmake_minimum_required(VERSION 3.22.1)
project(xhmpv CXX)

add_library(xhmpv SHARED xh_mpv.cpp)
target_compile_options(xhmpv PRIVATE -std=c++17 -fno-exceptions -fno-rtti -O2)
target_link_libraries(xhmpv PRIVATE android log EGL GLESv2 dl)
