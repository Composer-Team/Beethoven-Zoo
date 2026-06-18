cmake_minimum_required(VERSION 3.20)
project(dot_product LANGUAGES CXX)

find_package(beethoven REQUIRED)

beethoven_build(dot_product_tb SOURCES dot_product_tb.cc)
