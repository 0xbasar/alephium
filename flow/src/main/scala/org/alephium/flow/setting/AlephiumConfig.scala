package org.alephium.flow.setting

import java.net.InetSocketAddress
import java.nio.file.Path

import scala.collection.immutable.ArraySeq

import com.typesafe.config.Config
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.ConfigReader.Result
import pureconfig.generic.auto._

import org.alephium.flow.network.nat.Upnp
import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.config.{BrokerConfig, ChainsConfig, ConsensusConfig, DiscoveryConfig}
import org.alephium.protocol.model.{Address, Block, NetworkType}
import org.alephium.util.{AVector, Duration, U64}

final case class BrokerSetting(groups: Int, brokerNum: Int, brokerId: Int) extends BrokerConfig {
  override val chainNum: Int = groups * groups

  override val depsNum: Int = 2 * groups - 1

  override lazy val groupNumPerBroker: Int = groups / brokerNum
}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
final case class ConsensusSetting(numZerosAtLeastInHash: Int,
                                  blockTargetTime: Duration,
                                  tipsPruneInterval: Int,
                                  blockCacheCapacityPerChain: Int)
    extends ConsensusConfig {
  val maxMiningTarget: BigInt = (BigInt(1) << (256 - numZerosAtLeastInHash)) - 1

  val expectedTimeSpan: Duration = blockTargetTime

  val medianTimeInterval: Int = 11
  val diffAdjustDownMax: Int  = 16
  val diffAdjustUpMax: Int    = 8
  val timeSpanMin: Duration   = (expectedTimeSpan * (100L - diffAdjustDownMax)).get divUnsafe 100L
  val timeSpanMax: Duration   = (expectedTimeSpan * (100L + diffAdjustUpMax)).get divUnsafe 100L

  //scalastyle:off magic.number
  val recentBlockHeightDiff: Int         = 30
  val recentBlockTimestampDiff: Duration = Duration.ofMinutesUnsafe(30)
  //scalastyle:on
}

final case class MiningSetting(nonceStep: BigInt)

final case class NetworkSetting(
    pingFrequency: Duration,
    retryTimeout: Duration,
    upnp: UpnpSettings,
    bindAddress: InetSocketAddress,
    internalAddress: InetSocketAddress,
    masterAddress: InetSocketAddress,
    externalAddress: Option[InetSocketAddress],
    numOfSyncBlocksLimit: Int,
    rpcPort: Option[Int],
    wsPort: Option[Int],
    restPort: Option[Int]
) {
  val isCoordinator: Boolean = internalAddress == masterAddress

  def handshakeTimeout: Duration = retryTimeout

  val externalAddressInferred: Option[InetSocketAddress] = externalAddress.orElse {
    if (upnp.enabled) {
      Upnp.getUpnpClient(upnp).map { client =>
        val bindingPort = bindAddress.getPort
        client.addPortMapping(bindingPort, bindingPort)
        new InetSocketAddress(client.externalAddress, bindingPort)
      }
    } else None
  }
}

final case class UpnpSettings(enabled: Boolean,
                              httpTimeout: Option[Duration],
                              discoveryTimeout: Option[Duration])

final case class DiscoverySetting(
    bootstrap: ArraySeq[InetSocketAddress],
    peersPerGroup: Int,
    scanMaxPerGroup: Int,
    scanFrequency: Duration,
    scanFastFrequency: Duration,
    neighborsPerGroup: Int
) extends DiscoveryConfig {
  val (discoveryPrivateKey, discoveryPublicKey) = SignatureSchema.generatePriPub()
}

final case class MemPoolSetting(txPoolCapacity: Int, txMaxNumberPerBlock: Int)

final case class ChainsSetting(networkType: NetworkType, genesisBalances: AVector[(Address, U64)])
    extends ChainsConfig

final case class AlephiumConfig(
    broker: BrokerSetting,
    consensus: ConsensusSetting,
    mining: MiningSetting,
    network: NetworkSetting,
    discovery: DiscoverySetting,
    mempool: MemPoolSetting,
    chains: ChainsSetting
) {
  val genesisBlocks: AVector[AVector[Block]] =
    Configs.loadBlockFlow(chains.genesisBalances)(broker, consensus)
}

object AlephiumConfig {
  import PureConfigUtils._

  private final case class TempChainsSetting(networkType: NetworkType) {
    val chainsSetting: ChainsSetting = ChainsSetting(networkType, Genesis(networkType))
  }

  implicit val chainsSettingReader: ConfigReader[ChainsSetting] =
    ConfigReader[TempChainsSetting].map(_.chainsSetting)

  def load(config: Config): Result[AlephiumConfig] = {
    val path          = "alephium"
    val configLocated = if (config.hasPath(path)) config.getConfig(path) else config
    ConfigSource.fromConfig(configLocated).load[AlephiumConfig]
  }

  def load(rootPath: Path): Result[AlephiumConfig] = {
    load(Configs.parseConfig(rootPath))
  }
}
