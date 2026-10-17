# Content contract

`schemas/mission.schema.json` is JSON Schema Draft 2020-12. `MissionDefinition` is the Kotlin serialization counterpart. All object shapes reject extra fields in schema; Kotlin defaults fill optional constraints and reward defaults. Text3 requires zh/ja/en strings. Objective kinds are explicitly enumerated (35 kinds, e.g. `network`, `networkBlocked`, `noOpenIngress`, `objectReadable`, `database`, `serviceReady`, `alarm`, `containerReady`, `architecture`) and unknown runtime predicates return false. `constraints` use the same model and must also hold for a pass.

Run `python tools/validate_content.py`: validates every executable mission, checks uniqueness of resource IDs, verifies objective targets exist initially or are created by reference commands, validates initial resource dependencies, and asserts the 60-entry catalog has 60 playable entries backed by 60 mission files. `python tools/generate_content.py` regenerates assets from source and schema deterministically.

`faults` currently identifies authored scenarios whose effects are encoded in initial resources. Fault injection as a general event command is not implemented. The catalog includes plans separately, avoiding placeholder missions advertised as playable. Content additions require simulator implementation, negative cases and solution-path tests before playable is set true.
