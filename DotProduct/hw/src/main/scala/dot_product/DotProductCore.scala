package dot_product

import beethoven._
import beethoven.common._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import dot_product.Constants._

// Command sent by the host to kick off a dot-product computation.
// Fields become arguments to the generated C++ stub (sorted alphabetically):
//   DotProductCore::dot_product(core_id, in_addr, length)
class DotProductCmd(implicit p: Parameters) extends AccelCommand("dot_product") {
  val in_addr = Address()      // base of the interleaved {a,b} pair stream
  val length  = UInt(20.W)     // number of pairs (max ~1M)
}

// Response carrying the scalar dot-product result back to the host.
// Unlike EmptyAccelResponse, this struct is returned by .get() on the C++ side:
//   auto resp = DotProductCore::dot_product(...).get();
//   resp.result  // uint64_t
class DotProductResp extends AccelResponse("dot_product_resp") {
  val result = UInt(64.W)
}

class DotProductCore(implicit p: Parameters) extends AcceleratorCore {
  val io = BeethovenIO(new DotProductCmd(), new DotProductResp())

  // A single read channel; each beat carries one (a, b) pair.
  // No write channel — the scalar result is returned in the response.
  val pairs = getReaderModule("pairs")

  val cmd_fire = io.req.fire

  // Fire the memory request on the same cycle as the incoming command.
  pairs.requestChannel.valid     := cmd_fire
  pairs.requestChannel.bits.addr := io.req.bits.in_addr
  // Total bytes = pair count × bytes per pair.
  pairs.requestChannel.bits.len  := pairBytes.U * io.req.bits.length

  // Three-state machine: no flush state needed because there is no writer.
  val s_idle :: s_go :: s_response :: Nil = Enum(3)
  val state = RegInit(s_idle)

  io.req.ready  := state === s_idle && pairs.requestChannel.ready
  io.resp.valid := state === s_response

  // Registers to track in-flight computation.
  val length_reg  = RegInit(0.U(20.W))
  val accumulator = RegInit(0.U(64.W))
  val count       = RegInit(0.U(20.W))

  when(cmd_fire) {
    length_reg  := io.req.bits.length
    accumulator := 0.U
    count       := 0.U
  }

  // Consume one pair per cycle while running.
  val consume = pairs.dataChannel.data.valid && (state === s_go)
  pairs.dataChannel.data.ready := consume

  when(consume) {
    val beat = pairs.dataChannel.data.bits
    val a    = beat(elemBits - 1, 0)
    val b    = beat(2 * elemBits - 1, elemBits)
    // Product of two UInt(32.W) is UInt(64.W); explicit truncation on accumulate.
    accumulator := (accumulator + (a * b))(63, 0)
    count       := count + 1.U
  }

  // Drive result from the register; it holds the final sum in s_response.
  io.resp.bits.result := accumulator

  when(state === s_idle) {
    when(cmd_fire) { state := s_go }
  }.elsewhen(state === s_go) {
    when(consume && count === length_reg - 1.U) { state := s_response }
  }.elsewhen(state === s_response) {
    when(io.resp.ready) { state := s_idle }
  }
}
