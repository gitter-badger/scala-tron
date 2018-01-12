package org.tron.core
import com.google.protobuf.ByteString
import org.tron.core.Constant.LAST_HASH
import org.tron.crypto.ECKey
import org.tron.protos.core.TronBlock.Block
import org.tron.protos.core.TronTXOutputs.TXOutputs
import org.tron.protos.core.TronTransaction
import org.tron.protos.core.TronTransaction.Transaction
import org.tron.storage.leveldb.LevelDbDataSourceImpl
import org.tron.utils.ByteArray
import sun.nio.ch.Net

import scala.collection.mutable

class BlockchainImpl(address: String) extends Blockchain {

  var lastHash: Array[Byte] = null
  var currentHash: Array[Byte] = null

  def findTransaction(id: Array[Byte]): Option[Transaction] = {

    val bi = new BlockchainIterator(this)

    bi
      .flatMap { block => block.transactions }
      .find { tx =>
        val txID = ByteArray.toHexString(tx.id.toByteArray)
        val idStr = ByteArray.toHexString(id)
        txID == idStr
      }
  }

  def findUTXO(): Map[String, TXOutputs] = {

    val spenttxos = mutable.Map[String, Array[Long]]()
    val utxo = mutable.Map[String, TXOutputs]()

    val bi = new BlockchainIterator(this)

    def isSpent(txid: String, index: Long): Boolean = spenttxos.get(txid).exists(_.contains(index))

    for {
      block <- bi
      transaction <- block.transactions
    } {
      val txid = ByteArray.toHexString(transaction.id.toByteArray)

      for {
        outIdx <- 0 to transaction.vout.size
        out = transaction.vout(outIdx) if !isSpent(txid, outIdx)
      } {
        var outs = utxo.getOrElse(txid, TXOutputs())
        outs = outs.addOutputs(out)
        utxo.put(txid, outs)
      }


      if (!TransactionUtils.isCoinbaseTransaction(transaction)) {
        for (in <- transaction.vin) {
          val inTxid = ByteArray.toHexString(in.txID.toByteArray)
          val vindexs = spenttxos.getOrElse(inTxid, Array[Long]())
          spenttxos.put(inTxid, vindexs :+ in.vout)
        }
      }
    }

    utxo.toMap
  }


  override def addBlock(block: Block): Unit = {
    val blockInDB = blockDB.getData(block.getBlockHeader.hash.toByteArray)

    if (blockInDB == null || blockInDB.isEmpty) {
      return
    }

    blockDB.putData(block.getBlockHeader.hash.toByteArray, block.toByteArray)

    val lashHash = ByteArray.fromString("lashHash")

    val lastHash = blockDB.getData(lashHash)
    val lastBlockData = blockDB.getData(lastHash)
    val lastBlock = Block.parseFrom(lastBlockData)

    if (block.getBlockHeader.number > lastBlock.getBlockHeader.number) {
      blockDB.putData(lashHash, block.getBlockHeader.hash.toByteArray)
      this.lastHash = block.getBlockHeader.hash.toByteArray
      this.currentHash = this.lastHash
    }
  }

  override def signTransaction(transaction: TronTransaction.Transaction, key: ECKey): TronTransaction.Transaction = ???

  override def addBlock(transactions: List[TronTransaction.Transaction], net: Net): Unit = {
    // get lastHash
    val lastHash = blockDB.getData(LAST_HASH)
    val parentHash = ByteString.copyFrom(lastHash)
    // get number
    val number = BlockUtils.getIncreaseNumber(this)
    // get difficulty
    val difficulty = ByteString.copyFromUtf8(Constant.DIFFICULTY)

    BlockUtils.newBlock(transactions, parentHash, difficulty, number)
    // TODO send to kafka
  }

  override def blockDB: LevelDbDataSourceImpl = ???
}
