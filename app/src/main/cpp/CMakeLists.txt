cmake_minimum_required(VERSION 3.18.1)
project(mp3lame C)
file(GLOB LAME_SRC ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame/*.c)
add_library(mp3lame SHARED ${LAME_SRC} lamejni.c)
target_include_directories(mp3lame PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/include
    ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame)
target_compile_definitions(mp3lame PRIVATE STDC_HEADERS=1)
target_compile_options(mp3lame PRIVATE -O2 -w)
target_link_libraries(mp3lame log m)
