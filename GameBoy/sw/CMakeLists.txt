cmake_minimum_required(VERSION 3.20)
project(beethoven_gameboy_host VERSION 0.1.0 LANGUAGES CXX)

set(BEETHOVEN_SOFTWARE_DIR "$ENV{BEETHOVEN_SOFTWARE}" CACHE PATH "Path to Beethoven-Software checkout")
if(NOT BEETHOVEN_SOFTWARE_DIR)
  if(EXISTS "${CMAKE_CURRENT_LIST_DIR}/../../../Beethoven-Software/libbeethoven")
    set(BEETHOVEN_SOFTWARE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../Beethoven-Software")
  elseif(EXISTS "${CMAKE_CURRENT_LIST_DIR}/../../Beethoven-Software/libbeethoven")
    set(BEETHOVEN_SOFTWARE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../Beethoven-Software")
  else()
    message(FATAL_ERROR "Set BEETHOVEN_SOFTWARE to a Beethoven-Software checkout")
  endif()
endif()

add_subdirectory(${BEETHOVEN_SOFTWARE_DIR}/libbeethoven ${CMAKE_BINARY_DIR}/libbeethoven)

set(GB_BINDING_SRC ../target/binding/beethoven_hardware.cc)
set(GB_BINDING_INC ../target/binding)
set(GB_COMMON_INC ${BEETHOVEN_SOFTWARE_DIR}/libbeethoven/include)

add_executable(gameboy_beethoven_bridge
  gameboy_bridge.cc
  ${GB_BINDING_SRC})
target_include_directories(gameboy_beethoven_bridge PRIVATE
  ${GB_COMMON_INC}
  ${GB_BINDING_INC})
target_link_libraries(gameboy_beethoven_bridge PRIVATE beethoven-zynq)
target_compile_features(gameboy_beethoven_bridge PRIVATE cxx_std_20)

add_executable(gameboy_beethoven_bridge_sim
  gameboy_bridge.cc
  ${GB_BINDING_SRC})
target_include_directories(gameboy_beethoven_bridge_sim PRIVATE
  ${GB_COMMON_INC}
  ${GB_BINDING_INC})

add_executable(gameboy_status_tb
  gameboy_status_tb.cc
  ${GB_BINDING_SRC})
target_include_directories(gameboy_status_tb PRIVATE
  ${GB_COMMON_INC}
  ${GB_BINDING_INC})
target_link_libraries(gameboy_status_tb PRIVATE beethoven-discrete)
target_compile_features(gameboy_status_tb PRIVATE cxx_std_20)

add_executable(gameboy_runtime_tb
  gameboy_runtime_tb.cc
  ${GB_BINDING_SRC})
target_include_directories(gameboy_runtime_tb PRIVATE
  ${GB_COMMON_INC}
  ${GB_BINDING_INC})
target_link_libraries(gameboy_runtime_tb PRIVATE beethoven-discrete)
target_compile_features(gameboy_runtime_tb PRIVATE cxx_std_20)
target_link_libraries(gameboy_beethoven_bridge_sim PRIVATE beethoven-discrete)
target_compile_features(gameboy_beethoven_bridge_sim PRIVATE cxx_std_20)
