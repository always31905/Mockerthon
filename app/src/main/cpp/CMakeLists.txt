cmake_minimum_required(VERSION 3.22.1)
project("speechcoach")

# 1. whisper.cpp 원본 트리 구조 폴더를 통째로 빌드에 위임!
add_subdirectory(whisper.cpp)

# 2. 개발자님이 작성하신 완벽한 JNI 브릿지 파일 빌드
add_library(whisper_jni SHARED whisper_jni.cpp)

# 3. JNI 파일이 whisper.cpp의 최신 헤더 파일들을 찾을 수 있게 경로 매핑
target_include_directories(whisper_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/ggml/include
)

# 4. 우리가 만든 JNI와 원작자의 whisper 라이브러리, 안드로이드 기본 시스템을 하나로 연결
target_link_libraries(whisper_jni
        whisper
        android
        log
)