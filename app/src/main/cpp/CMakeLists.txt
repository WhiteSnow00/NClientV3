cmake_minimum_required(VERSION 3.22.1)

project(nclientv3_bypass_native C)

file(GLOB BYE_DPI_SRC byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(byedpi SHARED ${BYE_DPI_SRC} native-lib.c)
target_include_directories(byedpi PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/byedpi)
target_compile_options(byedpi PRIVATE -std=c99 -O2 -Wall -Wextra -Wno-unused -Wno-unused-parameter -pedantic)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)
target_link_libraries(byedpi PRIVATE android log)
