package dot_product

object Constants {
  // Each input element is a 32-bit unsigned integer.
  val elemBits  = 32
  val elemBytes = elemBits / 8

  // The accelerator streams the two input vectors as a single interleaved
  // stream: one 64-bit beat per index, carrying a[i] in the low 32 bits and
  // b[i] in the high 32 bits. Streaming one wide beat per index (instead of
  // two separate read channels) keeps a single in-order memory stream, which
  // is both simpler to reason about and avoids pacing two readers by hand.
  val pairBits  = 2 * elemBits
  val pairBytes = pairBits / 8
}
