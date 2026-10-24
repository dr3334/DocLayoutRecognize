# Android 下载器在完成时校验 SHA，加载前由 RecognitionModels 检查私有下载记录。
# 为该平台生成配置／ABI／版面／识别四份适配源码；保留相邻 docprase 源码和桌面校验行为。
# 每个替换必须精确匹配一次，源引擎更新时失败并要求核对，避免静默漏掉校验路径。
function(android_replace_once before after)
  string(LENGTH "${android_source_text}" original_length)
  string(LENGTH "${before}" match_length)
  string(REPLACE "${before}" "" without_match "${android_source_text}")
  string(LENGTH "${without_match}" remaining_length)
  math(EXPR removed_length "${original_length} - ${remaining_length}")
  if(NOT removed_length EQUAL match_length)
    message(FATAL_ERROR "Android 引擎适配与 ${android_source_path} 不匹配，请核对 docprase 源码")
  endif()
  string(REPLACE "${before}" "${after}" android_source_text "${android_source_text}")
  set(android_source_text "${android_source_text}" PARENT_SCOPE)
endfunction()

function(android_engine_source target filename)
  if(filename STREQUAL "printed_page_mnn_backend.cpp")
    set(android_source_path "${CMAKE_CURRENT_LIST_DIR}/model_config_ocr_backend.cpp")
  else()
    set(android_source_path "${DOCOCR_SOURCE_ROOT}/src/${filename}")
  endif()
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${android_source_path}")
  file(READ "${android_source_path}" android_source_text)
  if(filename STREQUAL "config.cpp")
    android_replace_once("std::string actual = sha256_file(resolved);" "std::string actual = expected;")
    android_replace_once([=[if (threads != 1) throw ConfigError("unsupported_parameter", "threads: only 1 is implemented");
    plan->threads = 1;]=] [=[if (threads != 1 && threads != 2 && threads != 4) throw ConfigError("unsupported_parameter", "threads: expected 1, 2 or 4");
    plan->threads = int(threads);]=])
  elseif(filename STREQUAL "layout_mnn_backend.cpp")
    android_replace_once(" ||\n            sha256_file(spec.artifacts[0].path) != model_hash" "")
    android_replace_once([=[config.numThread = 1;]=] [=[config.numThread = android_inference_threads();]=])
    android_replace_once([=[#include "config.hpp"]=] [=[#include "config.hpp"
#include "android_engine_runtime.hpp"]=])
  elseif(filename STREQUAL "printed_page_mnn_backend.cpp")
    # 模型目录配置驱动后端已经包含 Android 加载、线程及真实流式适配。
  elseif(filename STREQUAL "abi.cpp")
    android_replace_once([=[#include "dococr/dococr.h"]=] [=[#include "dococr/dococr.h"
#include "android_region_recognition.hpp"]=])
    android_replace_once("dococr::run_page(engine->backend.get(), view, job->cancelled," "dococr::android_run_page(engine->backend.get(), view, job->cancelled,")
    android_replace_once([=[#include "config.hpp"]=] [=[#include "config.hpp"
#include "model_ocr_config.hpp"]=])
    android_replace_once([=[        out += ",\"runtime_configuration\":{\"layout_threads\":1,\"ovis_threads\":1,"
            "\"device\":\"cpu\",\"sampler\":\"greedy\",\"seed\":null,"
            "\"seed_status\":\"not_configured\",\"temperature\":0.8,\"top_k\":40,"
            "\"top_p\":0.9,\"min_p\":0.05,\"tfs_z\":1.0,\"typical\":0.95,"
            "\"repetition_penalty\":1.0,\"presence_penalty\":0.0,"
            "\"frequency_penalty\":0.0,\"penalty_window\":0,\"n_gram\":8,"
            "\"ngram_factor\":1.0,\"llm_precision\":\"low\",\"llm_memory\":\"low\","
            "\"vision_precision\":\"normal\",\"vision_memory\":\"low\","
            "\"reuse_kv\":false,"
            "\"prompt_cache\":false,\"use_mmap\":false,\"kvcache_mmap\":false,"
            "\"async\":false,\"timeout_ms\":" + std::to_string(plan.generation_timeout_ms) + ","
            "\"session_strategy\":\"shared_model_reset_before_each_region\","
            "\"prompt_sha256\":\"de9617f877f6110d22adf1a6ba2a96221189dc246fb1fef161e408d37bff5267\"}";]=] [=[        out += ",\"runtime_configuration\":" + dococr::ocr_runtime_manifest(plan);]=])
    android_replace_once([=[#include "model_ocr_config.hpp"]=] [=[#include "model_ocr_config.hpp"
#include "android_engine_runtime.hpp"]=])
    android_replace_once([=[engine->backend->load(load_spec)]=] [=[dococr::load_android_backend(*engine->backend, load_spec, engine->plan ? engine->plan->threads : 1)]=])
    android_replace_once([=[replacement->load(spec)]=] [=[dococr::load_android_backend(*replacement, spec, job->plan ? job->plan->threads : 1)]=])
    android_replace_once([=["\"effective_parameters\":{\"threads\":1,\"max_new_tokens\":"]=] [=["\"effective_parameters\":{\"threads\":" + std::to_string(plan.threads) + ",\"max_new_tokens\":"]=])

  else()
    message(FATAL_ERROR "未知 Android 引擎适配源码：${filename}")
  endif()
  if(android_source_text MATCHES "sha256_file\\(")
    message(FATAL_ERROR "${filename} 仍含加载时文件 SHA 校验，请核对新源代码")
  endif()
  set(output "${CMAKE_CURRENT_BINARY_DIR}/android-engine/${filename}")
  file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/android-engine")
  file(WRITE "${output}" "${android_source_text}")
  get_target_property(sources ${target} SOURCES)
  list(FILTER sources EXCLUDE REGEX "(^|/)${filename}$")
  set_property(TARGET ${target} PROPERTY SOURCES "${sources}")
  target_sources(${target} PRIVATE "${output}")
  target_include_directories(${target} PRIVATE "${DOCOCR_SOURCE_ROOT}/src" "${DOCOCR_SOURCE_ROOT}/third_party" "${CMAKE_CURRENT_LIST_DIR}")
endfunction()

android_engine_source(dococr_core config.cpp)
android_engine_source(dococr_c abi.cpp)
android_engine_source(dococr_c layout_mnn_backend.cpp)
android_engine_source(dococr_c printed_page_mnn_backend.cpp)

target_sources(dococr_c PRIVATE "${CMAKE_CURRENT_LIST_DIR}/android_recognition_stream.cpp")
target_sources(dococr_c PRIVATE "${CMAKE_CURRENT_LIST_DIR}/android_region_recognition.cpp")
