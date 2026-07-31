# T1DMSERVER API

The wire contract this app speaks to the sync server is specified once, for the
whole suite, in **`T1DMCOMMON/SPEC/http-api.md`**. It is not restated here: this
file was a copy for a while, and it drifted — the app's account of the contract
fell a version behind the server's while both sides carried on working.

- Repository: <https://github.com/0xdeadf1sh/T1DMCOMMON>
- Sibling checkout: `../T1DMCOMMON/SPEC/http-api.md`
- The version this app implements: `../T1DMCOMMON/CONTRACT_VERSION`

## Where it is implemented here

| Concern | Where |
| --- | --- |
| Wire DTOs and their serialization | `sync/src/main/kotlin/com/t1dm/sync/Dto.kt` |
| HTTP client and JSON configuration | `sync/src/main/kotlin/com/t1dm/sync/SyncHttpClient.kt` |
| Durable outbox, retry, re-mirror | `sync/src/main/kotlin/com/t1dm/sync/` |
| Model download and staging | `sync/src/main/kotlin/com/t1dm/sync/ModelSyncCoordinator.kt` |

Two client-side conventions are worth holding in view while reading the
contract. The serializer omits absent optionals rather than nulling them, and
the contract accepts either form on a write, so the two agree. And unknown keys
are ignored on read, so a server that gains a field cannot break a build of this
app that predates it.

## Changing it

Amend `T1DMCOMMON/SPEC/http-api.md` first and bump `CONTRACT_VERSION`; the
server then needs the counterpart change, which is a separate task in that
repository. The protocol is `T1DMCOMMON/skills/shared-contract-change`.
