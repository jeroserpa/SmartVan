# Host build for the arbiter state machine. No ESPHome, no toolchain, no board.
#   make        build and run
#   make build  build only

CXX ?= g++
CXXFLAGS ?= -std=c++14 -Wall -Wextra -Werror -O1 -g

SRC = test_arbiter.cpp ../components/ac_arbiter/arbiter_core.cpp
BIN = test_arbiter

.PHONY: all build clean
all: build
	./$(BIN)

build: $(BIN)

$(BIN): $(SRC) ../components/ac_arbiter/arbiter_core.h
	$(CXX) $(CXXFLAGS) -o $@ $(SRC)

clean:
	rm -f $(BIN) $(BIN).exe
