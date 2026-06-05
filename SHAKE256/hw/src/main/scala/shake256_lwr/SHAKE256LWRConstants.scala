package shake256_lwr

object SHAKE256LWRConstants {
  val n_lwr       = 445   // LWR secret key dimension
  val lwr_N       = 2048  // lattice modulus
  val p           = 16    // output modulus (4-bit half-char)
  val nonce_bytes = 8     // 64-bit public PRF nonce
  val sk_bytes    = 64    // 512-bit bus; lower 445 bits = binary LWR secret key
  val out_bytes   = 1     // write channel bus width (8-bit); total transfer = count/2 bytes
  val max_count   = 1024  // compile-time max PRF outputs per command
}
