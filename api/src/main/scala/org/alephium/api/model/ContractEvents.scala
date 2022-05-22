// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.api.model

import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.LogStates
import org.alephium.util.AVector

final case class ContractEvents(
    events: AVector[ContractEvent],
    nextStart: Int
)

final case class ContractEventsByTxId(
    events: AVector[ContractEventByTxId],
    nextStart: Int
)

final case class ContractEvent(
    blockHash: BlockHash,
    txId: Hash,
    eventIndex: Int,
    fields: AVector[Val]
)

final case class ContractEventByTxId(
    blockHash: BlockHash,
    contractAddress: Address.Contract,
    eventIndex: Int,
    fields: AVector[Val]
)

object ContractEvents {
  def from(logStates: LogStates): AVector[ContractEvent] = {
    logStates.states.map { logState =>
      ContractEvent(
        logStates.blockHash,
        logState.txId,
        logState.index.toInt,
        logState.fields.map(Val.from)
      )
    }
  }

  def from(logStatesVec: AVector[LogStates], nextStart: Int): ContractEvents = {
    ContractEvents(
      logStatesVec.flatMap(ContractEvents.from),
      nextStart
    )
  }
}

object ContractEventsByTxId {
  def from(logStates: LogStates): AVector[ContractEventByTxId] = {
    logStates.states.map { logState =>
      ContractEventByTxId(
        logStates.blockHash,
        Address.contract(logStates.eventKey),
        logState.index.toInt,
        logState.fields.map(Val.from)
      )
    }
  }

  def from(logStatesVec: AVector[LogStates], nextStart: Int): ContractEventsByTxId = {
    ContractEventsByTxId(
      logStatesVec.flatMap(ContractEventsByTxId.from),
      nextStart
    )
  }
}
