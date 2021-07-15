```
                            ______  _____  _____
                            | ___ \|  _  ||  ___|
                            | |_/ / \ V / | |__
                            |  __/  / _ \ |  __|
                            | |    | |_| || |___
                            \_|    \_____/\____/

```
## Status

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.provenance.p8e/p8e-sdk/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.provenance.p8e/p8e-sdk)
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

# P8E — Provenance Contract Execution Environment

The Provenance Contact Execution Environment (nicknamed “P8e”) is an optional layer on top of the Provenance Blockchain
to allow single and multi-party client-side contract execution while preserving data privacy.
Provenance client-side contracts take encrypted data from the user (client) and transform the information into
encrypted data in the user’s own private object store with object hashes recorded on the blockchain.

Further documentation is provided [here](https://docs.provenance.io/p8e/overview).

## Provenance Blockchain

All of the contract memorialization artifacts are stored within the [Provenance](https://github.com/provenance-io/provenance)
open source blockchain. Submitted contract memorialization requests are evaluated against the known global provenance state.
Chain of custody and control is enforced for all state transitions to ensure provenance of data is maintained.

## Links
- [docs](https://docs.provenance.io/)
- [provenance github](https://github.com/provenance-io/provenance)
- [p8e-gradle-plugin github](https://github.com/provenance-io/p8e-gradle-plugin)
- [object-store github](https://github.com/provenance-io/object-store)
