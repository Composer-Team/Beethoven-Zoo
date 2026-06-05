cmake_minimum_required(VERSION 3.20)
project(shake256_lwr LANGUAGES CXX)

find_package(beethoven REQUIRED)

beethoven_build(shake256_lwr_tb SOURCES shake256_lwr_tb.cc shake256.cpp)
