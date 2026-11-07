cmake_minimum_required(VERSION 3.22.1)
project(uinput_gamepad)

add_library(uinput_gamepad SHARED uinput_gamepad.c)

find_library(log-lib log)
target_link_libraries(uinput_gamepad ${log-lib})
