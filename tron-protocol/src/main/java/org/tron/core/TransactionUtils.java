/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.tron.core;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.crypto.ECKey;
import org.tron.protos.core.TronTXInput.TXInput;
import org.tron.protos.core.TronTXOutput.TXOutput;
import org.tron.protos.core.TronTransaction.Transaction;
import org.tron.utils.ByteArray;
import org.tron.wallet.Wallet;

import java.security.SecureRandom;
import java.util.*;

import static org.tron.crypto.Hash.sha256;
import static org.tron.utils.Utils.getRandom;

public class TransactionUtils {
    private static final Logger logger = LoggerFactory.getLogger("Transaction");

    private final static int RESERVE_BALANCE = 10;

    /**
     * new coinbase transaction
     *
     * @param to   String to sender's address
     * @param data String transaction data
     * @return {@link Transaction}
     */
    public static Transaction newCoinbaseTransaction(String to, String data) {
        if (data == null || data.equals("")) {
            byte[] randBytes = new byte[20];
            SecureRandom random = getRandom();
            random.nextBytes(randBytes);
            data = "" + ByteArray.toHexString(randBytes);
        }

        TXInput txi = TXInputUtils.newTXInput(new byte[]{}, -1, new byte[]{},
                ByteArray.fromHexString(data));
        TXOutput txo = TXOutputUtils.newTXOutput(RESERVE_BALANCE, to);

        Transaction.Builder coinbaseTransaction = Transaction.newBuilder()
                .addVin(txi)
                .addVout(txo);

        coinbaseTransaction.setId(ByteString.copyFrom(getHash
                (coinbaseTransaction.build())));

        return coinbaseTransaction.build();
    }

    /**
     * Obtain a data bytes after removing the id and SHA-256(data)
     *
     * @param transaction {@link Transaction} transaction
     * @return byte[] the hash of the transaction's data bytes which have no id
     */
    public static byte[] getHash(Transaction transaction) {
        Transaction.Builder tmp = transaction.toBuilder();
        tmp.clearId();

        return sha256(tmp.build().toByteArray());
    }

    /**
     * getData print string of the transaction
     *
     * @param transaction {@link Transaction} transaction
     * @return String format string of the transaction
     */
    public static String toPrintString(Transaction transaction) {
        if (transaction == null) {
            return "";
        }

        String str =
                "\nTransaction {\n" +
                        "\tid=" + ByteArray.toHexString(transaction.getId()
                        .toByteArray()) + "\n" +
                        "\tvin=[\n";
        int i = 0;
        for (TXInput vin : transaction.getVinList()) {
            str += "\t\t{\n" +
                    "\t\t\ttxID=" + ByteArray.toHexString(vin.getTxID()
                    .toByteArray()) + "\n" +
                    "\t\t\tvout=" + vin.getVout() + "\n" +
                    "\t\t\tsignature=" + ByteArray.toHexString(vin
                    .getSignature().toByteArray()) + "\n" +
                    "\t\t\tpubKey=" + ByteArray.toHexString(vin.getPubKey()
                    .toByteArray()) + "\n" +
                    "\t\t}";

            if (i != transaction.getVinList().size() - 1) {
                str += ",";
            }

            i++;

            str += "\n";
        }

        str += "\t],\n\tvout=[\n";

        i = 0;
        for (TXOutput vout : transaction.getVoutList()) {
            str += "\t\t{\n" +
                    "\t\t\tvalue=" + vout.getValue() + "\n" +
                    "\t\t\tpubKeyHash=" + ByteArray.toHexString(vout
                    .getPubKeyHash().toByteArray()) + "\n" +
                    "\t\t}";

            if (i != transaction.getVoutList().size() - 1) {
                str += ",";
            }

            i++;

            str += "\n";
        }

        str += "\t]\n}";

        return str;
    }

    /**
     * Determine whether the transaction is a coinbase transaction
     *
     * @param transaction {@link Transaction} transaction
     * @return boolean true for coinbase, false for not coinbase
     */
    public static boolean isCoinbaseTransaction(Transaction transaction) {
        return transaction.getVinList().size() == 1 && transaction.getVin(0)
                .getTxID().size() == 0 && transaction.getVin(0).getVout() == -1;
    }

    public static Transaction sign(Transaction transaction, ECKey myKey,
                                   HashMap<String, Transaction> prevTXs) {
        if (TransactionUtils.isCoinbaseTransaction(transaction)) {
            return null;
        }

        for (TXInput vin : transaction.getVinList()) {
            if (prevTXs.get(ByteArray.toHexString(vin.getTxID().toByteArray()
            )).getId().toByteArray().length == 0) {
                logger.error("ERROR: Previous transaction is not correct");
                return null;
            }
        }

        for (int i = 0; i < transaction.getVinList().size(); i++) {
            TXInput vin = transaction.getVin(i);
            Transaction prevTx = prevTXs.get(ByteArray.toHexString(vin
                    .getTxID().toByteArray()));


            Transaction.Builder transactionCopyBuilder = transaction
                    .toBuilder();
            TXInput.Builder vinBuilder = vin.toBuilder();
            vinBuilder.clearSignature();
            vinBuilder.setPubKey(prevTx.getVout((int) vin.getVout()).getPubKeyHash());
            transactionCopyBuilder.setVin(i, vinBuilder.build());
            transactionCopyBuilder.setId(ByteString.copyFrom(TransactionUtils
                    .getHash(transactionCopyBuilder.build())));
            vinBuilder.clearPubKey();
            transactionCopyBuilder.setVin(i, vinBuilder.build());

            Transaction.Builder transactionBuilder = transaction.toBuilder().setVin(i, vin.toBuilder()
                    .setSignature(ByteString.copyFrom(myKey.sign
                            (transactionCopyBuilder.getId().toByteArray())
                            .toByteArray())).build());

            transactionBuilder.setId(ByteString.copyFrom(TransactionUtils.getHash(transactionBuilder.build())));

            transaction = transactionBuilder.build();
        }

        return transaction;
    }

    public static boolean verify(ECKey myKey, Transaction transaction,
                                 HashMap<String, Transaction> prevTXs) {
        if (TransactionUtils.isCoinbaseTransaction(transaction)) {
            return true;
        }

        for (TXInput vin : transaction.getVinList()) {
            if (prevTXs.get(ByteArray.toHexString(vin.getTxID().toByteArray()
            )).getId().toByteArray().length == 0) {
                logger.error("ERROR: Previous transaction is not correct");
            }
        }

        for (int i = 0; i < transaction.getVinList().size(); i++) {
            TXInput vin = transaction.getVin(i);
            Transaction prevTx = prevTXs.get(ByteArray.toHexString(vin
                    .getTxID().toByteArray()));


            Transaction.Builder transactionCopyBuilder = transaction
                    .toBuilder();
            TXInput.Builder vinBuilder = vin.toBuilder();
            vinBuilder.clearSignature();
            vinBuilder.setPubKey(prevTx.getVout((int) vin.getVout()).getPubKeyHash());
            transactionCopyBuilder.setVin(i, vinBuilder.build());
            transactionCopyBuilder.setId(ByteString.copyFrom(TransactionUtils
                    .getHash(transactionCopyBuilder.build())));
            vinBuilder.clearPubKey();
            transactionCopyBuilder.setVin(i, vinBuilder.build());

            if (!myKey.verify(transactionCopyBuilder.getId().toByteArray(),
                    vin.getSignature().toByteArray())) {
                return false;
            }
        }

        return true;
    }

    // getData sender
    public static byte[] getSender(Transaction tx) {
        byte[] pubKey = tx.getVin(0).getPubKey().toByteArray();
        return ECKey.computeAddress(pubKey);
    }
}
