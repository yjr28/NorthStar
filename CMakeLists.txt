cmake_minimum_required(VERSION 3.20)
project(northstar_native VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(northstar_telemetry STATIC native/telemetry.c)
target_include_directories(northstar_telemetry PUBLIC native/include)
target_compile_options(northstar_telemetry PRIVATE -Wall -Wextra -Wpedantic)

add_executable(northstar-agent native/agent.cpp)
target_include_directories(northstar-agent PRIVATE native/include)
target_link_libraries(northstar-agent PRIVATE northstar_telemetry)
target_compile_options(northstar-agent PRIVATE -Wall -Wextra -Wpedantic)
