package systolic_array

import beethoven._
import beethoven.Generation.CppGeneration
import systolic_array.Constants._

class SystolicArrayConfig extends AcceleratorConfig({
  CppGeneration.addPreprocessorDefinition(
    Seq(
      ("DATA_WIDTH_BYTES", data_width_bytes),
      ("FRAC_BITS", frac_bits),
      ("INT_BITS", int_bits),
      ("SYSTOLIC_ARRAY_DIM", systolic_array_dim)
    )
  )

  List(
    AcceleratorSystemConfig(
      nCores = 1,
      name = "SystolicArrayCore",
      moduleConstructor = ModuleBuilder(p => new SystolicArrayCore(systolic_array_dim)(p)),
      memoryChannelConfig = List(
        ReadChannelConfig(
          "weights",
          dataBytes = data_width_bytes * systolic_array_dim
        ),
        ReadChannelConfig(
          "activations",
          dataBytes = data_width_bytes * systolic_array_dim
        ),
        WriteChannelConfig(
          "vec_out",
          dataBytes = data_width_bytes * systolic_array_dim
        )
      )
    )
  )
})
