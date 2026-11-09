cmake_minimum_required(VERSION 3.22.1)
project(pocketwright LANGUAGES CXX)

# Node.js pour Android (nodejs-mobile), posé dans third_party par scripts/prepare-engine.sh.
set(NODE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/nodejs-mobile")

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES IMPORTED_LOCATION "${NODE_DIR}/bin/${ANDROID_ABI}/libnode.so")

add_library(pocketwright SHARED node-runner.cpp)
target_include_directories(pocketwright PRIVATE "${NODE_DIR}/include/node")
target_compile_features(pocketwright PRIVATE cxx_std_20)
target_link_libraries(pocketwright node log)
