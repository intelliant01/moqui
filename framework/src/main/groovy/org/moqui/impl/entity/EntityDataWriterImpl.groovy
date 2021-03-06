/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.entity

import java.sql.Timestamp

import org.apache.commons.collections.set.ListOrderedSet

import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.entity.EntityDataWriter
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityCondition.ComparisonOperator

import org.slf4j.LoggerFactory
import org.slf4j.Logger

class EntityDataWriterImpl implements EntityDataWriter {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDataWriterImpl.class)

    protected EntityFacadeImpl efi

    protected int txTimeout = 3600
    protected ListOrderedSet entityNames = new ListOrderedSet()
    protected boolean dependents = false
    protected String prefix = null
    protected Map<String, Object> filterMap = [:]
    protected List<String> orderByList = []
    protected Timestamp fromDate = null
    protected Timestamp thruDate = null

    EntityDataWriterImpl(EntityFacadeImpl efi) { this.efi = efi }

    EntityFacadeImpl getEfi() { return efi }

    EntityDataWriter entityName(String entityName) { entityNames.add(entityName);  return this }
    EntityDataWriter entityNames(List<String> enList) { entityNames.addAll(enList);  return this }
    EntityDataWriter dependentRecords(boolean d) { dependents = d; return this }
    EntityDataWriter prefix(String p) { prefix = p; return this }
    EntityDataWriter filterMap(Map<String, Object> fm) { filterMap.putAll(fm); return this }
    EntityDataWriter orderBy(List<String> obl) { orderByList.addAll(obl); return this }
    EntityDataWriter fromDate(Timestamp fd) { fromDate = fd; return this }
    EntityDataWriter thruDate(Timestamp td) { thruDate = td; return this }

    int file(String filename) {
        File outFile = new File(filename)
        if (!outFile.createNewFile()) {
            efi.ecfi.executionContext.message.addError("File ${filename} already exists.")
            return 0
        }

        PrintWriter pw = new PrintWriter(outFile)
        int valuesWritten = this.writer(pw)
        pw.close()
        efi.ecfi.executionContext.message.addMessage("Wrote ${valuesWritten} records to file ${filename}")
        return valuesWritten
    }

    int directory(String path) {
        File outDir = new File(path)
        if (!outDir.exists()) outDir.mkdir()
        if (!outDir.isDirectory()) {
            efi.ecfi.executionContext.message.addError("Path ${path} is not a directory.")
            return 0
        }

        if (dependents) efi.createAllAutoReverseManyRelationships()

        int valuesWritten = 0

        TransactionFacade tf = efi.getEcfi().getTransactionFacade()
        boolean suspendedTransaction = false
        try {
            if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(txTimeout)
            try {
                for (String en in entityNames) {
                    String filename = "${path}/${en}.xml"
                    File outFile = new File(filename)
                    if (outFile.exists()) {
                        efi.ecfi.executionContext.message.addError("File ${filename} already exists, skipping entity ${en}.")
                        continue
                    }
                    outFile.createNewFile()

                    PrintWriter pw = new PrintWriter(outFile)
                    pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    pw.println("<entity-facade-xml>")

                    EntityFind ef = efi.find(en).condition(filterMap).orderBy(orderByList)
                    EntityDefinition ed = efi.getEntityDefinition(en)
                    if (ed.isField("lastUpdatedStamp")) {
                        if (fromDate) ef.condition("lastUpdatedStamp", ComparisonOperator.GREATER_THAN_EQUAL_TO, fromDate)
                        if (thruDate) ef.condition("lastUpdatedStamp", ComparisonOperator.LESS_THAN, thruDate)
                    }
                    EntityListIterator eli = ef.iterator()
                    valuesWritten += eli.writeXmlText(pw, prefix, dependents)
                    eli.close()

                    pw.println("</entity-facade-xml>")
                    pw.close()
                    efi.ecfi.executionContext.message.addMessage("Wrote ${valuesWritten} records to file ${filename}")
                }

                return valuesWritten
            } catch (Throwable t) {
                logger.warn("Error writing data", t)
                tf.rollback(beganTransaction, "Error writing data", t)
                efi.getEcfi().getExecutionContext().getMessage().addError(t.getMessage())
            } finally {
                if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            try {
                if (suspendedTransaction) tf.resume()
            } catch (Throwable t) {
                logger.error("Error resuming parent transaction after data write", t)
            }
        }
    }

    int writer(Writer writer) {
        if (dependents) efi.createAllAutoReverseManyRelationships()

        TransactionFacade tf = efi.getEcfi().getTransactionFacade()
        boolean suspendedTransaction = false
        try {
            if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(txTimeout)
            try {
                writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                writer.println("<entity-facade-xml>")

                int valuesWritten = 0
                for (String en in entityNames) {
                    EntityFind ef = efi.find(en).condition(filterMap).orderBy(orderByList)
                    EntityDefinition ed = efi.getEntityDefinition(en)
                    if (ed.isField("lastUpdatedStamp")) {
                        if (fromDate) ef.condition("lastUpdatedStamp", ComparisonOperator.GREATER_THAN_EQUAL_TO, fromDate)
                        if (thruDate) ef.condition("lastUpdatedStamp", ComparisonOperator.LESS_THAN, thruDate)
                    }

                    /* leaving commented as might be useful for future con pool debugging:
                    try {
                        def dataSource = efi.getDatasourceFactory(efi.getEntityGroupName(ed)).getDataSource()
                        logger.warn("=========== edwi pool available size: ${dataSource.poolAvailableSize()}/${dataSource.poolTotalSize()}; ${dataSource.getMinPoolSize()}-${dataSource.getMaxPoolSize()}")
                    } catch (Throwable t) {
                        logger.warn("========= pool size error ${t.toString()}")
                    }
                    */

                    EntityListIterator eli = ef.iterator()
                    try {
                        valuesWritten += eli.writeXmlText(writer, prefix, dependents)
                    } finally {
                        eli.close()
                    }
                }

                writer.println("</entity-facade-xml>")
                writer.println("")

                return valuesWritten
            } catch (Throwable t) {
                logger.warn("Error writing data: " + t.toString(), t)
                tf.rollback(beganTransaction, "Error writing data", t)
                efi.getEcfi().getExecutionContext().getMessage().addError(t.getMessage())
            } finally {
                if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            try {
                if (suspendedTransaction) tf.resume()
            } catch (Throwable t) {
                logger.error("Error resuming parent transaction after data write", t)
            }
        }
    }
}
