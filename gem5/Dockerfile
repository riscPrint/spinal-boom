# Build and run environment for gem5 (same packages as gem5's own
# util/dockerfiles/ubuntu-24.04_all-dependencies, minus docs/GPU extras),
# plus RISC-V cross compilers for the benchmarks: Linux (gem5 SE mode) and bare
# metal (RTL), both GCC 13, so the two builds get the same code.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get -y update && apt-get -y install --no-install-recommends \
      build-essential scons python3-dev python3-venv git ca-certificates \
      zlib1g-dev libprotobuf-dev protobuf-compiler libprotoc-dev \
      libgoogle-perftools-dev libhdf5-serial-dev libcapstone-dev \
      libpng-dev libelf-dev pkg-config m4 \
      gcc-riscv64-linux-gnu libc6-dev-riscv64-cross \
      gcc-riscv64-unknown-elf picolibc-riscv64-unknown-elf \
    && rm -rf /var/lib/apt/lists/*
ENV XDG_CACHE_HOME=/tmp/
