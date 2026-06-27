package systolic_array_verilog

import beethoven._
import beethoven.Platforms.FPGA.Xilinx.AWS.AWSF2Platform
import systolic_array_verilog.Constants._
import beethoven.Generation.CppGeneration
import org.chipsalliance.cde.config.Parameters

class SystolicArrayVerilog4 extends SystolicArrayVerilogConfig(4)

class SystolicArrayVerilogConfig(nCores: Int)
    extends AcceleratorConfig({(p: Parameters) => 
      CppGeneration.addPreprocessorDefinition(
        Seq(
          ("DATA_WIDTH_BYTES", data_width_bytes),
          ("FRAC_BITS", frac_bits),
          ("INT_BITS", int_bits),
          ("SYSTOLIC_ARRAY_DIM", systolic_array_dim)
        )
      )
      AcceleratorSystemConfig(
        nCores = nCores,
        name = "SystolicArrayCore",
        moduleConstructor = new BlackboxBuilderCustom(
          Seq(
            BeethovenIOInterface(
              new SystolicArrayCmd()(p),
              EmptyAccelResponse()
            ),
            BeethovenIOInterface(
              new InitWeightsCmd()(p),
              EmptyAccelResponse()
            )
          ),
          sourcePath = os.pwd / "hw"/ "src" / "main" / "resources",
          externalDependencies = {
            val src_dir = os.pwd / "hw"/ "src" / "main" / "resources"
            Some(
              Seq(
                src_dir / "ProcessingElement.v",
                src_dir / "ShiftReg.v",
                src_dir / "SystolicArray.v",
                src_dir / "WeightQueue.v"
              )
            )
          },
          verilogMacroParams = Map(
            "SYSTOLIC_ARRAY_DIM" -> systolic_array_dim,
            "DATA_WIDTH_BITS" -> data_width_bits,
            "INT_BITS" -> int_bits,
            "FRAC_BITS" -> frac_bits
          )
        ),
        memoryChannelConfig = List(
          ScratchpadConfig(
            name = "WeightScratchpad",
            dataWidthBits = data_width_bits * systolic_array_dim,
            nDatas = max_inner_dimension,
            nPorts = 1
          ),
          ReadChannelConfig(
            "activations",
            ???
          ),
          WriteChannelConfig(
            "vec_out",
            ???
          )
        )
      )
    })

object SystolicArrayVerilogConfig {
  def main(args: Array[String]): Unit = {
    val buildMode = BuildMode.Simulation
    val config = new SystolicArrayVerilogConfig(1)
    BeethovenBuild.run(
      config = config,
      platform = new AWSF2Platform,
      buildMode = buildMode,
      paths = Manifest.local("systolic-array", BuildMode.Simulation))
  }
}
