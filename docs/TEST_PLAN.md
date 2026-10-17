# Validation

Automated JVM suite covers all 12 missions: initial failure, invalid resource operation preserving resources, successful reference commands, snapshot round-trip with equal verdict, deterministic audit replay, idempotent rewards and exact resource reset. Additional tests cover IAM default/explicit deny and wildcard scopes, console/CLI shared state, malformed/unknown commands and options, terminated-state denial, unauthorized queries/mutations, EBS vs filesystem separation, and failed storage atomicity.

`tools/validate_content.py` validates Draft 2020-12 schemas and known references for all playable assets, plus the 60-entry catalog. Planned catalog entries are not treated as implemented levels. Emulator tests cover native home-to-mission navigation and Room closing/reopening the exact unfinished snapshot.

Unimplemented network services have no claimed packet-evaluation tests. There is no prior released save schema migration to test yet. Full phone/tablet/landscape/accessibility and real-device flight-mode testing remain follow-up work. Absence of INTERNET permission and network SDK code ensures the game itself does not request AWS resources.

Real command outcomes and emulator results are recorded in VALIDATION.md after execution. Presence of tests does not itself mean they have run.
