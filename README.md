```
                            ______  _____  _____ 
                            | ___ \|  _  ||  ___|
                            | |_/ / \ V / | |__  
                            |  __/  / _ \ |  __| 
                            | |    | |_| || |___ 
                            \_|    \_____/\____/ 

```

# P8E — Provenance Contract Memorialization on DLT

P8e provides a system for collaborative execution, consensus, and proof recording of business execution on a distributed ledger
without revealing the contents of transactions.

## Smart Contract Engine, Indexer, Encrypted Object Store, Mailbox, etc (lots more stuff here)

Lee / Travis / Matt


## P8E-PROTO

Specifically the `contract_scope` (blockchain transaction block format), and `contract_spec` (contract execution requirements)
proto sets distill the core requirements for contract memorialization.  The Provenance SDK provides one implementation to satisfy
the requirements of completing these structures.  Alternate implements would be responsible for providing the required information
and submitting to the blockchain for validation/recording.

## Provenance Blockchain

All of the contract memorialization artifacts are stored within the `provenance-channel` DLT on blockchain.  Currently 
blockchain operations are handled through the adminapi service.  Submitted contract memorialization requests are evaluated
against the known global provenance state.  Chain of custody and control is enforced for all state transitions to ensure
provenance of data is maintained.  Participants in transactions are identified by Affiliate certificate registrations on chain.


### Contract Chaincode

Contract chaincode provides an implementation of the contract ledger rules.  A  contract for memorialization is submitted
in an `envelope` protobuf that contains a reference to the existing contract specification, affiliates involved with the request,
and references to any existing resources on chain that are involved in the request.

### Affiliate Chaincode

The Affiliate ledger contains records for each affiliate that contains their permission roles and basic information along with a list
of all known certificates and public keys for each affiliate.  These records are retrieved and compared against information provided
to the contract ledger during memorialization.