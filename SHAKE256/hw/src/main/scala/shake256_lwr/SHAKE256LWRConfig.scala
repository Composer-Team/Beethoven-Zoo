package shake256_lwr

import beethoven._
import beethoven.Generation.CppGeneration
import shake256_lwr.SHAKE256LWRConstants._

class SHAKE256LWRConfig extends AcceleratorConfig({
  CppGeneration.addPreprocessorDefinition(
    Seq(
      ("SHAKE256_LWR_N_LWR", n_lwr),
      ("SHAKE256_LWR_N", lwr_N),
      ("SHAKE256_LWR_P", p),
      ("SHAKE256_LWR_NONCE_BYTES", nonce_bytes),
      ("SHAKE256_LWR_SK_BYTES", sk_bytes),
      ("SHAKE256_LWR_OUT_BYTES", out_bytes),
      ("SHAKE256_LWR_MAX_COUNT", max_count)
    )
  )

  val verilogDir = os.pwd / "hw" / "src" / "main" / "verilog"

  List(
    AcceleratorSystemConfig(
      nCores = 1,
      name = "SHAKE256LWRCore",
      moduleConstructor = new BlackboxBuilderCustom(
        Seq(
          BeethovenIOInterface(
            new SHAKE256LWRCmd,
            EmptyAccelResponse()
          )
        ),
        sourcePath = verilogDir,
        externalDependencies = Some(
          Seq(
            verilogDir / "shake256.v",
            verilogDir / "keccak_f1600.v",
            verilogDir / "keccak_round.v",
            verilogDir / "hash_to_vector.v",
            verilogDir / "dot_product.v",
            verilogDir / "prf_rounding.v",
            verilogDir / "ShakeLWREncrypt.v"
          )
        ),
        verilogMacroParams = Map(
          "N_LWR" -> n_lwr,
          "N" -> lwr_N,
          "P" -> p,
          "MAX_COUNT" -> max_count
        )
      ),
      memoryChannelConfig = List(
        ReadChannelConfig("nonce", dataBytes = nonce_bytes),
        ReadChannelConfig("sk", dataBytes = sk_bytes),
        WriteChannelConfig("out", dataBytes = out_bytes)
      )
    )
  )
})
