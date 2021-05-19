package io.provenance.p8e.shared.sql

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.BatchInsertStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager

class BatchInsertOnConflictIgnore(table: Table) : BatchInsertStatement(table, false) {
    override fun prepareSQL(transaction: Transaction): String {
        return super.prepareSQL(transaction) + " ON CONFLICT DO NOTHING"
    }
}

fun <T : Table, E> T.batchInsertOnConflictIgnore(data: List<E>, body: T.(BatchInsertOnConflictIgnore, E) -> Unit) {
    data.takeIf { it.isNotEmpty() }
        ?.let {
            val insert = BatchInsertOnConflictIgnore(this)
            data.forEach {
                insert.addBatch()
                body(insert, it)
            }
            TransactionManager.current().exec(insert)
        }
}
