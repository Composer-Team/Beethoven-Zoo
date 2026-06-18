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
        ReadChannelConfig("pairs", dataBytes = pairBytes)
      )
    )
  )
})
