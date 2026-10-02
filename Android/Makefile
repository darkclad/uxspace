SHELL := /bin/sh

GRADLEW := sh ./gradlew
APK ?= app/build/outputs/apk/debug/app-debug.apk
APP_ID ?= com.uxspace
INSTALL_FLAGS ?= -r -d -g
VITURE_SDK_DIR ?=
VITURE_JNI_DIR ?= glasses/src/main/jniLibs/arm64-v8a
VITURE_INCLUDE_DIR ?= glasses/src/main/cpp/include

SDK_DIR ?= $(strip $(shell \
	if [ -n "$$ANDROID_HOME" ]; then \
		printf '%s\n' "$$ANDROID_HOME"; \
	elif [ -n "$$ANDROID_SDK_ROOT" ]; then \
		printf '%s\n' "$$ANDROID_SDK_ROOT"; \
	elif [ -f local.properties ]; then \
		sed -n 's/^sdk\.dir=//p' local.properties | sed 's/\\:/:/g; s/\\\\/\\/g' | head -n 1; \
	elif [ -d "$$HOME/Android/Sdk" ]; then \
		printf '%s\n' "$$HOME/Android/Sdk"; \
	fi))

ADB ?= $(strip $(shell \
	if [ -n "$(SDK_DIR)" ] && [ -x "$(SDK_DIR)/platform-tools/adb" ]; then \
		printf '%s\n' "$(SDK_DIR)/platform-tools/adb"; \
	elif command -v adb >/dev/null 2>&1; then \
		command -v adb; \
	elif [ -n "$(SDK_DIR)" ]; then \
		printf '%s\n' "$(SDK_DIR)/platform-tools/adb"; \
	else \
		printf 'adb\n'; \
	fi))
ADB_TARGET := $(if $(ANDROID_SERIAL),-s $(ANDROID_SERIAL),)

ifneq ($(SDK_DIR),)
export ANDROID_HOME := $(SDK_DIR)
export ANDROID_SDK_ROOT := $(SDK_DIR)
endif

.DEFAULT_GOAL := build

.PHONY: help build doctor doctor-build doctor-install install devices clean vendor-viture-sdk

help:
	@printf '%s\n' \
		'UxSpace Android Makefile' \
		'' \
		'Targets:' \
		'  make              Build the debug APK.' \
		'  make build        Build the debug APK.' \
		'  make install      Build and install the debug APK over adb.' \
		'  make vendor-viture-sdk VITURE_SDK_DIR=/path/to/sdk' \
		'                    Copy VITURE arm64 libs and headers into the ignored source dirs.' \
		'  make doctor       Check build and install prerequisites.' \
		'  make devices      List adb devices.' \
		'  make clean        Remove Gradle build outputs.' \
		'' \
		'Overrides:' \
		'  SDK_DIR=/path/to/Android/Sdk' \
		'  ADB=/path/to/adb' \
		'  ANDROID_SERIAL=<serial>' \
		'  APK=app/build/outputs/apk/debug/app-debug.apk' \
		'  INSTALL_FLAGS="-r -d -g"' \
		'  VITURE_SDK_DIR=/path/to/viture-android-sdk'

build: doctor-build
	$(GRADLEW) :app:assembleDebug
	@test -f "$(APK)" || { \
		printf 'error: expected APK was not produced: %s\n' "$(APK)" >&2; \
		exit 1; \
	}
	@printf 'Built %s\n' "$(APK)"

install: doctor-build doctor-install build
	@printf 'Installing %s to %s\n' "$(APK)" "$$($(ADB) $(ADB_TARGET) shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
	$(ADB) $(ADB_TARGET) install $(INSTALL_FLAGS) "$(APK)"
	@printf 'Installed %s (%s)\n' "$(APP_ID)" "$(APK)"

doctor: doctor-build doctor-install
	@printf 'All checked prerequisites are present.\n'

doctor-build:
	@missing=0; \
	sdk_missing=0; \
	viture_missing=0; \
	if ! command -v java >/dev/null 2>&1; then \
		printf 'error: java was not found. Install JDK 17 or newer.\n' >&2; \
		printf '       openSUSE: sudo zypper install java-17-openjdk-devel\n' >&2; \
		missing=1; \
	else \
		java_version=$$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p'); \
		if [ -z "$$java_version" ]; then \
			java_version=$$(java -version 2>&1 | sed -n '1s/.*version "1\.\([0-9][0-9]*\).*/\1/p'); \
		fi; \
		if [ -z "$$java_version" ] || [ "$$java_version" -lt 17 ]; then \
			printf 'error: JDK 17 or newer is required; found: %s\n' "$$(java -version 2>&1 | sed -n '1p')" >&2; \
			missing=1; \
		fi; \
	fi; \
	if [ ! -f ./gradlew ]; then \
		printf 'error: ./gradlew is missing.\n' >&2; \
		missing=1; \
	fi; \
	if [ -z "$(SDK_DIR)" ]; then \
		printf 'error: Android SDK was not found.\n' >&2; \
		printf '       Set SDK_DIR=/path/to/Android/Sdk or create local.properties with sdk.dir=...\n' >&2; \
		missing=1; \
		sdk_missing=1; \
	else \
		if [ ! -d "$(SDK_DIR)" ]; then \
			printf 'error: Android SDK directory does not exist: %s\n' "$(SDK_DIR)" >&2; \
			missing=1; \
			sdk_missing=1; \
		fi; \
		if [ ! -x "$(SDK_DIR)/platform-tools/adb" ]; then \
			printf 'error: Android platform-tools adb is missing: %s\n' "$(SDK_DIR)/platform-tools/adb" >&2; \
			missing=1; \
			sdk_missing=1; \
		fi; \
		if [ ! -d "$(SDK_DIR)/platforms/android-36" ]; then \
			printf 'error: Android SDK platform android-36 is missing.\n' >&2; \
			missing=1; \
			sdk_missing=1; \
		fi; \
		if ! ls "$(SDK_DIR)"/build-tools/36.* >/dev/null 2>&1; then \
			printf 'error: Android SDK Build-Tools 36.x are missing.\n' >&2; \
			missing=1; \
			sdk_missing=1; \
		fi; \
		if [ ! -d "$(SDK_DIR)/ndk/30.0.14904198" ]; then \
			printf 'error: Android NDK 30.0.14904198 is missing.\n' >&2; \
			missing=1; \
			sdk_missing=1; \
		fi; \
		if [ ! -d "$(SDK_DIR)/cmake/4.1.2" ]; then \
			printf 'error: Android CMake 4.1.2 is missing.\n' >&2; \
			missing=1; \
			sdk_missing=1; \
		fi; \
	fi; \
	if [ ! -f glasses/src/main/jniLibs/arm64-v8a/libglasses.so ]; then \
		printf 'error: missing VITURE SDK binary: glasses/src/main/jniLibs/arm64-v8a/libglasses.so\n' >&2; \
		missing=1; \
		viture_missing=1; \
	fi; \
	if [ ! -f glasses/src/main/cpp/include/viture_device.h ]; then \
		printf 'error: missing VITURE SDK headers under glasses/src/main/cpp/include/.\n' >&2; \
		missing=1; \
		viture_missing=1; \
	fi; \
	if [ "$$missing" -ne 0 ]; then \
		if [ "$$sdk_missing" -ne 0 ]; then \
			printf '\nInstall Android SDK packages with:\n' >&2; \
			printf '  sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;30.0.14904198" "cmake;4.1.2"\n' >&2; \
		fi; \
		if [ "$$viture_missing" -ne 0 ]; then \
			printf '\nVendor the proprietary VITURE SDK with:\n' >&2; \
			printf '  make vendor-viture-sdk VITURE_SDK_DIR=/path/to/viture-android-sdk\n' >&2; \
		fi; \
		exit 1; \
	fi

doctor-install:
	@missing=0; \
	if [ ! -x "$(ADB)" ]; then \
		if command -v adb >/dev/null 2>&1; then \
			adb_path=$$(command -v adb); \
		else \
			adb_path=; \
		fi; \
		if [ -n "$$adb_path" ]; then \
			printf 'Using adb from PATH: %s\n' "$$adb_path"; \
		else \
			printf 'error: adb was not found. Install Android SDK platform-tools or set ADB=/path/to/adb.\n' >&2; \
			missing=1; \
		fi; \
	fi; \
	if [ "$$missing" -ne 0 ]; then \
		exit 1; \
	fi; \
	adb_output=$$($(ADB) devices 2>&1) || { \
		printf 'error: adb failed while listing devices.\n' >&2; \
		printf '%s\n' "$$adb_output" >&2; \
		exit 1; \
	}; \
	devices=$$(printf '%s\n' "$$adb_output" | awk 'NR > 1 && $$2 == "device" { print $$1 }'); \
	unauthorized=$$(printf '%s\n' "$$adb_output" | awk 'NR > 1 && $$2 == "unauthorized" { print $$1 }'); \
	if [ -n "$$unauthorized" ]; then \
		printf 'error: adb sees unauthorized device(s): %s\n' "$$(printf '%s' "$$unauthorized" | tr '\n' ' ')" >&2; \
		printf '       Unlock the Pixel, enable USB debugging, and accept the RSA prompt.\n' >&2; \
		exit 1; \
	fi; \
	if [ -n "$(ANDROID_SERIAL)" ]; then \
		if ! printf '%s\n' "$$devices" | grep -qx "$(ANDROID_SERIAL)"; then \
			printf 'error: ANDROID_SERIAL=%s is not an authorized adb device.\n' "$(ANDROID_SERIAL)" >&2; \
			printf '       Current devices:\n' >&2; \
			printf '%s\n' "$$adb_output" >&2; \
			exit 1; \
		fi; \
	else \
		count=$$(printf '%s\n' "$$devices" | sed '/^$$/d' | wc -l | tr -d ' '); \
		if [ "$$count" -eq 0 ]; then \
			printf 'error: no authorized adb device found.\n' >&2; \
			printf '       Connect the Pixel 10 Pro XL, enable USB debugging, and accept the RSA prompt.\n' >&2; \
			exit 1; \
		fi; \
		if [ "$$count" -gt 1 ]; then \
			printf 'error: multiple adb devices found. Choose one with ANDROID_SERIAL=<serial> make install.\n' >&2; \
			printf '%s\n' "$$adb_output" >&2; \
			exit 1; \
		fi; \
	fi; \
	device_model=$$($(ADB) $(ADB_TARGET) shell getprop ro.product.model 2>/dev/null | tr -d '\r'); \
	device_api=$$($(ADB) $(ADB_TARGET) shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'); \
	printf 'ADB device: %s (API %s)\n' "$$device_model" "$$device_api"

devices:
	$(ADB) devices -l

vendor-viture-sdk:
	@if [ -z "$(VITURE_SDK_DIR)" ]; then \
		printf 'error: set VITURE_SDK_DIR=/path/to/VITURE SDK directory.\n' >&2; \
		printf '       Example: make vendor-viture-sdk VITURE_SDK_DIR=glasses/src/main/VITURE_XR_Glasses_SDK_for_Android\n' >&2; \
		exit 1; \
	fi; \
	if [ ! -d "$(VITURE_SDK_DIR)" ]; then \
		printf 'error: VITURE_SDK_DIR is not a directory: %s\n' "$(VITURE_SDK_DIR)" >&2; \
		exit 1; \
	fi; \
	lib_dir=; \
	for candidate in \
		"$(VITURE_SDK_DIR)/libs/arm64-v8a" \
		"$(VITURE_SDK_DIR)/android/arm64-v8a" \
		$$(find "$(VITURE_SDK_DIR)" -type d -name arm64-v8a 2>/dev/null); do \
		if [ -f "$$candidate/libcarina_vio.so" ] && \
		   [ -f "$$candidate/libcloud_protocol.so" ] && \
		   [ -f "$$candidate/libglasses.so" ]; then \
			lib_dir=$$candidate; \
			break; \
		fi; \
	done; \
	if [ -z "$$lib_dir" ]; then \
		printf 'error: could not find VITURE arm64-v8a libraries under %s.\n' "$(VITURE_SDK_DIR)" >&2; \
		printf '       Expected libcarina_vio.so, libcloud_protocol.so, and libglasses.so.\n' >&2; \
		exit 1; \
	fi; \
	header_dir=; \
	for candidate in \
		"$(VITURE_SDK_DIR)/include" \
		$$(find "$(VITURE_SDK_DIR)" -type f -name viture_device.h -exec dirname {} \; 2>/dev/null); do \
		if [ -f "$$candidate/viture_device.h" ]; then \
			header_dir=$$candidate; \
			break; \
		fi; \
	done; \
	if [ -z "$$header_dir" ]; then \
		printf 'error: could not find VITURE headers under %s.\n' "$(VITURE_SDK_DIR)" >&2; \
		printf '       Expected a directory containing viture_device.h and the other viture_*.h headers.\n' >&2; \
		exit 1; \
	fi; \
	missing=0; \
	for header in \
		viture_camera_provider.h \
		viture_device.h \
		viture_device_carina.h \
		viture_glasses_provider.h \
		viture_macros_public.h \
		viture_protocol_public.h \
		viture_result.h \
		viture_stat_reporter.h \
		viture_version.h; do \
		if [ ! -f "$$header_dir/$$header" ]; then \
			printf 'error: missing VITURE header: %s/%s\n' "$$header_dir" "$$header" >&2; \
			missing=1; \
		fi; \
	done; \
	if [ "$$missing" -ne 0 ]; then \
		exit 1; \
	fi; \
	mkdir -p "$(VITURE_JNI_DIR)" "$(VITURE_INCLUDE_DIR)"; \
	cp "$$lib_dir/libcarina_vio.so" "$(VITURE_JNI_DIR)/"; \
	cp "$$lib_dir/libcloud_protocol.so" "$(VITURE_JNI_DIR)/"; \
	cp "$$lib_dir/libglasses.so" "$(VITURE_JNI_DIR)/"; \
	cp "$$header_dir"/viture_*.h "$(VITURE_INCLUDE_DIR)/"; \
	printf 'Copied VITURE libraries from %s to %s\n' "$$lib_dir" "$(VITURE_JNI_DIR)"; \
	printf 'Copied VITURE headers from %s to %s\n' "$$header_dir" "$(VITURE_INCLUDE_DIR)"

clean:
	$(GRADLEW) clean
