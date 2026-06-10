cmake_minimum_required(VERSION 3.20)
project(a3 LANGUAGES CXX)

find_package(beethoven REQUIRED)

beethoven_build(a3_tb SOURCES a3_tb.cc)
