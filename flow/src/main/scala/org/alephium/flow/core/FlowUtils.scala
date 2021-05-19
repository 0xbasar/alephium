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

package org.alephium.flow.core

import scala.annotation.tailrec
import scala.reflect.ClassTag

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockFlowState.TxStatus
import org.alephium.flow.core.FlowUtils._
import org.alephium.flow.handler.FlowHandler.BlockFlowTemplate
import org.alephium.flow.mempool.{GrandPool, MemPool, MemPoolChanges, Normal, Reorg}
import org.alephium.flow.setting.MemPoolSetting
import org.alephium.io.{IOError, IOResult, IOUtils}
import org.alephium.protocol.{ALF, BlockHash, Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util.{AVector, TimeStamp, U256}

// scalastyle:off number.of.methods
trait FlowUtils
    extends MultiChain
    with BlockFlowState
    with SyncUtils
    with ConflictedBlocks
    with StrictLogging {
  implicit def mempoolSetting: MemPoolSetting

  val grandPool = GrandPool.empty

  def getMemPool(mainGroup: GroupIndex): MemPool = {
    grandPool.getMemPool(mainGroup)
  }

  def getMemPool(chainIndex: ChainIndex): MemPool = {
    grandPool.getMemPool(chainIndex)
  }

  def calMemPoolChangesUnsafe(
      mainGroup: GroupIndex,
      oldDeps: BlockDeps,
      newDeps: BlockDeps
  ): MemPoolChanges = {
    val oldOutDeps = oldDeps.outDeps
    val newOutDeps = newDeps.outDeps
    val diffs = AVector.tabulate(brokerConfig.groups) { toGroup =>
      val toGroupIndex = GroupIndex.unsafe(toGroup)
      val oldDep       = oldOutDeps(toGroup)
      val newDep       = newOutDeps(toGroup)
      val index        = ChainIndex(mainGroup, toGroupIndex)
      getBlockChain(index).calBlockDiffUnsafe(newDep, oldDep)
    }
    val toRemove = diffs.map(_.toAdd.flatMap(_.nonCoinbase))
    val toAdd    = diffs.map(_.toRemove.flatMap(_.nonCoinbase))
    if (toAdd.sumBy(_.length) == 0) Normal(toRemove) else Reorg(toRemove, toAdd)
  }

  def updateGrandPoolUnsafe(
      mainGroup: GroupIndex,
      newDeps: BlockDeps,
      oldDeps: BlockDeps
  ): AVector[TransactionTemplate] = {
    updateMemPoolUnsafe(mainGroup, newDeps, oldDeps)
    updatePendingPoolUnsafe(mainGroup, newDeps)
  }

  def updateMemPoolUnsafe(mainGroup: GroupIndex, newDeps: BlockDeps, oldDeps: BlockDeps): Unit = {
    calMemPoolChangesUnsafe(mainGroup, oldDeps, newDeps) match {
      case Normal(toRemove) =>
        val removed = toRemove.foldWithIndex(0) { (sum, txs, toGroup) =>
          val toGroupIndex = GroupIndex.unsafe(toGroup)
          val index        = ChainIndex(mainGroup, toGroupIndex)
          sum + getMemPool(mainGroup).removeFromTxPool(index, txs.map(_.toTemplate))
        }
        if (removed > 0) {
          logger.debug(s"Normal update for #$mainGroup mempool: #$removed removed")
        }
      case Reorg(toRemove, toAdd) =>
        val (removed, added) = getMemPool(mainGroup).reorg(toRemove, toAdd)
        logger.debug(s"Reorg for #$mainGroup mempool: #$removed removed, #$added added")
    }
  }

  // TODO: we could update this purely based on mempool, but we need to consider the tradeoffs
  // it returns the list of txs added to shared pool
  def updatePendingPoolUnsafe(
      mainGroup: GroupIndex,
      newDeps: BlockDeps
  ): AVector[TransactionTemplate] = Utils.unsafe {
    getPersistedWorldState(newDeps, mainGroup).flatMap { worldState =>
      getMemPool(mainGroup).updatePendingPool(worldState)
    }
  }

  def getBestDeps(groupIndex: GroupIndex): BlockDeps

  def updateBestDeps(): IOResult[AVector[TransactionTemplate]]

  def updateBestDepsUnsafe(): AVector[TransactionTemplate]

  def calBestDepsUnsafe(group: GroupIndex): BlockDeps

  def collectPooledTxs(chainIndex: ChainIndex): AVector[TransactionTemplate] = {
    getMemPool(chainIndex).collectForBlock(chainIndex, mempoolSetting.txMaxNumberPerBlock)
  }

  def filterValidInputsUnsafe(
      chainIndex: ChainIndex,
      deps: BlockDeps,
      txs: AVector[TransactionTemplate]
  ): AVector[TransactionTemplate] = {
    val cachedWorldState = Utils.unsafe(getCachedWorldState(deps, chainIndex.from))
    txs.filter(tx => Utils.unsafe(cachedWorldState.containsAllInputs(tx)))
  }

  def collectTransactions(
      chainIndex: ChainIndex,
      deps: BlockDeps
  ): IOResult[AVector[TransactionTemplate]] =
    IOUtils.tryExecute {
      val candidates0 = collectPooledTxs(chainIndex)
      val candidates1 = FlowUtils.filterDoubleSpending(candidates0)
      val candidates2 = filterValidInputsUnsafe(chainIndex, deps, candidates1)
      val candidates3 = filterConflicts(chainIndex.from, deps, candidates2, getBlockUnsafe)
      candidates3
    }

  // all the inputs and double spending should have been checked
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def executeTxTemplates(
      chainIndex: ChainIndex,
      deps: BlockDeps,
      txTemplates: AVector[TransactionTemplate]
  ): IOResult[AVector[Transaction]] = {
    if (chainIndex.isIntraGroup) {
      val parentHash = deps.getOutDep(chainIndex.to)
      val order      = Block.getScriptExecutionOrder(parentHash, txTemplates)
      val fullTxs =
        Array.ofDim[Transaction](txTemplates.length + 1) // reverse 1 slot for coinbase tx
      txTemplates.foreachWithIndex { case (tx, index) =>
        if (tx.unsigned.scriptOpt.isEmpty) {
          fullTxs(index) = FlowUtils.convertNonScriptTx(tx)
        }
      }

      for {
        cachedWorldState <- getCachedWorldState(deps, chainIndex.from)
        _ <- order.foreachE[IOError] { scriptTxIndex =>
          val tx = txTemplates(scriptTxIndex)
          FlowUtils
            .generateFullTx(cachedWorldState, tx, tx.unsigned.scriptOpt.get)
            .map(fullTx => fullTxs(scriptTxIndex) = fullTx)
        }
      } yield AVector.unsafe(fullTxs, 0, txTemplates.length)
    } else {
      Right(txTemplates.map(FlowUtils.convertNonScriptTx))
    }
  }

  def prepareBlockFlow(chainIndex: ChainIndex): IOResult[BlockFlowTemplate] = {
    assume(brokerConfig.contains(chainIndex.from))
    val singleChain = getBlockChain(chainIndex)
    val bestDeps    = getBestDeps(chainIndex.from)
    for {
      target       <- singleChain.getHashTarget(bestDeps.getOutDep(chainIndex.to))
      parentHeader <- getBlockHeader(bestDeps.parentHash(chainIndex))
      candidates   <- collectTransactions(chainIndex, bestDeps)
      fullTxs      <- executeTxTemplates(chainIndex, bestDeps, candidates)
      loosenDeps <- looseUncleDependencies(
        bestDeps,
        chainIndex,
        FlowUtils.nextTimeStamp(parentHeader.timestamp)
      )
      depStateHash <- getDepStateHash(BlockDeps.unsafe(loosenDeps), chainIndex.from)
    } yield {
      BlockFlowTemplate(
        chainIndex,
        loosenDeps,
        depStateHash,
        target,
        parentHeader.timestamp,
        fullTxs
      )
    }
  }

  def looseUncleDependencies(
      bestDeps: BlockDeps,
      chainIndex: ChainIndex,
      currentTs: TimeStamp
  ): IOResult[AVector[BlockHash]] = {
    val thresholdTs = currentTs.minusUnsafe(consensusConfig.uncleDependencyGapTime)
    bestDeps.deps.mapWithIndexE {
      case (hash, k) if k != (groups - 1 + chainIndex.to.value) =>
        val hashIndex = ChainIndex.from(hash)
        val chain     = getHeaderChain(hashIndex)
        looseDependency(hash, chain, thresholdTs)
      case (hash, _) => Right(hash)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def looseDependency(
      hash: BlockHash,
      headerChain: BlockHeaderChain,
      thresholdTs: TimeStamp
  ): IOResult[BlockHash] = {
    headerChain.getTimestamp(hash).flatMap {
      case timeStamp if timeStamp <= thresholdTs =>
        Right(hash)
      case _ =>
        headerChain.getParentHash(hash).flatMap(looseDependency(_, headerChain, thresholdTs))
    }
  }

  def prepareBlockFlowUnsafe(chainIndex: ChainIndex): BlockFlowTemplate = {
    Utils.unsafe(prepareBlockFlow(chainIndex))
  }

  def getPersistedUtxos(
      groupIndex: GroupIndex,
      bestDeps: BlockDeps,
      lockupScript: LockupScript
  ): IOResult[AVector[AssetOutputInfo]] = {
    for {
      bestWorldState <- getPersistedWorldState(bestDeps, groupIndex)
      persistedUtxos <- bestWorldState
        .getAssetOutputs(
          lockupScript.assetHintBytes,
          maxUtxosReads,
          (_, output) => output.lockupScript == lockupScript
        )
        .map(
          _.map(p => AssetOutputInfo(p._1, p._2, PersistedOutput))
        )
    } yield persistedUtxos
  }

  def mergeUtxos(
      persistedUtxos: AVector[AssetOutputInfo],
      usedInCache: AVector[AssetOutputRef],
      newInCache: AVector[AssetOutputInfo]
  ): AVector[AssetOutputInfo] = {
    persistedUtxos.filter(p => !usedInCache.contains(p.ref)) ++ newInCache
  }

  def getRelevantUtxos(
      groupIndex: GroupIndex,
      bestDeps: BlockDeps,
      lockupScript: LockupScript
  ): IOResult[AVector[AssetOutputInfo]] = {
    for {
      persistedUtxos <- getPersistedUtxos(groupIndex, bestDeps, lockupScript)
      cachedResult   <- getUtxosInCache(groupIndex, bestDeps, lockupScript, persistedUtxos)
    } yield {
      val utxosInBlock = mergeUtxos(persistedUtxos, cachedResult._1, cachedResult._2)
      grandPool.getRelevantUtxos(groupIndex, lockupScript, utxosInBlock)
    }
  }

  def getPreOutputs(tx: Transaction): IOResult[Option[AVector[TxOutput]]] = {
    val chainIndex = tx.chainIndex
    val mainGroup  = chainIndex.from
    val bestDeps   = getBestDeps(mainGroup)
    for {
      worldState <- getPersistedWorldState(bestDeps, mainGroup)
      result <- tx.allInputRefs.foldE(Option(AVector.empty[TxOutput])) {
        case (Some(outputs), input) =>
          getPreOutput(mainGroup, worldState, input).map(_.map(outputs :+ _))
        case (None, _) => Right(None)
      }
    } yield result
  }

  def getPreOutput(
      mainGroup: GroupIndex,
      worldState: WorldState.Persisted,
      outputRef: TxOutputRef
  ): IOResult[Option[TxOutput]] = {
    getMemPool(mainGroup).getUtxo(outputRef) match {
      case Some(output) => Right(Some(output))
      case None         => worldState.getOutputOpt(outputRef)
    }
  }

  // We call getUsableUtxosOnce multiple times until the resulted tx does not change
  // In this way, we can guarantee that no concurrent utxos operations are making trouble
  def getUsableUtxos(
      lockupScript: LockupScript
  ): IOResult[AVector[AssetOutputInfo]] = {
    @tailrec
    def iter(lastTryOpt: Option[AVector[AssetOutputInfo]]): IOResult[AVector[AssetOutputInfo]] = {
      getUsableUtxosOnce(lockupScript) match {
        case Right(utxos) =>
          lastTryOpt match {
            case Some(lastTry) if isSame(utxos, lastTry) => Right(utxos)
            case _                                       => iter(Some(utxos))
          }
        case Left(error) => Left(error)
      }
    }
    iter(None)
  }

  def getUsableUtxosOnce(
      lockupScript: LockupScript
  ): IOResult[AVector[AssetOutputInfo]] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))
    val bestDeps = getBestDeps(groupIndex)
    getUsableUtxosOnce(groupIndex, bestDeps, lockupScript)
  }

  def getUsableUtxosOnce(
      groupIndex: GroupIndex,
      bestDeps: BlockDeps,
      lockupScript: LockupScript
  ): IOResult[AVector[AssetOutputInfo]] = {
    val currentTs = TimeStamp.now()
    getRelevantUtxos(groupIndex, bestDeps, lockupScript).map(
      _.filter(_.output.lockTime <= currentTs)
    )
  }

  // return the total balance, the locked balance, and the number of all utxos
  def getBalance(lockupScript: LockupScript): IOResult[(U256, U256, Int)] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))
    val bestDeps = getBestDeps(groupIndex)

    val currentTs = TimeStamp.now()
    getRelevantUtxos(groupIndex, bestDeps, lockupScript).map { utxos =>
      val balance = utxos.fold(U256.Zero)(_ addUnsafe _.output.amount)
      val lockedBalance = utxos.fold(U256.Zero) { case (acc, utxo) =>
        if (utxo.output.lockTime > currentTs) acc addUnsafe utxo.output.amount else acc
      }
      (balance, lockedBalance, utxos.length)
    }
  }

  def prepareUnsignedTx(
      fromKey: PublicKey,
      toLockupScript: LockupScript,
      lockTimeOpt: Option[TimeStamp],
      amount: U256,
      gasPrice: GasPrice
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val fromLockupScript = LockupScript.p2pkh(fromKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromKey)
    getUsableUtxos(fromLockupScript).map { utxos =>
      for {
        selected <- UtxoUtils.select(
          utxos,
          amount,
          gasPrice,
          defaultGasPerInput,
          defaultGasPerOutput,
          2
        ) // sometime only 1 output, but 2 is always safe
        unsignedTx <- UnsignedTransaction
          .transferAlf(
            selected.assets.map(asset => (asset.ref, asset.output)),
            fromLockupScript,
            fromUnlockScript,
            toLockupScript,
            lockTimeOpt,
            amount,
            if (selected.gas.value > minimalGas.value) selected.gas else minimalGas,
            gasPrice
          )
      } yield {
        unsignedTx
      }
    }
  }

  def getTxStatus(txId: Hash, chainIndex: ChainIndex): IOResult[Option[TxStatus]] =
    IOUtils.tryExecute {
      assume(brokerConfig.contains(chainIndex.from))
      val chain = getBlockChain(chainIndex)
      chain.getTxStatusUnsafe(txId).flatMap { chainStatus =>
        val confirmations = chainStatus.confirmations
        if (chainIndex.isIntraGroup) {
          Some(TxStatus(chainStatus.index, confirmations, confirmations, confirmations))
        } else {
          val confirmHash = chainStatus.index.hash
          val fromGroupConfirmations =
            getFromGroupConfirmationsUnsafe(confirmHash, chainIndex)
          val toGroupConfirmations =
            getToGroupConfirmationsUnsafe(confirmHash, chainIndex)
          Some(
            TxStatus(chainStatus.index, confirmations, fromGroupConfirmations, toGroupConfirmations)
          )
        }
      }
    }

  def getFromGroupConfirmationsUnsafe(hash: BlockHash, chainIndex: ChainIndex): Int = {
    assume(ChainIndex.from(hash) == chainIndex)
    val header        = getBlockHeaderUnsafe(hash)
    val fromChain     = getHeaderChain(chainIndex.from, chainIndex.from)
    val fromTip       = getOutTip(header, chainIndex.from)
    val fromTipHeight = fromChain.getHeightUnsafe(fromTip)

    @tailrec
    def iter(height: Int): Option[Int] = {
      val hashes = fromChain.getHashesUnsafe(height)
      if (hashes.isEmpty) {
        None
      } else {
        val header   = fromChain.getBlockHeaderUnsafe(hashes.head)
        val chainDep = header.uncleHash(chainIndex.to)
        if (fromChain.isBeforeUnsafe(hash, chainDep)) Some(height) else iter(height + 1)
      }
    }

    iter(fromTipHeight + 1) match {
      case None => 0
      case Some(firstConfirmationHeight) =>
        fromChain.maxHeightUnsafe - firstConfirmationHeight + 1
    }
  }

  def getToGroupConfirmationsUnsafe(hash: BlockHash, chainIndex: ChainIndex): Int = {
    assume(ChainIndex.from(hash) == chainIndex)
    val header        = getBlockHeaderUnsafe(hash)
    val toChain       = getHeaderChain(chainIndex.to, chainIndex.to)
    val toGroupTip    = getGroupTip(header, chainIndex.to)
    val toGroupHeader = getBlockHeaderUnsafe(toGroupTip)
    val toTip         = getOutTip(toGroupHeader, chainIndex.to)
    val toTipHeight   = toChain.getHeightUnsafe(toTip)

    assume(ChainIndex.from(toTip) == ChainIndex(chainIndex.to, chainIndex.to))

    @tailrec
    def iter(height: Int): Option[Int] = {
      val hashes = toChain.getHashesUnsafe(height)
      if (hashes.isEmpty) {
        None
      } else {
        val header   = toChain.getBlockHeaderUnsafe(hashes.head)
        val chainDep = getGroupTip(header, chainIndex.from)
        if (isExtendingUnsafe(chainDep, hash)) Some(height) else iter(height + 1)
      }
    }

    if (header.isGenesis) {
      toChain.maxHeightUnsafe - ALF.GenesisHeight + 1
    } else {
      iter(toTipHeight + 1) match {
        case None => 0
        case Some(firstConfirmationHeight) =>
          toChain.maxHeightUnsafe - firstConfirmationHeight + 1
      }
    }
  }

  private def ableToUse(
      output: TxOutput,
      lockupScript: LockupScript
  ): Boolean =
    output match {
      case o: AssetOutput    => o.lockupScript == lockupScript
      case _: ContractOutput => false
    }

  def getUtxosInCache(
      groupIndex: GroupIndex,
      bestDeps: BlockDeps,
      lockupScript: LockupScript,
      persistedUtxos: AVector[AssetOutputInfo]
  ): IOResult[(AVector[AssetOutputRef], AVector[AssetOutputInfo])] = {
    getBlocksForUpdates(groupIndex, bestDeps).map { blockCaches =>
      val usedUtxos = blockCaches.flatMap[AssetOutputRef] { blockCache =>
        AVector.from(
          blockCache.inputs.view
            .filter(input => persistedUtxos.exists(_.ref == input))
            .map(_.asInstanceOf[AssetOutputRef])
        )
      }
      val newUtxos = blockCaches.flatMap { blockCache =>
        AVector
          .from(
            blockCache.relatedOutputs.view
              .filter(p => ableToUse(p._2, lockupScript) && p._1.isAssetType && p._2.isAsset)
              .map(p =>
                AssetOutputInfo(
                  p._1.asInstanceOf[AssetOutputRef],
                  p._2.asInstanceOf[AssetOutput],
                  UnpersistedBlockOutput
                )
              )
          )
      }
      (usedUtxos, newUtxos)
    }
  }
}
// scalastyle:on number.of.methods

object FlowUtils {
  final case class AssetOutputInfo(ref: AssetOutputRef, output: AssetOutput, outputType: OutputType)

  sealed trait OutputType {
    def cachedLevel: Int
  }
  case object PersistedOutput extends OutputType {
    val cachedLevel = 0
  }
  case object UnpersistedBlockOutput extends OutputType {
    val cachedLevel = 1
  }
  case object SharedPoolOutput extends OutputType {
    val cachedLevel = 2
  }
  case object PendingPoolOutput extends OutputType {
    val cachedLevel = 3
  }

  def filterDoubleSpending[T <: TransactionAbstract: ClassTag](txs: AVector[T]): AVector[T] = {
    var output   = AVector.ofSize[T](txs.length)
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
    txs.foreach { tx =>
      if (tx.unsigned.inputs.forall(input => !utxoUsed.contains(input.outputRef))) {
        utxoUsed.addAll(tx.unsigned.inputs.toIterable.view.map(_.outputRef))
        output = output :+ tx
      }
    }
    output
  }

  def convertNonScriptTx(txTemplate: TransactionTemplate): Transaction = {
    Transaction(
      txTemplate.unsigned,
      AVector.empty,
      AVector.empty,
      txTemplate.inputSignatures,
      txTemplate.contractSignatures
    )
  }

  def convertSuccessfulTx(
      txTemplate: TransactionTemplate,
      result: TxScriptExecution
  ): Transaction = {
    Transaction(
      txTemplate.unsigned,
      result.contractInputs,
      result.generatedOutputs,
      txTemplate.inputSignatures,
      txTemplate.contractSignatures
    )
  }

  def deductGas(inputs: AVector[TxOutput], gasFee: U256): AVector[TxOutput] = {
    inputs.replace(0, inputs(0).payGasUnsafe(gasFee))
  }

  def convertFailedScriptTx(
      worldState: MutableWorldState,
      txTemplate: TransactionTemplate
  ): IOResult[Transaction] = {
    worldState.getPreOutputsForVM(txTemplate).map { inputs =>
      assume(inputs.forall(_.isAsset))
      val remainingBalances = deductGas(inputs, txTemplate.gasFeeUnsafe)
      Transaction(
        txTemplate.unsigned,
        AVector.empty,
        generatedOutputs = remainingBalances,
        txTemplate.inputSignatures,
        txTemplate.contractSignatures
      )
    }
  }

  def generateFullTx(
      worldState: WorldState.Cached,
      tx: TransactionTemplate,
      script: StatefulScript
  ): IOResult[Transaction] = {
    StatefulVM.runTxScript(worldState, tx, script, tx.unsigned.startGas) match {
      case Left(_)       => convertFailedScriptTx(worldState, tx)
      case Right(result) => Right(FlowUtils.convertSuccessfulTx(tx, result))
    }
  }

  def nextTimeStamp(parentTs: TimeStamp): TimeStamp = {
    val resultTs = TimeStamp.now()
    if (resultTs <= parentTs) {
      parentTs.plusMillisUnsafe(1)
    } else {
      resultTs
    }
  }

  def isSame(utxos0: AVector[AssetOutputInfo], utxos1: AVector[AssetOutputInfo]): Boolean = {
    (utxos0.length == utxos1.length) && {
      val set = utxos0.toSet
      utxos1.forall(set.contains)
    }
  }
}

trait SyncUtils {
  def getIntraSyncInventories(
      remoteBroker: BrokerGroupInfo
  ): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getIntraSyncInventoriesUnsafe(remoteBroker))

  def getSyncLocators(): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getSyncLocatorsUnsafe())

  def getSyncInventories(
      locators: AVector[AVector[BlockHash]]
  ): IOResult[AVector[AVector[BlockHash]]] =
    IOUtils.tryExecute(getSyncInventoriesUnsafe(locators))

  protected def getIntraSyncInventoriesUnsafe(
      remoteBroker: BrokerGroupInfo
  ): AVector[AVector[BlockHash]]

  protected def getSyncLocatorsUnsafe(): AVector[AVector[BlockHash]]

  protected def getSyncInventoriesUnsafe(
      locators: AVector[AVector[BlockHash]]
  ): AVector[AVector[BlockHash]]
}
