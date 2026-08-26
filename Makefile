SHELL := /bin/sh
JAVAC ?= javac
JAVA ?= java
JAR ?= jar

SOURCES := $(shell find src -name '*.java' -type f | sort)
BUILD := build
CLASSES := $(BUILD)/classes
JAR_FILE := $(BUILD)/java-server.jar

.PHONY: all build run audit clean

all: build

build: $(JAR_FILE)

$(JAR_FILE): $(SOURCES)
	mkdir -p $(CLASSES)
	$(JAVAC) -encoding UTF-8 -Xlint:all -Werror -d $(CLASSES) $(SOURCES)
	$(JAR) --create --file $(JAR_FILE) --main-class Main -C $(CLASSES) .

run: build
	$(JAVA) -jar $(JAR_FILE) --config config.json

audit: build
	JAVA="$(JAVA)" sh tests/audit.sh

clean:
	rm -rf $(BUILD)
