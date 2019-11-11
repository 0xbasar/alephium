package org.alephium.flow.network.clique

import java.net.InetSocketAddress
import java.time.Instant

import scala.concurrent.duration._

import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp}

import org.alephium.flow.core.AllHandlers
import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}

object OutboundBrokerHandler {
  def props(selfCliqueInfo: CliqueInfo,
            remoteCliqueId: CliqueId,
            remoteBroker: BrokerInfo,
            allHandlers: AllHandlers)(implicit config: PlatformProfile): Props =
    Props(new OutboundBrokerHandler(selfCliqueInfo, remoteCliqueId, remoteBroker, allHandlers))

  sealed trait Command
  case object Retry extends Command

  sealed trait Event
}

class OutboundBrokerHandler(val selfCliqueInfo: CliqueInfo,
                            val remoteCliqueId: CliqueId,
                            val remoteBrokerInfo: BrokerInfo,
                            val allHandlers: AllHandlers)(implicit val config: PlatformProfile)
    extends BrokerHandler {
  override def remote: InetSocketAddress = remoteBrokerInfo.address

  val until: Instant = Instant.now().plusMillis(config.retryTimeout.toMillis)

  IO(Tcp)(context.system) ! Tcp.Connect(remoteBrokerInfo.address)

  var connection: ActorRef = _

  override def receive: Receive = connecting

  def connecting: Receive = {
    case OutboundBrokerHandler.Retry =>
      IO(Tcp)(context.system) ! Tcp.Connect(remoteBrokerInfo.address)

    case _: Tcp.Connected =>
      connection = sender()
      connection ! Tcp.Register(self, keepOpenOnPeerClosed = true)
      handshakeIn()

    case Tcp.CommandFailed(c: Tcp.Connect) =>
      val current = Instant.now()
      if (current isBefore until) {
        scheduleOnce(self, OutboundBrokerHandler.Retry, 1.second)
      } else {
        log.info(s"Cannot connect to ${c.remoteAddress}")
        stop()
      }
  }

  def handleBrokerInfo(_remoteCliqueId: CliqueId, brokerInfo: BrokerInfo): Unit = {
    if (_remoteCliqueId != remoteCliqueId ||
        remoteBrokerInfo.id != brokerInfo.id ||
        remoteBrokerInfo.groupNumPerBroker != brokerInfo.groupNumPerBroker) {
      context stop self
    }
  }
}
