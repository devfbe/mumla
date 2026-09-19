cmake_minimum_required(VERSION 3.22)
project(humla_native_tests C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../rnnoise ${CMAKE_BINARY_DIR}/rnnoise)

add_executable(test_rnnoise test_rnnoise.c)
target_link_libraries(test_rnnoise PRIVATE humla_rnnoise m)
add_test(NAME rnnoise COMMAND test_rnnoise)
