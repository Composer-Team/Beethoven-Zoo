package design.A3

import beethoven.MemoryStreams.ScratchpadDataPort
import beethoven.common.{ShiftReg, splitIntoChunks}
import chisel3._
import chisel3.experimental.FixedPoint
import chisel3.util.{Enum, isPow2, log2Up}
import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.ValName

import scala.annotation.tailrec

object ReduceTree {
  @tailrec
  def apply[T <: Data](
      operands: Seq[T],
      valid: Bool,
      operation: (T, T) => T,
      layersPerReg: Int,
      depth: Int = 0
  ): (T, Bool) = {
    require(operands.length >= 2 && isPow2(operands.length))
    if (operands.length == 2) {
      val v = operation(operands(0), operands(1))
      if (depth + 1 == layersPerReg) {
        (RegNext(v), RegNext(valid))
      } else {
        (v, valid)
      }
    } else {
      val groups = operands.grouped(2).toSeq
      ReduceTree(
        groups.map {
          case Seq(a, b) =>
            if (depth + 1 == layersPerReg) RegNext(operation(a, b)) else operation(a, b)
        },
        if (depth + 1 == layersPerReg) RegNext(valid) else valid,
        operation,
        layersPerReg,
        (depth + 1) % layersPerReg
      )
    }
  }
}

class DotProduct()(implicit params: A3Params, p: Parameters) extends Module {

  private def shiftFixed(x: FixedPoint, latency: Int, useMemory: Boolean = false, withWidth: Option[Int] = None): FixedPoint = {
    if (latency <= 0) x
    else {
      implicit val valName: ValName = ValName(s"dotProductShift_${latency}")
      ShiftReg(x.asUInt, latency, clock, a => a, useMemory = useMemory, withWidth = withWidth).asFixedPoint(x.binaryPoint)
    }
  }

  val io = IO(new Bundle {
    val start = Input(Bool())
    val query = Input(Vec(params.d, A3FP(FPType.Input)))
    val dotProduct = Output(A3FP(FPType.DotProduct))
    val maxValue = Output(A3FP(FPType.DotProduct))
    val complete = Output(Bool())
    val keysScratchpad = Flipped(new ScratchpadDataPort(log2Up(params.n), params.SCRATCHPAD_DATA_WIDTH))
  })
  private val maxValueReg = Reg(A3FP(FPType.DotProduct))

  private val sIdle :: sLoad :: Nil = Enum(2)
  private val readState = RegInit(sIdle)

  private val readCounter = RegInit(0.U(params.NUM_CYCLES_WIDTH.W))
  private val computeCounter = RegInit(0.U(params.NUM_CYCLES_WIDTH.W))

  io.keysScratchpad.req.bits.addr := DontCare
  io.keysScratchpad.req.bits.data := DontCare
  io.keysScratchpad.req.bits.write_enable := false.B
  io.keysScratchpad.req.valid := false.B

  when(readState === sIdle) {
    readCounter := 0.U
    when(io.start) {
      computeCounter := 0.U
      readState := sLoad
    }
  }.elsewhen(readState === sLoad) {
    readCounter := readCounter + 1.U
    io.keysScratchpad.read(readCounter)
    when(readCounter >= (params.n - 1).U) {
      readState := sIdle
    }
  }

  private val keyRow = RegNext(
    VecInit(splitIntoChunks(io.keysScratchpad.res.bits, params.INPUT_WIDTH_TOTAL).map(_.asFixedPoint(params.INPUT_WIDTH_F.BP)))
  )

  val queryHold = Reg(io.query.cloneType)
  when(io.start) {
    queryHold := io.query
  }

  private val productValid = ShiftReg(io.keysScratchpad.res.valid, 2, clock)
  private val product = Reg(Vec(params.d, A3FP(FPType.DPMult)))
  product := keyRow.zip(queryHold).map { case (key, query) => key * query }

  private val (dP, dPValid) = ReduceTree[FixedPoint](product, productValid, { case (a, b) => a +& b }, 2)

  when(dPValid) {
    computeCounter := computeCounter + 1.U
    when(computeCounter === 0.U || dP > maxValueReg) {
      maxValueReg := dP
    }
  }

  io.dotProduct := shiftFixed(
    dP,
    params.PIPELINE_LATENCY - log2Up(params.d) - params.SCRATCHPAD_LATENCY,
    useMemory = true,
    withWidth = Some(io.dotProduct.getWidth)
  )
  io.maxValue := shiftFixed(maxValueReg, params.PIPELINE_LATENCY - params.n - log2Up(params.d) - params.SCRATCHPAD_LATENCY - 1)
  io.complete := ShiftReg(io.start, params.PIPELINE_LATENCY, clock)
}
