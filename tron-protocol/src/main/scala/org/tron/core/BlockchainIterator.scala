package org.tron.core

import java.util

import org.tron.protos.core.TronBlock.Block

class BlockchainIterator(blockchain: Blockchain) extends Iterator[Block] {

  var index = new Array[Byte](blockchain.currentHash.length)
  index = util.Arrays.copyOf(blockchain.currentHash, blockchain.currentHash.length)

  def hasNext = index == null || index.length == 0

  override def next() = {
    if (hasNext) {
      val value = blockchain.blockDb.getData(index)
      val block = Block.parseFrom(value)
      index = block.getBlockHeader.getParentHash.toByteArray
      block
    } else {
      null
    }
  }
}
