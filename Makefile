IMAGE ?= keyboard-setter-builder
PODMAN ?= podman
GRADLE_CACHE ?= keyboard-setter-gradle-cache

RUN_ANDROID = $(PODMAN) run --rm --userns=keep-id \
	-e HOME=/gradle-cache \
	-e JAVA_TOOL_OPTIONS=-Duser.home=/gradle-cache \
	-e GRADLE_USER_HOME=/gradle-cache \
	-v "$(CURDIR):/work:Z" \
	-v "$(GRADLE_CACHE):/gradle-cache:Z" \
	-w /work \
	$(IMAGE)

.PHONY: image debug release clean gradle shell install

image:
	$(PODMAN) build -t $(IMAGE) .

debug: image
	$(RUN_ANDROID) gradle --no-daemon assembleDebug

release: image
	$(RUN_ANDROID) gradle --no-daemon assembleRelease

clean: image
	$(RUN_ANDROID) gradle --no-daemon clean

gradle: image
	$(RUN_ANDROID) gradle --no-daemon $(ARGS)

shell: image
	$(RUN_ANDROID) bash

install:
	adb install -r app/build/outputs/apk/debug/app-debug.apk
