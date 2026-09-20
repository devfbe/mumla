# webrtc-audio-processing v2.1 (BSD-3) plus abseil-cpp 20240722.0 (Apache-2.0), built as one
# static library with the C wrapper in humla_apm.cpp.
#
# Upstream ships a meson build, and meson is not one of the two build systems Gradle's
# externalNativeBuild can drive (CMake and the deprecated ndk-build). Rather than bolt a second
# build system and a cross file into the NDK toolchain, the meson file lists are transcribed
# here, in upstream's own order and grouping, so that `diff`ing this file against
#   webrtc/{rtc_base,api,system_wrappers,common_audio,third_party/pffft,third_party/rnnoise,
#           modules/audio_coding,modules/audio_processing}/meson.build
# stays a reviewable exercise on every submodule bump. Every compile definition below names the
# meson line it comes from; a definition that silently differs from upstream is not a build
# error in a DSP library, it is different audio.
set(WAP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/webrtc-audio-processing)
set(WEBRTC_DIR ${WAP_DIR}/webrtc)
set(ABSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/abseil-cpp)

if(NOT EXISTS ${WEBRTC_DIR}/meson.build OR NOT EXISTS ${ABSL_DIR}/CMakeLists.txt)
  message(FATAL_ERROR
      "third_party/webrtc-audio-processing and third_party/abseil-cpp are empty. "
      "Run: git submodule update --init --recursive")
endif()

# meson.build:1-8 default_options: c_std=c11, cpp_std=c++17.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
enable_language(ASM)   # common_audio ships hand-written .S for 32-bit ARM

if(NOT TARGET absl::base)
  # ABSL_PROPAGATE_CXX_STD mirrors meson.build:49-56, which requires abseil to be configured
  # with the same cpp_std=c++17 as webrtc: abseil's ABI depends on the standard it was built
  # with, and a mismatch is a link-time or run-time surprise rather than a compile error.
  set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
  set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
  set(ABSL_BUILD_TESTING OFF CACHE BOOL "" FORCE)
  # -isystem for abseil's headers: they are not ours to keep warning-clean.
  set(ABSL_USE_SYSTEM_INCLUDES ON CACHE BOOL "" FORCE)
  add_subdirectory(${ABSL_DIR} ${CMAKE_CURRENT_BINARY_DIR}/abseil-cpp EXCLUDE_FROM_ALL)
endif()

# ---- platform / arch definitions (mirrors the top-level meson.build) ----
#
# meson.build:170-176 common_cflags, unconditional:
#   WEBRTC_LIBRARY_IMPL           -- selects the in-library definitions of the RTC_EXPORT macros
#   WEBRTC_ENABLE_SYMBOL_EXPORT   -- makes RTC_EXPORT expand to a visibility attribute
#   _WINSOCKAPI_                  -- upstream's windows.h/winsock2.h conflict guard, inert here
#   NDEBUG                        -- upstream's buildtype=debugoptimized default; turns RTC_DCHECK
#                                    into a no-op. Keeping it means the shipped library behaves
#                                    like every distro's copy.
# modules/audio_processing/meson.build:1 apm_flags:
#   WEBRTC_APM_DEBUG_DUMP=0       -- upstream scopes this to modules/audio_processing; it is
#                                    applied to the whole target here because the macro appears
#                                    nowhere else in the tree (checked: only under
#                                    modules/audio_processing and .../logging), so the two are
#                                    equivalent and one list is easier to keep honest.
set(WAP_DEFS WEBRTC_LIBRARY_IMPL WEBRTC_ENABLE_SYMBOL_EXPORT _WINSOCKAPI_ NDEBUG
             WEBRTC_APM_DEBUG_DUMP=0)
set(WAP_OS_LIBS "")

# meson.build:74-105 os_cflags plus meson.build:107-109 platform_cflags. Only the platforms this
# project actually builds for are transcribed; anything else stops the configure rather than
# compiling with a quietly wrong platform define.
if(ANDROID)
  # meson.build:81-85: android gets WEBRTC_ANDROID *and* WEBRTC_LINUX, and links liblog.
  list(APPEND WAP_DEFS WEBRTC_ANDROID WEBRTC_LINUX WEBRTC_POSIX)
  list(APPEND WAP_OS_LIBS log)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  # meson.build:86-90. librt is `required: false` upstream and clock_gettime has been in glibc
  # proper since 2.17, so only the threads dependency is carried over.
  list(APPEND WAP_DEFS WEBRTC_LINUX WEBRTC_POSIX)
  find_package(Threads REQUIRED)
  list(APPEND WAP_OS_LIBS Threads::Threads)
elseif(APPLE)
  # meson.build:74-80. Here so a developer's macOS host build of the ctests is not silently
  # compiled as Linux; the shipped product is Android only.
  list(APPEND WAP_DEFS WEBRTC_MAC WEBRTC_POSIX)
  if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    list(APPEND WAP_DEFS WEBRTC_IOS)
  endif()
  find_package(Threads REQUIRED)
  list(APPEND WAP_OS_LIBS Threads::Threads)
else()
  message(FATAL_ERROR "webrtc_apm: no platform defines transcribed for ${CMAKE_SYSTEM_NAME}; "
                      "see meson.build:74-109 before adding them.")
endif()

# v2.1 does not build with GCC 15: libstdc++ 15 stopped pulling <cstdint> in transitively, and
# webrtc/rtc_base/trace_event.h and .../aec3/multi_channel_content_detector.h both use uint8_t,
# uint64_t and uintptr_t without including it. Upstream fixed exactly that in e9c78dc ("Fix
# compilation with gcc-15", 2025-04-14) by adding `#include <cstdint>` to those two headers; the
# commit is on master and in no release tag, and the submodule stays on v2.1. Force-including
# <cstdint> into the C++ translation units has the same effect -- it only makes the fixed-width
# typedefs visible, and it is a no-op for every source that already includes them -- and it
# becomes inert the moment a tag containing e9c78dc is available, at which point delete it.
# C++ only: <cstdint> is not a C header and this target compiles ~50 .c files.
#
# Note that this is deliberately broader than upstream's fix: e9c78dc edits two headers, this
# force-includes <cstdint> into all ~213 C++ translation units. That is what keeps it a one-line
# build-file change instead of a patch against vendored sources, but it has a cost worth knowing
# at the next submodule bump -- it would also silently paper over a *different* missing include,
# anywhere in the tree, for any of the fixed-width types. When the submodule moves, delete this
# line first and only reinstate it if the build still needs it.
set(WAP_CSTDINT_FIX $<$<COMPILE_LANGUAGE:CXX>:-include$<SEMICOLON>cstdint>)

set(WAP_ARCH_ARM32 OFF)
set(WAP_ARCH_ARM64 OFF)
set(WAP_ARCH_X86 OFF)
set(WAP_ARCH_FLAGS "")
if(ANDROID)
  if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(WAP_ARCH_ARM64 ON)
  elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(WAP_ARCH_ARM32 ON)
  elseif(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
    set(WAP_ARCH_X86 ON)
  endif()
else()
  if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64)$")
    set(WAP_ARCH_ARM64 ON)
  elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^arm")
    set(WAP_ARCH_ARM32 ON)
  elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64|i[3-6]86)$")
    set(WAP_ARCH_X86 ON)
  endif()
endif()
# meson.build:120-165 arch_cflags. NEON is meson's `neon` feature with value 'auto', which
# resolves to enabled on every ARM target that can compile <arm_neon.h>; both Android ARM ABIs
# can, so it is unconditional here.
#   WEBRTC_ARCH_ARM64             -- meson.build:134-138 (cpu_family aarch64)
#   WEBRTC_ARCH_ARM/_ARM_V7       -- meson.build:120-133, from __ARM_ARCH_ISA_ARM/__ARM_ARCH_7A__,
#                                    both defined by the NDK for armeabi-v7a
#   WEBRTC_HAS_NEON + -mfpu=neon  -- meson.build:166-171; the flag is omitted on arm64, where NEON
#                                    is mandatory and -mfpu is not a valid aarch64 option
#   WEBRTC_ENABLE_AVX2            -- meson.build:146-157, unconditional on x86/x86_64 because the
#                                    AVX2 code sits in its own objects behind runtime detection
# Deliberately NOT defined:
#   WAP_DISABLE_INLINE_SSE        -- meson.build:158-163 adds it only when the `inline-sse` option
#                                    is false; meson_options.txt:8-10 defaults it to true, so the
#                                    upstream default build does not define it and neither do we.
#   PFFFT_SIMD_DISABLE            -- third_party/pffft/meson.build:7-9 needs it only for x86
#                                    without inline SSE, ARM without NEON, or MIPS; none of the
#                                    four configurations built here is one of those.
if(WAP_ARCH_ARM64)
  list(APPEND WAP_DEFS WEBRTC_ARCH_ARM64 WEBRTC_HAS_NEON)
elseif(WAP_ARCH_ARM32)
  list(APPEND WAP_DEFS WEBRTC_ARCH_ARM WEBRTC_ARCH_ARM_V7 WEBRTC_HAS_NEON)
  list(APPEND WAP_ARCH_FLAGS -mfpu=neon)
elseif(WAP_ARCH_X86)
  list(APPEND WAP_DEFS WEBRTC_ENABLE_AVX2)
endif()

# ---- rtc_base (rtc_base/meson.build:1-23, 51-66) ----
set(WAP_BASE_SOURCES
  rtc_base/checks.cc rtc_base/containers/flat_tree.cc rtc_base/event.cc rtc_base/event_tracer.cc
  rtc_base/experiments/field_trial_parser.cc rtc_base/logging.cc rtc_base/memory/aligned_malloc.cc
  rtc_base/platform_thread.cc rtc_base/platform_thread_types.cc rtc_base/race_checker.cc rtc_base/random.cc
  rtc_base/string_encode.cc rtc_base/string_to_number.cc rtc_base/string_utils.cc rtc_base/strings/string_builder.cc
  rtc_base/synchronization/sequence_checker_internal.cc rtc_base/synchronization/yield_policy.cc
  rtc_base/system/file_wrapper.cc rtc_base/system_time.cc rtc_base/time_utils.cc rtc_base/zero_memory.cc)
if(ANDROID)
  list(APPEND WAP_BASE_SOURCES rtc_base/system/warn_current_thread_is_deadlocked.cc)
endif()

# ---- api (api/meson.build:1-17) ----
set(WAP_API_SOURCES
  api/audio/audio_frame.cc api/audio/audio_processing.cc api/audio/audio_processing_statistics.cc
  api/audio/channel_layout.cc api/audio/echo_canceller3_config.cc api/rtp_headers.cc api/rtp_packet_info.cc
  api/task_queue/task_queue_base.cc api/units/frequency.cc api/units/time_delta.cc api/units/timestamp.cc
  api/video/color_space.cc api/video/hdr_metadata.cc api/video/video_content_type.cc api/video/video_timing.cc)

# ---- system_wrappers (system_wrappers/meson.build:1-7) ----
set(WAP_SYSWRAP_SOURCES
  system_wrappers/source/cpu_features.cc system_wrappers/source/denormal_disabler.cc
  system_wrappers/source/field_trial.cc system_wrappers/source/metrics.cc system_wrappers/source/sleep.cc)

# ---- common_audio (common_audio/meson.build:1-56) ----
set(WAP_COMMON_AUDIO_SOURCES
  common_audio/audio_converter.cc common_audio/audio_util.cc common_audio/channel_buffer.cc
  common_audio/fir_filter_c.cc common_audio/fir_filter_factory.cc
  common_audio/resampler/push_resampler.cc common_audio/resampler/push_sinc_resampler.cc
  common_audio/resampler/resampler.cc common_audio/resampler/sinc_resampler.cc
  common_audio/resampler/sinusoidal_linear_chirp_source.cc common_audio/ring_buffer.c
  common_audio/signal_processing/auto_correlation.c common_audio/signal_processing/auto_corr_to_refl_coef.c
  common_audio/signal_processing/complex_fft.c common_audio/signal_processing/copy_set_operations.c
  common_audio/signal_processing/cross_correlation.c common_audio/signal_processing/division_operations.c
  common_audio/signal_processing/dot_product_with_scale.cc common_audio/signal_processing/downsample_fast.c
  common_audio/signal_processing/filter_ar.c common_audio/signal_processing/energy.c
  common_audio/signal_processing/filter_ma_fast_q12.c common_audio/signal_processing/get_hanning_window.c
  common_audio/signal_processing/get_scaling_square.c common_audio/signal_processing/ilbc_specific_functions.c
  common_audio/signal_processing/levinson_durbin.c common_audio/signal_processing/lpc_to_refl_coef.c
  common_audio/signal_processing/min_max_operations.c common_audio/signal_processing/randomization_functions.c
  common_audio/signal_processing/real_fft.c common_audio/signal_processing/refl_coef_to_lpc.c
  common_audio/signal_processing/resample_48khz.c common_audio/signal_processing/resample_by_2.c
  common_audio/signal_processing/resample_by_2_internal.c common_audio/signal_processing/resample.c
  common_audio/signal_processing/resample_fractional.c common_audio/signal_processing/spl_init.c
  common_audio/signal_processing/spl_inl.c common_audio/signal_processing/splitting_filter.c
  common_audio/signal_processing/spl_sqrt.c common_audio/signal_processing/sqrt_of_one_minus_x_squared.c
  common_audio/signal_processing/vector_scaling_operations.c common_audio/smoothing_filter.cc
  common_audio/third_party/ooura/fft_size_128/ooura_fft.cc common_audio/third_party/ooura/fft_size_256/fft4g.cc
  common_audio/vad/vad.cc common_audio/vad/vad_core.c common_audio/vad/vad_filterbank.c
  common_audio/vad/vad_gmm.c common_audio/vad/vad_sp.c common_audio/vad/webrtc_vad.c)
# complex_bit_reverse, filter_ar_fast_q12 and spl_sqrt_floor have a C and a 32-bit-ARM assembly
# implementation of the same symbols, and none of the six files carries a preprocessor guard
# (verified: no #if/#ifdef/#ifndef in any of them). Upstream's common_audio/meson.build puts all
# three .c files in the base list and then adds the .S files again for ARM, so on 32-bit ARM the
# archive holds two definitions of each symbol and which one wins depends on member order. We
# list exactly one implementation of each symbol per ABI. arm64 takes the C path, matching
# meson.build:95-100, where `have_arm` is only true for cpu_family 'arm'.
if(WAP_ARCH_ARM32)
  list(APPEND WAP_COMMON_AUDIO_SOURCES
    common_audio/signal_processing/complex_bit_reverse_arm.S
    common_audio/third_party/spl_sqrt_floor/spl_sqrt_floor_arm.S
    common_audio/signal_processing/filter_ar_fast_q12_armv7.S)
else()
  list(APPEND WAP_COMMON_AUDIO_SOURCES
    common_audio/signal_processing/complex_bit_reverse.c
    common_audio/signal_processing/filter_ar_fast_q12.c
    common_audio/third_party/spl_sqrt_floor/spl_sqrt_floor.c)
endif()
if(WAP_ARCH_ARM32 OR WAP_ARCH_ARM64)
  list(APPEND WAP_COMMON_AUDIO_SOURCES
    common_audio/fir_filter_neon.cc common_audio/resampler/sinc_resampler_neon.cc
    common_audio/signal_processing/cross_correlation_neon.c common_audio/signal_processing/downsample_fast_neon.c
    common_audio/signal_processing/min_max_operations_neon.c common_audio/third_party/ooura/fft_size_128/ooura_fft_neon.cc)
endif()

# ---- third_party and the iSAC VAD ----
set(WAP_PFFFT_SOURCES third_party/pffft/src/pffft.c)                    # third_party/pffft/meson.build:1-3
set(WAP_RNNOISE_VAD_SOURCES third_party/rnnoise/src/rnn_vad_weights.cc) # third_party/rnnoise/meson.build:1-3
                                                                        # WebRTC's own tiny VAD weights;
                                                                        # unrelated to the xiph rnnoise in ../rnnoise
set(WAP_ISAC_VAD_SOURCES                                                # modules/audio_coding/meson.build:1-6
  modules/audio_coding/codecs/isac/main/source/filter_functions.c
  modules/audio_coding/codecs/isac/main/source/isac_vad.c
  modules/audio_coding/codecs/isac/main/source/pitch_estimator.c
  modules/audio_coding/codecs/isac/main/source/pitch_filter.c)
# modules/third_party/fft is built by upstream but is not among libwebrtc_audio_processing's
# dependencies (modules/audio_processing/meson.build:196-205), so it is left out here.

# ---- modules/audio_processing (modules/audio_processing/meson.build:3-152, 173-186) ----
set(WAP_APM_SOURCES
  modules/audio_processing/aec_dump/null_aec_dump_factory.cc
  modules/audio_processing/aec3/adaptive_fir_filter.cc modules/audio_processing/aec3/adaptive_fir_filter_erl.cc
  modules/audio_processing/aec3/aec3_common.cc modules/audio_processing/aec3/aec3_fft.cc
  modules/audio_processing/aec3/aec_state.cc modules/audio_processing/aec3/alignment_mixer.cc
  modules/audio_processing/aec3/api_call_jitter_metrics.cc modules/audio_processing/aec3/block_buffer.cc
  modules/audio_processing/aec3/block_delay_buffer.cc modules/audio_processing/aec3/block_framer.cc
  modules/audio_processing/aec3/block_processor.cc modules/audio_processing/aec3/block_processor_metrics.cc
  modules/audio_processing/aec3/clockdrift_detector.cc modules/audio_processing/aec3/coarse_filter_update_gain.cc
  modules/audio_processing/aec3/comfort_noise_generator.cc modules/audio_processing/aec3/config_selector.cc
  modules/audio_processing/aec3/decimator.cc modules/audio_processing/aec3/dominant_nearend_detector.cc
  modules/audio_processing/aec3/downsampled_render_buffer.cc modules/audio_processing/aec3/echo_audibility.cc
  modules/audio_processing/aec3/echo_canceller3.cc modules/audio_processing/aec3/echo_path_delay_estimator.cc
  modules/audio_processing/aec3/echo_path_variability.cc modules/audio_processing/aec3/echo_remover.cc
  modules/audio_processing/aec3/echo_remover_metrics.cc modules/audio_processing/aec3/erle_estimator.cc
  modules/audio_processing/aec3/erl_estimator.cc modules/audio_processing/aec3/fft_buffer.cc
  modules/audio_processing/aec3/filter_analyzer.cc modules/audio_processing/aec3/frame_blocker.cc
  modules/audio_processing/aec3/fullband_erle_estimator.cc modules/audio_processing/aec3/matched_filter.cc
  modules/audio_processing/aec3/matched_filter_lag_aggregator.cc modules/audio_processing/aec3/moving_average.cc
  modules/audio_processing/aec3/multi_channel_content_detector.cc modules/audio_processing/aec3/refined_filter_update_gain.cc
  modules/audio_processing/aec3/render_buffer.cc modules/audio_processing/aec3/render_delay_buffer.cc
  modules/audio_processing/aec3/render_delay_controller.cc modules/audio_processing/aec3/render_delay_controller_metrics.cc
  modules/audio_processing/aec3/render_signal_analyzer.cc modules/audio_processing/aec3/residual_echo_estimator.cc
  modules/audio_processing/aec3/reverb_decay_estimator.cc modules/audio_processing/aec3/reverb_frequency_response.cc
  modules/audio_processing/aec3/reverb_model.cc modules/audio_processing/aec3/reverb_model_estimator.cc
  modules/audio_processing/aec3/signal_dependent_erle_estimator.cc modules/audio_processing/aec3/spectrum_buffer.cc
  modules/audio_processing/aec3/stationarity_estimator.cc modules/audio_processing/aec3/subband_erle_estimator.cc
  modules/audio_processing/aec3/subband_nearend_detector.cc modules/audio_processing/aec3/subtractor.cc
  modules/audio_processing/aec3/subtractor_output_analyzer.cc modules/audio_processing/aec3/subtractor_output.cc
  modules/audio_processing/aec3/suppression_filter.cc modules/audio_processing/aec3/suppression_gain.cc
  modules/audio_processing/aec3/transparent_mode.cc
  modules/audio_processing/aecm/aecm_core.cc modules/audio_processing/aecm/echo_control_mobile.cc
  modules/audio_processing/aecm/aecm_core_c.cc
  modules/audio_processing/agc/agc.cc modules/audio_processing/agc/agc_manager_direct.cc
  modules/audio_processing/agc/legacy/analog_agc.cc modules/audio_processing/agc/legacy/digital_agc.cc
  modules/audio_processing/agc/loudness_histogram.cc modules/audio_processing/agc/utility.cc
  modules/audio_processing/agc2/adaptive_digital_gain_controller.cc modules/audio_processing/agc2/agc2_testing_common.cc
  modules/audio_processing/agc2/biquad_filter.cc modules/audio_processing/agc2/clipping_predictor.cc
  modules/audio_processing/agc2/clipping_predictor_level_buffer.cc modules/audio_processing/agc2/compute_interpolated_gain_curve.cc
  modules/audio_processing/agc2/cpu_features.cc modules/audio_processing/agc2/fixed_digital_level_estimator.cc
  modules/audio_processing/agc2/gain_applier.cc modules/audio_processing/agc2/input_volume_controller.cc
  modules/audio_processing/agc2/input_volume_stats_reporter.cc modules/audio_processing/agc2/interpolated_gain_curve.cc
  modules/audio_processing/agc2/limiter.cc modules/audio_processing/agc2/limiter_db_gain_curve.cc
  modules/audio_processing/agc2/noise_level_estimator.cc
  modules/audio_processing/agc2/rnn_vad/auto_correlation.cc modules/audio_processing/agc2/rnn_vad/features_extraction.cc
  modules/audio_processing/agc2/rnn_vad/lp_residual.cc modules/audio_processing/agc2/rnn_vad/pitch_search.cc
  modules/audio_processing/agc2/rnn_vad/pitch_search_internal.cc modules/audio_processing/agc2/rnn_vad/rnn.cc
  modules/audio_processing/agc2/rnn_vad/rnn_fc.cc modules/audio_processing/agc2/rnn_vad/rnn_gru.cc
  modules/audio_processing/agc2/rnn_vad/spectral_features.cc modules/audio_processing/agc2/rnn_vad/spectral_features_internal.cc
  modules/audio_processing/agc2/saturation_protector.cc modules/audio_processing/agc2/saturation_protector_buffer.cc
  modules/audio_processing/agc2/speech_level_estimator.cc modules/audio_processing/agc2/speech_probability_buffer.cc
  modules/audio_processing/agc2/vad_wrapper.cc modules/audio_processing/agc2/vector_float_frame.cc
  modules/audio_processing/audio_buffer.cc modules/audio_processing/audio_processing_builder_impl.cc
  modules/audio_processing/audio_processing_impl.cc
  modules/audio_processing/capture_levels_adjuster/audio_samples_scaler.cc
  modules/audio_processing/capture_levels_adjuster/capture_levels_adjuster.cc
  modules/audio_processing/echo_control_mobile_impl.cc
  modules/audio_processing/echo_detector/circular_buffer.cc modules/audio_processing/echo_detector/mean_variance_estimator.cc
  modules/audio_processing/echo_detector/moving_max.cc modules/audio_processing/echo_detector/normalized_covariance_estimator.cc
  modules/audio_processing/gain_control_impl.cc modules/audio_processing/gain_controller2.cc
  modules/audio_processing/high_pass_filter.cc modules/audio_processing/include/aec_dump.cc
  modules/audio_processing/include/audio_frame_proxies.cc modules/audio_processing/logging/apm_data_dumper.cc
  modules/audio_processing/ns/fast_math.cc modules/audio_processing/ns/histograms.cc
  modules/audio_processing/ns/noise_estimator.cc modules/audio_processing/ns/noise_suppressor.cc
  modules/audio_processing/ns/ns_fft.cc modules/audio_processing/ns/prior_signal_model.cc
  modules/audio_processing/ns/prior_signal_model_estimator.cc modules/audio_processing/ns/quantile_noise_estimator.cc
  modules/audio_processing/ns/signal_model.cc modules/audio_processing/ns/signal_model_estimator.cc
  modules/audio_processing/ns/speech_probability_estimator.cc modules/audio_processing/ns/suppression_params.cc
  modules/audio_processing/ns/wiener_filter.cc modules/audio_processing/residual_echo_detector.cc
  modules/audio_processing/rms_level.cc modules/audio_processing/splitting_filter.cc
  modules/audio_processing/three_band_filter_bank.cc modules/audio_processing/utility/cascaded_biquad_filter.cc
  modules/audio_processing/utility/delay_estimator.cc modules/audio_processing/utility/delay_estimator_wrapper.cc
  modules/audio_processing/utility/pffft_wrapper.cc modules/audio_processing/vad/gmm.cc
  modules/audio_processing/vad/pitch_based_vad.cc modules/audio_processing/vad/pitch_internal.cc
  modules/audio_processing/vad/pole_zero_filter.cc modules/audio_processing/vad/standalone_vad.cc
  modules/audio_processing/vad/vad_audio_proc.cc modules/audio_processing/vad/vad_circular_buffer.cc
  modules/audio_processing/vad/voice_activity_detector.cc)
if(WAP_ARCH_ARM32 OR WAP_ARCH_ARM64)
  list(APPEND WAP_APM_SOURCES modules/audio_processing/aecm/aecm_core_neon.cc)
endif()

set(WAP_ALL_SOURCES ${WAP_BASE_SOURCES} ${WAP_API_SOURCES} ${WAP_SYSWRAP_SOURCES} ${WAP_COMMON_AUDIO_SOURCES}
    ${WAP_PFFFT_SOURCES} ${WAP_RNNOISE_VAD_SOURCES} ${WAP_ISAC_VAD_SOURCES} ${WAP_APM_SOURCES})
list(TRANSFORM WAP_ALL_SOURCES PREPEND ${WEBRTC_DIR}/)

# Everything a translation unit needs in order to include webrtc's headers, in one place, so
# that the library, the x86 SIMD object libraries and the sanitized rebuild in tests/ cannot
# drift apart. It is INTERFACE-only and carries no objects of its own.
add_library(humla_apm_buildflags INTERFACE)
target_include_directories(humla_apm_buildflags INTERFACE ${WEBRTC_DIR} ${WAP_DIR})
target_compile_definitions(humla_apm_buildflags INTERFACE ${WAP_DEFS})
target_compile_options(humla_apm_buildflags INTERFACE ${WAP_ARCH_FLAGS} ${WAP_CSTDINT_FIX})
# meson.build:49-56 absl_dep, narrowed to the abseil headers this tree actually includes
# (absl/{algorithm/container,base/*,functional/any_invocable,meta/type_traits,strings/*}.h).
#
# Upstream names six packages; the two missing here, absl_synchronization and
# absl_bad_optional_access, are left out on purpose. Nothing in the source set includes their
# headers directly, and both arrive anyway through abseil's own transitive dependencies, so
# naming them would restate what abseil's CMake already propagates.
#
# Note what has and has not been shown about that. humla_apm is a static library, so the only
# configuration in which these symbols reach a real linker today is the host test in tests/,
# which links clean. The Android side does not link them yet at all: webrtc_apm is not in the
# JNI library's CMakeLists (that is Task 3's hand-off), so the ABI builds only compile it. If an
# undefined absl::Mutex or absl::bad_optional_access ever appears, it will appear the first time
# Task 3 links libhumla.so -- add the two targets here then, rather than reaching for
# -Wl,--start-group.
target_link_libraries(humla_apm_buildflags INTERFACE
    absl::base absl::strings absl::any_invocable absl::algorithm_container absl::type_traits
    ${WAP_OS_LIBS})

add_library(humla_apm STATIC ${WAP_ALL_SOURCES} ${CMAKE_CURRENT_SOURCE_DIR}/humla_apm.cpp)
target_include_directories(humla_apm PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(humla_apm PRIVATE -O2 -fvisibility=hidden -Wno-deprecated-declarations)
# PRIVATE: humla_apm.h exposes neither webrtc nor abseil, so no consumer needs their headers.
target_link_libraries(humla_apm PRIVATE humla_apm_buildflags)
# third_party/pffft/meson.build:5 pffft_cflags.
set_source_files_properties(${WEBRTC_DIR}/third_party/pffft/src/pffft.c PROPERTIES COMPILE_DEFINITIONS _GNU_SOURCE)

if(WAP_ARCH_X86)
  # common_audio/meson.build:58-81 and modules/audio_processing/meson.build:154-171: files built
  # with SIMD flags live in separate objects, because the runtime detection that decides whether
  # to call them must not itself be compiled with those flags.
  set(WAP_SSE2_SOURCES common_audio/fir_filter_sse.cc common_audio/resampler/sinc_resampler_sse.cc
      common_audio/third_party/ooura/fft_size_128/ooura_fft_sse2.cc)
  set(WAP_AVX2_SOURCES common_audio/fir_filter_avx2.cc common_audio/resampler/sinc_resampler_avx2.cc
      modules/audio_processing/aec3/adaptive_fir_filter_avx2.cc modules/audio_processing/aec3/adaptive_fir_filter_erl_avx2.cc
      modules/audio_processing/aec3/fft_data_avx2.cc modules/audio_processing/aec3/matched_filter_avx2.cc
      modules/audio_processing/aec3/vector_math_avx2.cc modules/audio_processing/agc2/rnn_vad/vector_math_avx2.cc)
  list(TRANSFORM WAP_SSE2_SOURCES PREPEND ${WEBRTC_DIR}/)
  list(TRANSFORM WAP_AVX2_SOURCES PREPEND ${WEBRTC_DIR}/)
  add_library(humla_apm_sse2 OBJECT ${WAP_SSE2_SOURCES})
  target_compile_options(humla_apm_sse2 PRIVATE -msse2 -O2 -fvisibility=hidden)
  # meson.build:186-190 avx_flags.
  add_library(humla_apm_avx2 OBJECT ${WAP_AVX2_SOURCES})
  target_compile_options(humla_apm_avx2 PRIVATE -mavx2 -mfma -O2 -fvisibility=hidden)
  foreach(t humla_apm_sse2 humla_apm_avx2)
    target_link_libraries(${t} PRIVATE humla_apm_buildflags)
  endforeach()
  target_sources(humla_apm PRIVATE $<TARGET_OBJECTS:humla_apm_sse2> $<TARGET_OBJECTS:humla_apm_avx2>)
endif()
