package design.A3

import beethoven._
import beethoven.common._
import beethoven.Generation.CppGeneration

class A3Config extends AcceleratorConfig({
  val numCores = 1
  val params = A3Params(
    n = 320,
    d = 64,
    i = 4,
    f = 4
  )

  CppGeneration.addUserCppDefinition(
    Seq(
      ("uint16_t", "n", params.n),
      ("uint16_t", "d", params.d),
      ("uint32_t", "NUM_CORES", numCores),
      ("uint32_t", "N", params.n),
      ("uint32_t", "D", params.d)
    )
  )

  List(
    AcceleratorSystemConfig(
      name = "A3",
      nCores = numCores,
      moduleConstructor = ModuleBuilder(p => new A3Core()(p, params)),
      memoryChannelConfig = List(
        ReadChannelConfig(
          name = "ReadChannel",
          dataBytes = params.d * params.INPUT_WIDTH_TOTAL / 8
        ),
        WriteChannelConfig(
          name = "WriteChannel",
          dataBytes = pow2ByteAlign(params.OUTPUT_WIDTH_TOTAL) / 8
        ),
        ScratchpadConfig(
          name = "KeysScratchpad",
          dataWidthBits = params.SCRATCHPAD_DATA_WIDTH,
          nDatas = params.n,
          latency = params.SCRATCHPAD_LATENCY,
          nPorts = 1
        ),
        ScratchpadConfig(
          name = "ValuesScratchpad",
          dataWidthBits = params.SCRATCHPAD_DATA_WIDTH,
          nDatas = params.n,
          latency = params.SCRATCHPAD_LATENCY,
          nPorts = 1
        )
      )
    )
  )
})
