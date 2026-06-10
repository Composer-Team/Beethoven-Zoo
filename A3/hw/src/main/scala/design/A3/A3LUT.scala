package design.A3

import chisel3._
import chisel3.util.HasBlackBoxInline
import design.A3.FPType.FPType

class A3LUT(infpt: Int, outfpt: FPType, name: String, m: Seq[Double])(implicit params: A3Params)
    extends BlackBox
    with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val in = Input(UInt(infpt.W))
    val out = Output(A3FP(outfpt))
  })

  override val desiredName = s"A3LUT$name"

  private val outWidth = io.out.getWidth
  private val cases = new StringBuilder
  m.zipWithIndex.foreach { case (md, idx) =>
    cases.append(s"      ${infpt}'h${idx.toHexString}: outreg = ${outWidth}'h${A3FP(md, outfpt).litValue.toString(16)};\n")
  }

  setInline(
    s"$desiredName.v",
    s"""
       |module $desiredName(
       |  input clk,
       |  input [${infpt - 1}:0] in,
       |  output [${outWidth - 1}:0] out
       |);
       |  reg [${outWidth - 1}:0] outreg;
       |  assign out = outreg;
       |
       |  always @(*) begin
       |    case (in)
       |${cases.toString()}      default: outreg = ${outWidth}'h0;
       |    endcase
       |  end
       |endmodule
       |""".stripMargin
  )
}
