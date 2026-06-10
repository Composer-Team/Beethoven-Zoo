package design

import chisel3.util.log2Ceil

package object A3 {
  def nAlign(x: Int, n: Int): Int = x + (if (x % n == 0) 0 else n - x % n)

  def byteAlign(x: Int): Int = nAlign(x, 8)

  def align64(x: Int): Int = nAlign(x, 64)

  def pow2Align(x: Int): Int = Math.pow(2, log2Ceil(x)).toInt

  def pow264Align(x: Int): Int = pow2Align(align64(x))

  def pow2ByteAlign(x: Int): Int = pow2Align(byteAlign(x))


}
