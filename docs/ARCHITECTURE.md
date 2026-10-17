# Architecture

Two implemented Gradle modules keep the simulation independent of Android. `core` is a Kotlin/JVM domain module, `app` hosts Android presentation and persistence. Avoid empty feature modules until there is enough code to justify them.

Compose → Hilt GameViewModel → CommandBus → validation/authorization → immutable world → persistence callback → StateFlow → Compose. The CLI translates supported text into the same SimulationCommand objects. All resource mutations, including topology positions, pass through the bus. ViewModel owns asynchronous operations; the repository loads bundled JSON and persists snapshots.

A Mutex serializes commands. State is computed on a copy; command failures keep prior resources and append a failed audit record. The persistence callback completes before StateFlow publishes the new world. Storage failure leaves the published world unchanged. No ticking coroutine runs while idle or backgrounded. One accepted or rejected command advances the virtual clock by one second. Simulation time is unrelated to wall-clock time.

WorldState includes account, region, identity, resources, policies, event log, tick, seed, hints, mission ID, costs, completion stars, XP, coins and achievement ticks. Resource attribute maps are currently used for the small supported model; objective predicates are explicitly enumerated, never substring matching.

Room stores one serialized world in an atomic row upsert. A full snapshot includes unfinished resources and the event log. Save format version is 1. Unknown future versions are rejected without overwriting their data. There is no historical version-0 migration because this is the first release. Future Room schema changes require explicit migrations; destructive fallback is not enabled. Stable mission IDs key progress independently of array positions.

Current compromises: the command bus contains service handlers rather than a separate pluggable SimulatorRegistry; UseCase responsibilities are the bus, CLI adapter and validator rather than one class per command. Repository save/load is real Room, not a fake implementation. UI selects world state through StateFlow. Fine-grained service model classes and a separate feature layer are future refactors.
