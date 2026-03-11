cmake_minimum_required(VERSION 3.16)
project(ableton_link_bridge_cpp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_subdirectory(third_party/ableton-link EXCLUDE_FROM_ALL)

add_executable(ableton-link-bridge
  src/main.cpp
)

target_link_libraries(ableton-link-bridge PRIVATE Ableton::Link)

if (UNIX AND NOT APPLE)
  target_link_libraries(ableton-link-bridge PRIVATE pthread)
endif()
