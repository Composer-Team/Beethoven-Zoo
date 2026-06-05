cmake_minimum_required(VERSION 3.20)
project(systolic_array LANGUAGES CXX)

find_package(beethoven REQUIRED)

beethoven_build(systolic_array_tb SOURCES systolic_array_tb.cc)
