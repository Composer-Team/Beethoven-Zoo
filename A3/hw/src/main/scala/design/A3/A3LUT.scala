package design.A3

import beethoven.BeethovenBuild
import chisel3._
import chisel3.experimental.FixedPoint
import design.A3.FPType.FPType

class A3LUT(infpt: Int, outfpt: FPType, name: String, m: Seq[Double])(implicit params: A3Params) extends BlackBox {
  val io = IO(new Bundle {
    val clk = Input(Bool())
    val in = Input(UInt(infpt.W))
    val out = Output(A3FP(outfpt))
  })
  override val desiredName = f"A3LUT${name}"

  val inputLen = io.in.getWidth
  var lutdef =
    f"""
       |module ${desiredName} (
       |input clk,
       |input  [${inputLen}:0] in,
       |output [${io.out.getWidth - 1}:0] out);
       |
       |reg [${io.out.getWidth - 1}:0] outreg;
       |assign out = outreg;
       |always @(posedge clk) begin
       |  case (in)
       |""".stripMargin

  val outWidth = io.out.getWidth
  m.zipWithIndex.foreach { case (md, idx) =>
    lutdef += f"    $inputLen'h${Integer.toHexString(idx)} : outreg <= $outWidth'h${A3FP(md, outfpt).litValue.toString(16)};\n"
  }
  lutdef += "  endcase\nend\nendmodule"

  val fname = os.pwd / f"${desiredName}.v"
  os.write.over(fname, lutdef)
  BeethovenBuild.addSource(fname)
}
