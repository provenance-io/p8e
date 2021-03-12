```
                            ______  _____  _____ 
                            | ___ \|  _  ||  ___|
                            | |_/ / \ V / | |__  
                            |  __/  / _ \ |  __| 
                            | |    | |_| || |___ 
                            \_|    \_____/\____/ 

```
## Status

[![Latest Release][release-badge]][release-latest]
[![Code Coverage][code-coverage-badge]][code-coverage-report]
[![License][license-badge]][license-url]
[![LOC][loc-badge]][loc-report]

[code-coverage-badge]: https://codecov.io/gh/provenance-io/p8e/branch/main/graph/badge.svg
[code-coverage-report]: https://app.codecov.io/gh/provenance-io/p8e

[release-badge]: https://img.shields.io/github/v/tag/provenance-io/p8e.svg?sort=semver
[release-latest]: https://github.com/provenance-io/p8e/releases/latest

[license-badge]: https://img.shields.io/github/license/provenance-io/p8e.svg
[license-url]: https://github.com/provenance-io/p8e/blob/main/LICENSE

[loc-badge]: https://tokei.rs/b1/github/provenance-io/p8e
[loc-report]: https://github.com/provenance-io/p8e

# P8E — Provenance Contract Memorialization on DLT

P8e provides a system for collaborative execution, consensus, and proof recording of business execution on a distributed ledger
without revealing the contents of transactions.

## P8E-PROTO

Specifically the `contract_scope` (blockchain transaction block format), and `contract_spec` (contract execution requirements)
proto sets distill the core requirements for contract memorialization.  The Provenance SDK provides one implementation to satisfy
the requirements of completing these structures.  Alternate implements would be responsible for providing the required information
and submitting to the blockchain for validation/recording.

## Provenance Blockchain

All of the contract memorialization artifacts are stored within [Provenance](https://github.com/provenance-io/provenance) DLT 
on blockchain. Submitted contract memorialization requests are evaluated against the known global provenance state.  Chain 
of custody and control is enforced for all state transitions to ensure provenance of data is maintained. 

## Contract

Contract provides an implementation of the contract ledger rules. A contract for memorialization is submitted
in an `envelope` protobuf that contains a reference to the existing contract specification, parties involved with the request,
and references to any existing resources on-chain that are involved in the request.