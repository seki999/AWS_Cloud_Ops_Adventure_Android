# Content contract

`schemas/mission.schema.json` is JSON Schema Draft 2020-12. `MissionDefinition` is the Kotlin serialization counterpart. All object shapes reject extra fields in schema; Kotlin defaults fill optional constraints and reward defaults. Text3 requires zh/ja/en strings. Objective kinds are explicitly enumerated and unknown runtime predicates return false.

Run `python tools/validate_content.py`: validates every executable mission, checks uniqueness of resource IDs, verifies objective targets exist initially or are created by reference commands, validates initial resource dependencies, and asserts the 60-entry catalog has exactly 12 playable entries. `python tools/generate_content.py` regenerates assets from source and schema deterministically.

`faults` currently identifies authored scenarios whose effects are encoded in initial resources. Fault injection as a general event command is not implemented. The catalog includes plans separately, avoiding placeholder missions advertised as playable. Content additions require simulator implementation, negative cases and solution-path tests before playable is set true.
