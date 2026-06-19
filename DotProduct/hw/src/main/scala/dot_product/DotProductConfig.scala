package dot_product

import beethoven._
import beethoven.Generation.CppGeneration
import dot_product.Constants._

class DotProductConfig extends AcceleratorConfig({
  CppGeneration.addPreprocessorDefinition(
    Seq(
      ("ELEM_BYTES", elemBytes),
      ("PAIR_BYTES", pairBytes)
    )
  )

  List(
    AcceleratorSystemConfig(
      nCores = 1,
      name = "DotProductCore",
      moduleConstructor = ModuleBuilder(p => new DotProductCore()(p)),
      memoryChannelConfig = List(
        ReadChannelConfig("vec_a", dataBytes = pairBytes),
        ReadChannelConfig("vec_b", dataBytes = pairBytes),
        WriteChannelConfig("vec_out", dataBytes = pairBytes)
      )
    )
  )
})
