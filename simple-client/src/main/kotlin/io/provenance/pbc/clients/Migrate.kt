package io.provenance.pbc.clients

import feign.RequestLine
import io.provenance.pbc.proto.contract.ScopeProtos.Scope
import io.provenance.pbc.clients.tx.TxPreparer

data class MigrateScopeRequest(
    val base_req: BaseReq,
    val scope: Scope
)

interface Migrate {
    @RequestLine("POST /migrate/scope")
    fun prepareMigrateScope(req: MigrateScopeRequest): PreparedStdTx
}

fun Migrate.migrateScope(scope: Scope): TxPreparer = { base ->
    log.trace("migrateScope(scope:$scope")
    prepareMigrateScope(MigrateScopeRequest(base, scope))
}
