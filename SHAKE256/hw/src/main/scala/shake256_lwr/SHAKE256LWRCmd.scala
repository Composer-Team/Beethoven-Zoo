package shake256_lwr

import beethoven._
import chisel3._

class SHAKE256LWRCmd extends AccelCommand("shake256_lwr") {
  val nonce_addr = UInt(64.W) // address of 64-bit public PRF nonce
  val sk_addr    = UInt(64.W) // address of 445-bit binary LWR secret key (64 bytes)
  val count      = UInt(32.W) // number of 4-bit PRF outputs to generate (must be even)
  val out_addr   = UInt(64.W) // address to write PRF output buffer (count/2 bytes)
}
