package systolic_array

import chisel3.util.isPow2

object Constants {
  val systolic_array_dim = 8

  // Max K (inner) dimension supported by the on-chip weight scratchpad.
  // Bumping this trades BRAM capacity for supporting larger matmuls without
  // re-initializing weights.
  val max_inner_dimension = 512

  // Conservative extra wait after the weight scratchpad's init port reports
  // ready, to cover the last write(s) settling into the underlying BRAM.
  val weight_init_settle_cycles = 8

  val data_width_bits = 16
  val data_width_bytes = data_width_bits / 8
  val int_bits = 7
  val frac_bits = 8

  require(int_bits + frac_bits + 1 == data_width_bits)
  require(isPow2(data_width_bits))
}
