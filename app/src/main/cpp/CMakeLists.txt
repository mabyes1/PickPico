cmake_minimum_required(VERSION 3.22.1)

project(mcpocket_node_bridge)

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
        IMPORTED_LOCATION "${NODE_MOBILE_ROOT}/bin/${ANDROID_ABI}/libnode.so")

add_library(mcpocket_node_bridge SHARED node_bridge.cpp)

target_include_directories(mcpocket_node_bridge PRIVATE
        "${NODE_MOBILE_ROOT}/include/node")

find_library(log-lib log)

target_link_libraries(mcpocket_node_bridge
        node
        ${log-lib})
